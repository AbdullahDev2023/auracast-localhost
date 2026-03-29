package com.akdevelopers.auracast.domain.streaming

import android.content.Context
import com.akdevelopers.auracast.audio.AudioQualityConfig
import com.akdevelopers.auracast.service.StreamStatus
import kotlinx.coroutines.flow.StateFlow

interface AudioTransport {
    var onStatusChange: ((StreamStatus) -> Unit)?

    val isOpen: Boolean

    fun connect(serverUrl: String, context: Context)
    fun disconnect()
    fun reconnectNow()
    fun reconnectTo(serverUrl: String, context: Context)
    fun sendFrame(data: ByteArray)
    fun sendStreamingState(active: Boolean)
    /** Notify server of current audio source mode ("mic" | "call"). */
    fun sendAudioMode(mode: String)
    /**
     * Re-announce the active Opus codec parameters. Called after a bitrate
     * hot-swap so the server and browser listeners update their expectations.
     */
    fun sendCodecConfig(sampleRate: Int, frameMs: Int, bitrate: Int)

    /**
     * Returns true if this transport sends audio frames over UDP rather than
     * WebSocket. The default is false so existing implementations require no change.
     * Used by telemetry / stats to report the active transport type.
     */
    fun supportsUdp(): Boolean = false
}

interface RemoteCommandSource {
    var onCommandReceived: ((commandId: String, action: String, url: String, source: String) -> Unit)?
    var onStatusChange: ((StreamStatus) -> Unit)?

    val isOpen: Boolean

    fun start(serverUrl: String, context: Context)
    fun stop()
    fun reconnectNow()
    fun reconnectTo(serverUrl: String, context: Context)
}

interface StatusPublisher {
    fun publishStatus(status: StreamStatus, serverUrl: String)
}

interface MetricsPublisher {
    fun publishRealtimeMetrics(framesPerSec: Float, kbps: Float, uptimeSec: Int, quality: String)
}

interface AudioCaptureController {
    fun start()
    fun stop()
    fun release()
    /** Hot-swap the Opus encoding bitrate; no-op if the encoder is not active. */
    fun setBitrate(bps: Int) {}
}

interface AudioCaptureFactory {
    val qualityName: String

    fun sampleRate(): Int
    fun frameMs(): Int
    fun create(
        onFrameReady: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ): AudioCaptureController
}

interface StreamOrchestrator {
    val status: StateFlow<StreamStatus>
    val isConnected: StateFlow<Boolean>
    val isRunning: StateFlow<Boolean>

    fun bindCommandHandler(
        handler: (commandId: String, action: String, url: String, source: String) -> Unit
    )

    fun start(serverUrl: String, startMicImmediately: Boolean = true)
    fun startMic()
    fun stopMic()
    fun reconnectNow()
    fun reconnectTo(serverUrl: String)
    fun onNetworkAvailable()
    fun onCallStarted()
    fun onCallEnded()
    /** Apply a new quality config immediately; restarts capture if mic is active. */
    fun applyQuality(config: AudioQualityConfig)
    /**
     * Hot-swap just the Opus encoding bitrate. Capture stays at 48 kHz (fixed) —
     * no mic restart, no AudioRecord tear-down. Allowed values: 16000, 32000, 64000, 128000 bps.
     */
    fun applyBitrate(bps: Int)
    fun stopAll()
}
