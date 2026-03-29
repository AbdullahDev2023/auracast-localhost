package com.akdevelopers.auracast.analytics
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.akdevelopers.auracast.service.StreamStatus

/**
 * Central Crashlytics wrapper for AuraCast.
 *
 * Usage:
 *  - Call [setStreamContext] whenever streaming state changes so every crash
 *    report carries up-to-date key/value context.
 *  - Call [recordNonFatal] for handled errors (WebSocket failures, audio errors)
 *    that should appear in the Crashlytics dashboard without crashing the app.
 *  - Call [log] for breadcrumb-style messages visible in the crash report timeline.
 */
object CrashManager {

    private const val TAG = "CrashManager"

    private val crashlytics: FirebaseCrashlytics by lazy {
        FirebaseCrashlytics.getInstance()
    }

    // ── Custom keys ───────────────────────────────────────────────────────────

    /** Update all stream-related keys in one call — do this on every status change. */
    fun setStreamContext(
        status: StreamStatus,
        serverUrl: String = "",
        quality: String = ""
    ) {
        runCatching {
            crashlytics.setCustomKey("stream_status", status.name)
            if (serverUrl.isNotBlank()) {
                // Store only the host portion — avoid logging tokens/paths
                val host = runCatching {
                    serverUrl.removePrefix("wss://").removePrefix("ws://").substringBefore("/")
                }.getOrDefault(serverUrl)
                crashlytics.setCustomKey("server_host", host)
            }
            if (quality.isNotBlank()) crashlytics.setCustomKey("quality_preset", quality)
        }.onFailure { Log.w(TAG, "setStreamContext failed: ${it.message}") }
    }

    /** Set the WebSocket reconnect attempt counter so it shows in crash reports. */
    fun setReconnectAttempt(attempt: Int) {
        runCatching { crashlytics.setCustomKey("reconnect_attempt", attempt) }
            .onFailure { Log.w(TAG, "setReconnectAttempt failed: ${it.message}") }
    }

    /** Set the audio capture source that was active when a crash/error occurred. */
    fun setAudioSource(sourceName: String) {
        runCatching { crashlytics.setCustomKey("audio_source", sourceName) }
            .onFailure { Log.w(TAG, "setAudioSource failed: ${it.message}") }
    }

    // ── Breadcrumb logging ────────────────────────────────────────────────────

    /**
     * Append a breadcrumb message to the Crashlytics log.
     * These lines appear in the "Logs" tab of a crash report, giving a
     * timeline of what the app was doing before the crash.
     */
    fun log(message: String) {
        runCatching { crashlytics.log(message) }
            .onFailure { Log.w(TAG, "log failed: ${it.message}") }
    }

    // ── Non-fatal error recording ─────────────────────────────────────────────

    /**
     * Record a handled exception as a non-fatal issue.
     * The error surfaces in the Crashlytics "Non-fatals" section without
     * crashing the app, so you can track error rates over time.
     */
    fun recordNonFatal(throwable: Throwable, context: String = "") {
        runCatching {
            if (context.isNotBlank()) crashlytics.log("Non-fatal context: $context")
            crashlytics.recordException(throwable)
        }.onFailure { Log.w(TAG, "recordNonFatal failed: ${it.message}") }
    }

    /**
     * Record a non-fatal error described only by a message string.
     * Wraps it in a [RuntimeException] so Crashlytics has a stack trace.
     */
    fun recordNonFatal(message: String, context: String = "") {
        recordNonFatal(RuntimeException(message), context)
    }
}
