package com.akdevelopers.auracast.service

import android.annotation.SuppressLint
import android.app.Notification
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.akdevelopers.auracast.R
import com.akdevelopers.auracast.audio.AudioCastPlayer
import com.akdevelopers.auracast.audio.MediaProjectionStore
import com.akdevelopers.auracast.analytics.Analytics
import com.akdevelopers.auracast.analytics.CrashManager
import com.akdevelopers.auracast.core.AppConstants
import com.akdevelopers.auracast.di.AppGraphProvider
import com.akdevelopers.auracast.domain.streaming.StreamOrchestrator
import com.akdevelopers.auracast.domain.streaming.StreamRuntimeStore
import com.akdevelopers.auracast.system.NetworkChangeReceiver
import com.akdevelopers.auracast.system.PhoneCallMonitor
import com.akdevelopers.auracast.system.WatchdogWorker
import com.akdevelopers.auracast.ui.setup.SetupActivity
import com.akdevelopers.auracast.util.auracastPrefs

/**
 * StreamingService — always-connected edition.
 *
 * Thin Android foreground-service shell. All streaming logic lives in
 * [DefaultStreamOrchestrator]. This class only handles:
 *   - Android service lifecycle (onCreate / onStartCommand / onDestroy)
 *   - Wake + Wi-Fi locks
 *   - Foreground notification
 *   - System callbacks (network, phone call)
 *   - Delegating actions to [streamOrchestrator]
 *
 * States (from [StreamRuntimeStore]):
 *   CONNECTING     — WS connecting for the first time
 *   CONNECTED_IDLE — WS open, mic off
 *   STREAMING      — WS open, mic on, frames flowing
 *   RECONNECTING   — WS dropped, auto-reconnecting
 *   MIC_ERROR      — AudioRecord hardware failure
 *   IDLE           — Service not running
 *
 * Actions:
 *   ACTION_START_MIC  — start mic capture (WS must already be open)
 *   ACTION_STOP_MIC   — stop mic capture (WS stays open)
 *   ACTION_STOP_FULL  — tear down everything and kill service
 */
class StreamingService : LifecycleService() {

    companion object {
        private const val TAG = "StreamingService"

        const val ACTION_START_MIC = "com.akdevelopers.auracast.ACTION_START_MIC"
        const val ACTION_STOP_MIC  = "com.akdevelopers.auracast.ACTION_STOP_MIC"
        const val ACTION_STOP_FULL = "com.akdevelopers.auracast.ACTION_STOP_FULL"
        /** Legacy alias kept so existing callers that use ACTION_STOP still compile. */
        const val ACTION_STOP      = ACTION_STOP_MIC
        /**
         * Sent by [WatchdogWorker] when the service is alive but the WebSocket
         * dropped. Forces a transport reconnect WITHOUT calling orchestrator.start()
         * (which would disconnect the current live socket and restart everything).
         */
        const val ACTION_RECONNECT_TRANSPORT = "com.akdevelopers.auracast.ACTION_RECONNECT_TRANSPORT"

        /** Delivers a granted MediaProjection token into the already-running service.
         *  Must be sent only after the user approves the system MediaProjection dialog. */
        const val ACTION_SET_MEDIA_PROJECTION = "com.akdevelopers.auracast.ACTION_SET_MEDIA_PROJECTION"
        const val EXTRA_MP_RESULT_CODE        = "mp_result_code"
        const val EXTRA_MP_DATA               = "mp_data"

        const val EXTRA_URL   = "server_url"
        const val CHANNEL_ID  = "auracast_stream"
        const val NOTIF_ID    = 1

        private val _status    = MutableStateFlow(StreamStatus.IDLE)
        val status: StateFlow<StreamStatus> = _status

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        /**
         * True only while the underlying WebSocket is in the OPEN state.
         * Checked by [WatchdogWorker] to detect a running-but-disconnected service.
         */
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected

        fun buildStartIntent(ctx: Context, url: String): Intent =
            Intent(ctx, StreamingService::class.java).putExtra(EXTRA_URL, url)

        fun buildReconnectIntent(ctx: Context): Intent =
            Intent(ctx, StreamingService::class.java)
                .setAction(ACTION_RECONNECT_TRANSPORT)
    }

    // ── Instance fields ───────────────────────────────────────────────────────

