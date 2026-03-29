package com.akdevelopers.auracast.data.streaming

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.akdevelopers.auracast.analytics.Analytics
import com.akdevelopers.auracast.analytics.CrashManager
import com.akdevelopers.auracast.audio.AudioQualityConfig
import com.akdevelopers.auracast.domain.streaming.AudioCaptureController
import com.akdevelopers.auracast.domain.streaming.AudioCaptureFactory
import com.akdevelopers.auracast.domain.streaming.AudioTransport
import com.akdevelopers.auracast.domain.streaming.MetricsPublisher
import com.akdevelopers.auracast.domain.streaming.RemoteCommandSource
import com.akdevelopers.auracast.domain.streaming.StatusPublisher
import com.akdevelopers.auracast.domain.streaming.StreamOrchestrator
import com.akdevelopers.auracast.domain.streaming.StreamRuntimeStore
import com.akdevelopers.auracast.service.StreamStatus
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

class DefaultStreamOrchestrator(
    private val appContext: Context,
    private val audioTransport: AudioTransport,
    private val remoteCommandSource: RemoteCommandSource,
    private val statusPublisher: StatusPublisher,
    private val metricsPublisher: MetricsPublisher,
    audioCaptureFactory: AudioCaptureFactory,
    /**
     * Called when [applyQuality] needs a new factory for the given config.
     * Provided by the app layer (e.g. AuraCastAppGraph) which has access to the
     * concrete capture implementation.
     */
    private val captureFactoryBuilder: (AudioQualityConfig) -> AudioCaptureFactory,
    /**
     * Optional selector invoked when a phone call starts.
     *
     * Returns a [Pair] of (factory, audioMode). The factory is used for the call
     * capture session; the audioMode string is forwarded to the server so the browser
     * dashboard can display the correct label:
     *  - `"call"`        — mic only (standard VOICE_COMMUNICATION source)
     *  - `"call_mixed"`  — mic + earpiece (AudioPlaybackCapture via MediaProjection)
     *
     * If null, [captureFactoryBuilder] is called with [AudioQualityConfig.CALL] and
     * mode `"call"` (existing behaviour — no earpiece capture).
     */
    private val callModeSelector: (() -> Pair<AudioCaptureFactory, String>)? = null,
) : StreamOrchestrator {

    companion object {
        private const val TAG = "StreamOrchestrator"
        private const val METRICS_INTERVAL_MS = 60_000L
        /**
         * Minimum gap between successive reconnect attempts triggered by
         * [onNetworkAvailable]. Android can fire many network-capability callbacks
         * in rapid succession when the phone reconnects to WiFi, each of which
         * would otherwise call [AudioTransport.reconnectNow] and open a new socket
         * before the previous TLS handshake completes — producing the connection
         * storm visible in the server logs. A 5 s cooldown collapses the burst
         * into at most one reconnect per network-state transition.
         */
        private const val NETWORK_RECONNECT_COOLDOWN_MS = 5_000L
    }

    override val status: StateFlow<StreamStatus> = StreamRuntimeStore.status
    override val isConnected: StateFlow<Boolean> = StreamRuntimeStore.isConnected
    override val isRunning: StateFlow<Boolean> = StreamRuntimeStore.isRunning

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameCounter = AtomicInteger(0)

    /** Currently active capture factory — swapped when quality or call mode changes. */
    private var audioCaptureFactory: AudioCaptureFactory = audioCaptureFactory

    /** The quality config set by the user or server. Preserved across call interruptions. */
    private var currentQualityConfig: AudioQualityConfig = AudioQualityConfig.HD

    /** Active encoding bitrate — updated by applyBitrate() without restarting the mic. */
    private var currentBitrateBps: Int = AudioQualityConfig.HD.opusBitrate

    /** True while the phone is in an active call (OFFHOOK). */
    private var inPhoneCall = false

    private var bytesInWindow = 0L
    private var serviceStartEpoch = 0L
    private var serverUrl = ""
    private var captureRestartAttempt = 0
    private var micActive = false
    private var captureController: AudioCaptureController? = null
    /** Epoch ms of the last reconnect triggered by a network-availability callback. */
    private var lastNetworkReconnectMs = 0L

    init {
        audioTransport.onStatusChange = { transportStatus ->
            val connected = transportStatus == StreamStatus.CONNECTED_IDLE ||
                transportStatus == StreamStatus.STREAMING
            StreamRuntimeStore.updateConnection(connected)

            val effectiveStatus = if (transportStatus == StreamStatus.CONNECTED_IDLE && micActive) {
                StreamStatus.STREAMING
            } else {
                transportStatus
            }
            StreamRuntimeStore.updateStatus(effectiveStatus)
            statusPublisher.publishStatus(effectiveStatus, serverUrl)
            CrashManager.setStreamContext(effectiveStatus, serverUrl, audioCaptureFactory.qualityName)

            if (transportStatus == StreamStatus.CONNECTED_IDLE) {
                // Re-announce the current codec params on every (re-)connect.
                // buildDelegate() always creates the transport with HIGH_QUALITY defaults,
                // so after a server-pushed quality change, the stale onOpen codec message
                // must be corrected immediately.
                audioTransport.sendCodecConfig(
                    sampleRate = currentQualityConfig.sampleRate,
                    frameMs    = currentQualityConfig.frameMs,
                    bitrate    = currentBitrateBps,
                )
                audioTransport.sendStreamingState(micActive)
                if (micActive) {
                    StreamRuntimeStore.updateStatus(StreamStatus.STREAMING)
                    statusPublisher.publishStatus(StreamStatus.STREAMING, serverUrl)
                }
            }
        }
    }

    override fun bindCommandHandler(
        handler: (commandId: String, action: String, url: String, source: String) -> Unit
    ) {
        remoteCommandSource.onCommandReceived = handler
    }

    override fun start(serverUrl: String, startMicImmediately: Boolean) {
        this.serverUrl = serverUrl
        this.serviceStartEpoch = System.currentTimeMillis()
        this.captureRestartAttempt = 0
        this.frameCounter.set(0)
        this.bytesInWindow = 0L

        remoteCommandSource.start(serverUrl, appContext)
        StreamRuntimeStore.updateRunning(true)
        StreamRuntimeStore.updateStatus(StreamStatus.CONNECTING)
        statusPublisher.publishStatus(StreamStatus.CONNECTING, serverUrl)
        audioTransport.connect(serverUrl, appContext)

        if (startMicImmediately) {
            startMic()
        }
    }

    override fun startMic() {
        if (micActive) {
            Log.w(TAG, "startMic: already active")
            return
        }
        micActive = true
        captureRestartAttempt = 0
        frameCounter.set(0)
        bytesInWindow = 0L

        Analytics.setStreamingActiveUserProperty(true)
        Analytics.setQualityUserProperty(audioCaptureFactory.qualityName)
        audioTransport.sendStreamingState(true)
        StreamRuntimeStore.updateStatus(StreamStatus.STREAMING)
        statusPublisher.publishStatus(StreamStatus.STREAMING, serverUrl)
        schedulePeriodicMetrics()

        captureController = audioCaptureFactory.create(
            onFrameReady = { frame ->
                frameCounter.incrementAndGet()
                bytesInWindow += frame.size
                audioTransport.sendFrame(frame)
            },
            onError = { message -> onMicError(message) }
        ).also { it.start() }
    }

    override fun stopMic() {
        if (!micActive) {
            Log.w(TAG, "stopMic: already idle")
            return
        }
        micActive = false
        mainHandler.removeCallbacks(metricsRunnable)
        captureController?.stop()
        captureController = null
        captureRestartAttempt = 0

        Analytics.setStreamingActiveUserProperty(false)
        if (serviceStartEpoch > 0L) {
            Analytics.logSessionDuration(
                System.currentTimeMillis() - serviceStartEpoch,
                audioCaptureFactory.qualityName
            )
        }

        audioTransport.sendStreamingState(false)
        val newStatus = if (StreamRuntimeStore.status.value == StreamStatus.RECONNECTING) {
            StreamStatus.RECONNECTING
        } else {
            StreamStatus.CONNECTED_IDLE
        }
        StreamRuntimeStore.updateStatus(newStatus)
        statusPublisher.publishStatus(newStatus, serverUrl)
    }

    override fun reconnectNow() {
        remoteCommandSource.reconnectNow()
        audioTransport.reconnectNow()
    }

    override fun reconnectTo(serverUrl: String) {
        this.serverUrl = serverUrl
        remoteCommandSource.reconnectTo(serverUrl, appContext)
        audioTransport.reconnectTo(serverUrl, appContext)
        statusPublisher.publishStatus(StreamRuntimeStore.status.value, serverUrl)
    }

    override fun onNetworkAvailable() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkReconnectMs < NETWORK_RECONNECT_COOLDOWN_MS) {
            Log.d(TAG, "onNetworkAvailable: suppressed (cooldown active)")
            return
        }
        // Always reconnect both transports on every network-available event that
        // passes the cooldown. We cannot reliably check remoteCommandSource.isOpen
        // because CompositeRemoteCommandSource.isOpen returns true as long as the
        // Firebase bridge (which is always connected) is alive, masking a dropped
        // control WebSocket. Reconnecting an already-open socket is cheap — OkHttp
        // performs the TLS handshake asynchronously and the generation guard on both
        // clients prevents duplicate connections.
        lastNetworkReconnectMs = now
        Log.i(TAG, "onNetworkAvailable: reconnecting all transports (audioOpen=${audioTransport.isOpen})")
        audioTransport.reconnectNow()
        remoteCommandSource.reconnectNow()
    }

    override fun onCallStarted() {
        if (inPhoneCall) return
        inPhoneCall = true
        Log.i(TAG, "onCallStarted: switching to CALL quality config")
        // Stop current capture so VOICE_COMMUNICATION source can open cleanly
        captureController?.stop()
        captureController = null
        // Use mixed (mic + earpiece) factory if a MediaProjection token is available,
        // otherwise fall back to mic-only call mode (existing behaviour).
        val (factory, mode) = callModeSelector?.invoke()
            ?: (captureFactoryBuilder(AudioQualityConfig.CALL) to "call")
        audioCaptureFactory = factory
        audioTransport.sendAudioMode(mode)
        Log.i(TAG, "onCallStarted: mode=$mode factory=${factory.qualityName}")
        if (micActive) {
            scheduleCaptureRestart("call started")
        }
    }

    override fun onCallEnded() {
        if (!inPhoneCall) return
        inPhoneCall = false
        Log.i(TAG, "onCallEnded: reverting to user quality config")
        captureController?.stop()
        captureController = null
        // Revert to whatever quality the user/server had set before the call,
        // then re-apply any pending bitrate override on top.
        val revertConfig = currentQualityConfig.copy(opusBitrate = currentBitrateBps)
        audioCaptureFactory = captureFactoryBuilder(revertConfig)
        audioTransport.sendAudioMode("mic")
        if (micActive) {
            scheduleCaptureRestart("call ended")
        }
    }

    override fun applyQuality(config: AudioQualityConfig) {
        Log.i(TAG, "applyQuality: ${config.opusBitrate / 1000}kbps ${config.sampleRate / 1000}kHz frameMs=${config.frameMs}")
        currentQualityConfig = config
        currentBitrateBps = config.opusBitrate
        // Don't change factory while a call is active — call config takes priority
        if (inPhoneCall) {
            Log.d(TAG, "applyQuality: in call — deferred until call ends")
            return
        }
        audioCaptureFactory = captureFactoryBuilder(config)
        // Re-announce codec so server and browsers reflect new quality immediately.
        audioTransport.sendCodecConfig(config.sampleRate, config.frameMs, config.opusBitrate)
        if (micActive) {
            // Restart capture with new settings non-disruptively
            captureController?.stop()
            captureController = null
            scheduleCaptureRestart("quality changed")
        }
    }

    override fun applyBitrate(bps: Int) {
        Log.i(TAG, "applyBitrate: ${bps / 1000} kbps (capture stays at 48 kHz HD — no mic restart)")
        currentBitrateBps = bps
        if (inPhoneCall) {
            Log.d(TAG, "applyBitrate: in call — deferred until call ends")
            return
        }
        // Hot-swap the encoder bitrate in the running capture session.
        captureController?.setBitrate(bps)
        // Re-announce the new codec config so the server and browsers know the bitrate changed.
        audioTransport.sendCodecConfig(
            sampleRate = currentQualityConfig.sampleRate,
            frameMs    = currentQualityConfig.frameMs,
            bitrate    = bps,
        )
    }

    override fun stopAll() {
        mainHandler.removeCallbacksAndMessages(null)
        micActive = false
        inPhoneCall = false
        captureRestartAttempt = 0
        captureController?.release()
        captureController = null
        audioTransport.disconnect()
        remoteCommandSource.stop()
        statusPublisher.publishStatus(StreamStatus.IDLE, serverUrl)
        StreamRuntimeStore.reset()
    }

    private fun onMicError(message: String) {
        StreamRuntimeStore.updateStatus(StreamStatus.MIC_ERROR)
        statusPublisher.publishStatus(StreamStatus.MIC_ERROR, serverUrl)
        CrashManager.recordNonFatal(
            "AudioCaptureEngine error: $message",
            "quality=${audioCaptureFactory.qualityName}"
        )
        Analytics.logMicError(message, audioCaptureFactory.qualityName)
        scheduleCaptureRestart(message)
    }

    private fun scheduleCaptureRestart(reason: String) {
        captureController?.stop()
        captureController = null
        val delayMs = minOf(3_000L shl captureRestartAttempt, 30_000L)
        captureRestartAttempt++
        Log.i(TAG, "scheduleCaptureRestart: attempt=$captureRestartAttempt delay=${delayMs}ms reason=$reason")
        Analytics.logMicRestartScheduled(captureRestartAttempt, delayMs)
        mainHandler.postDelayed({
            if (!micActive) return@postDelayed
            var firstFrame = true
            captureController = audioCaptureFactory.create(
                onFrameReady = { frame ->
                    if (firstFrame) {
                        firstFrame = false
                        captureRestartAttempt = 0
                        Analytics.logMicRecovered(captureRestartAttempt)
                        StreamRuntimeStore.updateStatus(StreamStatus.STREAMING)
                        statusPublisher.publishStatus(StreamStatus.STREAMING, serverUrl)
                    }
                    frameCounter.incrementAndGet()
                    bytesInWindow += frame.size
                    audioTransport.sendFrame(frame)
                },
                onError = { error -> onMicError(error) }
            ).also { it.start() }
        }, delayMs)
    }

    private fun schedulePeriodicMetrics() {
        mainHandler.removeCallbacks(metricsRunnable)
        mainHandler.postDelayed(metricsRunnable, METRICS_INTERVAL_MS)
    }

    private val metricsRunnable = object : Runnable {
        override fun run() {
            if (!micActive) return
            val frames = frameCounter.getAndSet(0)
            val bytes = bytesInWindow.also { bytesInWindow = 0L }
            val windowSec = METRICS_INTERVAL_MS / 1_000f
            val fps = frames / windowSec
            val kbps = (bytes * 8f) / (windowSec * 1_000f)
            val uptimeSec = ((System.currentTimeMillis() - serviceStartEpoch) / 1_000).toInt()
            Analytics.logRealtimeSnapshot(fps, kbps, uptimeSec)
            metricsPublisher.publishRealtimeMetrics(fps, kbps, uptimeSec, audioCaptureFactory.qualityName)
            mainHandler.postDelayed(this, METRICS_INTERVAL_MS)
        }
    }
}
