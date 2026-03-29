package com.akdevelopers.auracast.analytics
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase

/**
 * Central Firebase Analytics wrapper for AuraCast.
 *
 * Every meaningful user action, system event, and streaming lifecycle moment
 * fires a named event so the Firebase DebugView and Analytics dashboards show
 * a complete picture of app behaviour in real-time.
 *
 * ── DebugView setup (choose ONE) ─────────────────────────────────────────────
 *
 * Option A — Android Studio Run Config (recommended for physical devices):
 *   Run → Edit Configurations → Miscellaneous → Launch Options → Add
 *   Extra: --ei "firebase_analytics.debug" 1
 *   (Restart app; events appear in Firebase Console → Analytics → DebugView)
 *
 * Option B — ADB (works on any device):
 *   adb shell setprop debug.firebase.analytics.app com.akdevelopers.auracast
 *   Disable: adb shell setprop debug.firebase.analytics.app .none.
 *
 * Option C — Programmatic (emulators / rooted devices only — handled in AuraCastApp):
 *   SystemProperties.set("debug.firebase.analytics.app", packageName)
 *
 * ── Events Config reference ───────────────────────────────────────────────────
 * After events appear in the Firebase Console (Analytics → Events), you can:
 *   • Mark ac_stream_start / ac_stream_connected as Conversions
 *   • Build Audiences: e.g. "Active streamers" = users who fired ac_stream_start in last 7 d
 *   • Create Funnels: ac_app_open → ac_permission_granted → ac_stream_start → ac_ws_connected
 */
object Analytics {

    private const val TAG = "AC_Analytics"

    private val fa: FirebaseAnalytics by lazy {
        Firebase.analytics.also {
            Log.d(TAG, "FirebaseAnalytics initialised ✓")
        }
    }

    // ── Internal helper ───────────────────────────────────────────────────────

    private fun log(event: String, vararg pairs: Pair<String, Any?>) {
        runCatching {
            val bundle = Bundle()
            for ((k, v) in pairs) {
                when (v) {
                    is String  -> bundle.putString(k, v)
                    is Int     -> bundle.putInt(k, v)
                    is Long    -> bundle.putLong(k, v)
                    is Float   -> bundle.putFloat(k, v)
                    is Boolean -> bundle.putBoolean(k, v)
                    null       -> {}  // skip null params
                }
            }
            fa.logEvent(event, bundle.takeIf { !it.isEmpty })
            Log.d(TAG, "▶ event=$event  params=${pairs.toList()}")
        }.onFailure { Log.w(TAG, "logEvent($event) failed: ${it.message}") }
    }

    // ── Init / Events Config ──────────────────────────────────────────────────

    /**
     * One-time initialisation — call from AuraCastApp.onCreate().
     *
     * Sets user properties that are attached to EVERY subsequent event automatically,
     * giving you device-level segmentation in the Analytics audience builder:
     *   ac_android_api    → API level (e.g. "34")
     *   ac_device_brand   → e.g. "samsung"
     *   ac_app_version    → versionName from manifest
     */
    fun initUserProperties(context: Context) {
        runCatching {
            fa.setAnalyticsCollectionEnabled(true)
            fa.setUserProperty("ac_android_api",   Build.VERSION.SDK_INT.toString())
            fa.setUserProperty("ac_device_brand",  Build.BRAND.take(36))
            fa.setUserProperty("ac_app_version",   getAppVersion(context))
            Log.d(TAG, "User properties initialised ✓")
        }.onFailure { Log.w(TAG, "initUserProperties failed: ${it.message}") }
    }

    /** Updates the user property that segments events by audio quality preset. */
    fun setQualityUserProperty(quality: String) {
        runCatching { fa.setUserProperty("ac_quality_preset", quality) }
            .onFailure { Log.w(TAG, "setQualityUserProperty failed") }
    }

    /**
     * Flips a user property so Firebase Audiences can distinguish
     * "currently streaming" vs idle users in real-time.
     */
    fun setStreamingActiveUserProperty(active: Boolean) {
        runCatching {
            fa.setUserProperty("ac_streaming_active", if (active) "true" else "false")
        }.onFailure { Log.w(TAG, "setStreamingActiveUserProperty failed") }
    }

    /**
     * Enables Firebase Analytics DebugView programmatically.
     *
     * Works automatically on:
     *   • Android Emulator (any API level)
     *   • Rooted physical devices
     *
    * On non-rooted physical devices the SystemProperties.set() call throws
    * SecurityException (caught silently). Use Option A or B from the header comment.
    *
    * Call this only for BuildConfig.DEBUG builds.
    */
    @SuppressLint("PrivateApi")
    fun enableDebugMode(context: Context) {
        try {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("set", String::class.java, String::class.java)
                .invoke(null, "debug.firebase.analytics.app", context.packageName)
            Log.i(TAG, "Firebase DebugView enabled via SystemProperties ✓")
        } catch (e: SecurityException) {
            Log.d(TAG, "DebugView reflection blocked (non-rooted) — use adb or Run Config option")
        } catch (e: Exception) {
            Log.d(TAG, "DebugView reflection unavailable: ${e.javaClass.simpleName}")
        }
        // Ensure collection is always enabled in debug
        runCatching { fa.setAnalyticsCollectionEnabled(true) }
    }