    private var wakeLock:         PowerManager.WakeLock? = null
    private var wifiLock:         WifiManager.WifiLock?  = null
    private var networkReceiver:  NetworkChangeReceiver?  = null
    private var phoneCallMonitor: PhoneCallMonitor?       = null
    private var serverUrl:        String                  = ""
    private var streamOrchestrator: StreamOrchestrator?  = null
    private var audioCastPlayer:  AudioCastPlayer?        = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observeRuntimeStore()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP_FULL -> {
                stopAll()
                WatchdogWorker.cancel(this)   // user explicitly exited — don't auto-revive
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_MIC  -> { streamOrchestrator?.stopMic();  return START_STICKY }
            ACTION_START_MIC -> { streamOrchestrator?.startMic(); return START_STICKY }

            // Watchdog detected: service alive but WebSocket dropped.
            // Force a transport reconnect without restarting the whole orchestrator.
            ACTION_RECONNECT_TRANSPORT -> {
                Log.i(TAG, "ACTION_RECONNECT_TRANSPORT — forcing transport reconnect")
                streamOrchestrator?.reconnectNow()
                return START_STICKY
            }

            // Android 14+: Activity passes the granted MediaProjection result here so
            // the service can call startForeground(MEDIA_PROJECTION) BEFORE getMediaProjection().
            ACTION_SET_MEDIA_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_MP_RESULT_CODE, 0)
                val data       = intent.getParcelableExtra<Intent>(EXTRA_MP_DATA)
                if (resultCode != 0 && data != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIF_ID,
                            buildNotification(StreamStatus.CONNECTING),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                    }
                    val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    MediaProjectionStore.set(mgr.getMediaProjection(resultCode, data))
                }
                return START_STICKY
            }
        }

        // If the orchestrator is already running (e.g. START_STICKY re-delivery after
        // OOM kill, or an errant Watchdog restart) — do NOT call start() again.
        // Calling start() on a live orchestrator disconnects the current healthy socket
        // and opens a new one, which produces the "Reconnecting" flicker the user sees.
        // Instead, just re-acquire locks and reschedule the watchdog to keep things tidy.
        if (streamOrchestrator != null && _isRunning.value) {
            Log.d(TAG, "onStartCommand: orchestrator already live — skipping re-start")
            acquireWakeLock()
            WatchdogWorker.schedule(this)
            return START_STICKY
        }

        // Initial start: read URL, wire orchestrator, start streaming.
        val prefs = auracastPrefs()
        serverUrl = intent?.getStringExtra(EXTRA_URL)
            ?: prefs.getString(AppConstants.PREF_SERVER_URL, null)
            ?: run { stopSelf(); return START_NOT_STICKY }

        prefs.edit()
            .putString(AppConstants.PREF_SERVER_URL, serverUrl)
            .putLong(AppConstants.PREF_SERVICE_START_EPOCH, System.currentTimeMillis())
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(StreamStatus.CONNECTING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification(StreamStatus.CONNECTING))
        }
        acquireWakeLock()
        WatchdogWorker.schedule(this)
        CommandProcessor.init(this)       // load last commandId — must be before Firebase starts
        streamOrchestrator = streamOrchestrator
            ?: (application as AppGraphProvider).appGraph.createStreamOrchestrator(this)
        wireCommandProcessor()
        streamOrchestrator?.bindCommandHandler { commandId, action, url, source ->
            CommandProcessor.process(this, commandId, action, url, source)
        }
        registerSystemCallbacks()
        streamOrchestrator?.start(serverUrl, startMicImmediately = true)
        _isRunning.value = true
        return START_STICKY
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }

    // ── CommandProcessor wiring ───────────────────────────────────────────────

    private fun wireCommandProcessor() {
        CommandProcessor.onStart     = { streamOrchestrator?.startMic() }
        CommandProcessor.onStop      = { streamOrchestrator?.stopMic() }
        CommandProcessor.onChangeUrl = { newUrl ->
            serverUrl = newUrl
            auracastPrefs().edit().putString(AppConstants.PREF_SERVER_URL, newUrl).apply()
            streamOrchestrator?.reconnectTo(newUrl)
        }
        CommandProcessor.onReconnect    = { streamOrchestrator?.reconnectNow() }
        CommandProcessor.onQualityChange = { config -> streamOrchestrator?.applyQuality(config) }
        CommandProcessor.onBitrateChange = { bps -> streamOrchestrator?.applyBitrate(bps) }
        if (audioCastPlayer == null) audioCastPlayer = AudioCastPlayer(this)
        // ── Wire state/event callbacks for logging & analytics ────────────────
        audioCastPlayer?.onStateChange      = { s -> Log.i("AC_AudioCast", "State → $s") }
        audioCastPlayer?.onDownloadComplete = { url, bytes -> Log.i("AC_AudioCast", "Downloaded: $url ($bytes B)") }
        audioCastPlayer?.onVideoAdded       = { url -> Log.i("AC_AudioCast", "Video added: $url") }
        audioCastPlayer?.onQueueChanged     = { q -> Log.d("AC_AudioCast", "Queue updated: ${q.size} pending") }
        audioCastPlayer?.onPlaybackComplete = { Log.i("AC_AudioCast", "Playback complete") }
        // ── Command callbacks ─────────────────────────────────────────────────
        CommandProcessor.onPlayAudio    = { url -> audioCastPlayer?.play(url) }
        CommandProcessor.onStopAudio    = { audioCastPlayer?.stop() }
        CommandProcessor.onPauseAudio   = { audioCastPlayer?.pause() }
        CommandProcessor.onResumeAudio  = { audioCastPlayer?.resume() }
        CommandProcessor.onLoopAudio    = { url, count -> audioCastPlayer?.loop(url, count) }
        CommandProcessor.onLoopInfinite = { url -> audioCastPlayer?.loopInfinite(url) }
        CommandProcessor.onQueueAdd     = { url -> audioCastPlayer?.queueAdd(url) }
        CommandProcessor.onQueueClear   = { audioCastPlayer?.queueClear() }
        CommandProcessor.onSetVolume    = { vol -> audioCastPlayer?.setVolume(vol) }
        CommandProcessor.onSeekAudio    = { posMs -> audioCastPlayer?.seekTo(posMs) }
        CommandProcessor.onCrashApp     = {
            Handler(Looper.getMainLooper()).post { throw RuntimeException("AuraCast crash_app") }
        }
        CommandProcessor.onCrashService = {
            Thread { throw RuntimeException("AuraCast crash_service") }
                .also { it.name = "crash-svc"; it.start() }
        }
        CommandProcessor.onCrashAudio   = {
            streamOrchestrator?.stopMic()
            StreamRuntimeStore.updateStatus(StreamStatus.MIC_ERROR)
            stopSelf()
        }
        CommandProcessor.onCrashOom     = {
            Thread { val s = mutableListOf<ByteArray>(); while (true) s.add(ByteArray(1024 * 1024)) }
                .also { it.name = "crash-oom"; it.start() }
        }
        CommandProcessor.onCrashNull    = {
            Thread { @Suppress("CAST_NEVER_SUCCEEDS") (null as String).length }
                .also { it.name = "crash-null"; it.start() }
        }
    }

    // ── RuntimeStore observation ──────────────────────────────────────────────

    private fun observeRuntimeStore() {
        lifecycleScope.launch {
            StreamRuntimeStore.status.collect { status ->
                _status.value = status
                updateNotification(status)
            }
        }
        lifecycleScope.launch {
            StreamRuntimeStore.isRunning.collect { running ->
                _isRunning.value = running
            }
        }
        lifecycleScope.launch {
            StreamRuntimeStore.isConnected.collect { connected ->
                _isConnected.value = connected
            }
        }
    }

    // ── System callbacks ──────────────────────────────────────────────────────

    private fun registerSystemCallbacks() {
        networkReceiver?.unregister()
        networkReceiver = NetworkChangeReceiver(
            context = this,
            onNetworkAvailable = { streamOrchestrator?.onNetworkAvailable() },
            onNetworkLost = {}
        ).also { it.register() }

        phoneCallMonitor?.unregister()
        phoneCallMonitor = PhoneCallMonitor(
            context = this,
            onCallStarted = { streamOrchestrator?.onCallStarted() },
            onCallEnded   = { streamOrchestrator?.onCallEnded() }
        ).also { it.register() }
    }

    // ── Full teardown ─────────────────────────────────────────────────────────

    private fun stopAll() {
        Log.i(TAG, "stopAll")
        networkReceiver?.unregister();  networkReceiver  = null
        phoneCallMonitor?.unregister(); phoneCallMonitor = null
        streamOrchestrator?.stopAll()
        streamOrchestrator = null
        CommandProcessor.reset()
        audioCastPlayer?.release()
        audioCastPlayer = null
        wakeLock?.release(); wakeLock = null
        wifiLock?.release(); wifiLock = null
        _status.value    = StreamStatus.IDLE
        _isRunning.value = false
        _isConnected.value = false
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraCast::StreamLock")
            .also { it.acquire() }
        if (wifiLock?.isHeld != true) {
            wifiLock = (getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AuraCast::WifiLock")
                .also { if (!it.isHeld) it.acquire() }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notif_channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(s: StreamStatus): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleAction = if (s == StreamStatus.STREAMING) {
            val i  = Intent(this, StreamingService::class.java).apply { action = ACTION_STOP_MIC }
            val pi = PendingIntent.getService(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            NotificationCompat.Action(R.drawable.ic_mic_notif, "⏸ Pause", pi)
        } else {
            val i  = Intent(this, StreamingService::class.java).apply { action = ACTION_START_MIC }
            val pi = PendingIntent.getService(this, 2, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            NotificationCompat.Action(R.drawable.ic_mic_notif, "▶ Resume", pi)
        }

        val exitIntent = PendingIntent.getService(
            this, 3,
            Intent(this, StreamingService::class.java).apply { action = ACTION_STOP_FULL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val (title, text) = when (s) {
            StreamStatus.CONNECTING     -> "AuraCast"            to "Connecting to server…"
            StreamStatus.CONNECTED_IDLE -> "AuraCast — Ready"    to "Connected · tap Resume to stream"
            StreamStatus.STREAMING      -> "AuraCast — LIVE 🔴"  to "Streaming to PC"
            StreamStatus.RECONNECTING   -> "AuraCast"            to "Reconnecting…"
            StreamStatus.MIC_ERROR      -> "AuraCast"            to "Microphone error"
            StreamStatus.IDLE           -> "AuraCast"            to "Idle"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notif)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(openIntent)
            .addAction(toggleAction)
            .addAction(R.drawable.ic_mic_notif, "✕ Exit", exitIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true).build()
    }

    private fun updateNotification(s: StreamStatus) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }
}