    private fun getAppVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    }.getOrDefault("1.0")

    // ── Activity / Permission events ──────────────────────────────────────────

    /** Fired in MainActivity.onCreate — tracks every app launch. */
    fun logAppOpen(alreadyRunning: Boolean) =
        log("ac_app_open", "already_running" to alreadyRunning)

    /** RECORD_AUDIO + READ_PHONE_STATE both granted. */
    fun logPermissionGranted() = log("ac_permission_granted")

    /** One or more required permissions were denied. */
    fun logPermissionDenied() = log("ac_permission_denied")

    /** Battery optimisation exemption dialog was shown. */
    fun logBatteryPrompt() = log("ac_battery_prompt")

    // ── Stream lifecycle events ────────────────────────────────────────────────

    /**
     * Fired when the foreground service is about to start.
     * @param quality  e.g. "HIGH_QUALITY"
     * @param urlHost  e.g. "abc.ngrok-free.app"
     */
    fun logStreamStart(quality: String, urlHost: String) =
        log("ac_stream_start", "quality" to quality, "url_host" to urlHost)

    /** Fired when the user (or system) explicitly stops streaming. */
    fun logStreamStop() = log("ac_stream_stop")

    /**
     * Fired on every WebSocket status change inside the service.
     * @param status  e.g. "CONNECTED", "RECONNECTING"
     */
    fun logStreamStatus(status: String, quality: String) =
        log("ac_stream_status", "status" to status, "quality" to quality)

    /**
     * Fired the moment the WebSocket transitions to CONNECTED.
     * @param latencyMs  milliseconds from connect() call to first onOpen()
     * @param quality    preset name
     */
    fun logStreamConnected(latencyMs: Long, quality: String) =
        log("ac_stream_connected", "latency_ms" to latencyMs, "quality" to quality)

    /**
     * Fired when streaming stops. Records the total foreground-service lifetime.
     * @param durationMs  total ms the service was alive (start → stop)
     * @param quality     preset in use during the session
     */
    fun logSessionDuration(durationMs: Long, quality: String) =
        log("ac_session_duration", "duration_ms" to durationMs, "quality" to quality)

    // ── Periodic realtime health snapshot ─────────────────────────────────────

    /**
     * Fired every ~60 s while streaming is active.
     * Lets you plot stream health over time in the Analytics Events funnel.
     *
     * @param framesPerSec  measured audio frames delivered in the last window (≈ 50 at 20 ms/frame)
     * @param kbps          measured kilobits/sec sent to server
     * @param uptimeSec     total seconds since streaming started
     */
    fun logRealtimeSnapshot(framesPerSec: Float, kbps: Float, uptimeSec: Int) =
        log(
            "ac_realtime_snapshot",
            "frames_per_sec" to framesPerSec,
            "kbps"           to kbps,
            "uptime_sec"     to uptimeSec
        )

    // ── Settings / config events ───────────────────────────────────────────────

    /**
     * Generic settings change event.
     * @param key    e.g. "server_url", "quality_preset", "auto_restart"
     * @param value  new value (truncated to 36 chars for Firebase param limit)
     */
    fun logSettingsChanged(key: String, value: String) =
        log("ac_settings_changed", "key" to key, "value" to value.take(36))

    /** Fired when the user selects a quality preset. */
    fun logAudioQualitySet(quality: String) =
        log("ac_quality_set", "quality" to quality)

    /** Fired when the user saves a new server URL. */
    fun logUrlSet(urlHost: String) =
        log("ac_url_set", "url_host" to urlHost)

    // ── WebSocket events ───────────────────────────────────────────────────────

    fun logWsConnected(attempt: Int, sampleRate: Int) =
        log("ac_ws_connected", "attempt" to attempt, "sample_rate" to sampleRate)

    fun logWsFailure(attempt: Int, errorType: String) =
        log("ac_ws_failure", "attempt" to attempt, "error_type" to errorType)

    fun logWsReconnectScheduled(attempt: Int, delayMs: Long) =
        log("ac_ws_reconnect", "attempt" to attempt, "delay_ms" to delayMs)

    // ── Microphone / AudioRecord events ───────────────────────────────────────

    fun logMicError(message: String, quality: String) =
        log("ac_mic_error", "message" to message.take(100), "quality" to quality)

    fun logMicRestartScheduled(attempt: Int, delayMs: Long) =
        log("ac_mic_restart", "attempt" to attempt, "delay_ms" to delayMs)

    /** Fired when the AudioCaptureEngine starts successfully after a restart. */
    fun logMicRecovered(attempt: Int) =
        log("ac_mic_recovered", "attempt" to attempt)

    // ── Phone call events ─────────────────────────────────────────────────────

    /** Mic paused — incoming or outgoing call detected. */
    fun logPhoneCallStarted() = log("ac_call_started")

    /** Mic resumed — call ended. */
    fun logPhoneCallEnded() = log("ac_call_ended")

    // ── Network events ────────────────────────────────────────────────────────

    fun logNetworkAvailable() = log("ac_network_available")
    fun logNetworkLost()      = log("ac_network_lost")

    // ── Boot / auto-restart event ─────────────────────────────────────────────

    /** Fired when BootReceiver restarts the service after reboot or update. */
    fun logBootRestart() = log("ac_boot_restart")

    // ── Firebase remote events ────────────────────────────────────────────────

    /**
     * Fired when the server URL is resolved.
     * @param source "firebase" | "prefs" | "none"
     */
    fun logFirebaseUrlFetched(source: String) =
        log("ac_firebase_url", "source" to source)

    /**
     * Fired when a remote command is received from Firebase RTDB.
     * @param command e.g. "stop", "reconnect", "change_url", "crash_app"
     */
    fun logFirebaseCommand(command: String) =
        log("ac_firebase_cmd", "command" to command)

    /**
     * Fired by CommandProcessor every time a command is actually executed.
     * @param action  e.g. "start", "stop", "change_url"
     * @param source  "websocket" | "firebase"
     */
    fun logCommandReceived(action: String, source: String) =
        log("ac_command_received", "action" to action, "source" to source)
}
