package com.akdevelopers.auracast.core

/**
 * AppConstants — single source of truth for every app-wide constant.
 *
 * ── SharedPreferences ────────────────────────────────────────────────────────
 * All prefs live in one file ([PREFS_FILE]).  Adding a new key here keeps
 * every read/write consistent and makes typo-bugs impossible to compile.
 *
 * ── Network ──────────────────────────────────────────────────────────────────
 * Firebase and default-URL constants are here so no class needs to own them.
 * Override [DEFAULT_SERVER_URL] at runtime via [PREF_SERVER_URL].
 *
 * ── How to add a new preference ──────────────────────────────────────────────
 * 1. Add `const val PREF_YOUR_KEY = "your_key"` below.
 * 2. Use it everywhere: `prefs.getString(AppConstants.PREF_YOUR_KEY, null)`.
 */
object AppConstants {

    // ── SharedPreferences file name ───────────────────────────────────────────
    const val PREFS_FILE = "auracast"

    // ── SharedPreferences keys ────────────────────────────────────────────────
    const val PREF_STREAM_ID            = "stream_id"
    const val PREF_DISPLAY_NAME         = "display_name"
    const val PREF_SERVER_URL           = "server_url"
    const val PREF_AUTO_RESTART         = "auto_restart"
    const val PREF_FIREBASE_LAST_TS     = "firebase_last_ts"
    const val PREF_LAST_COMMAND_ID      = "last_command_id"
    const val PREF_AUTOSTART_PROMPTED   = "autostart_prompted"
    const val PREF_SERVICE_START_EPOCH  = "service_start_epoch"
    const val PREF_APP_CMD_LAST_TS      = "app_cmd_last_ts"

    // ── Firebase ──────────────────────────────────────────────────────────────
    const val FIREBASE_DB_URL =
        "https://auracast-df815-default-rtdb.asia-southeast1.firebasedatabase.app"

    /** Path prefix for per-device data in the Realtime Database. */
    const val FIREBASE_PATH_USERS         = "users"
    const val FIREBASE_PATH_CONTROL       = "control"
    const val FIREBASE_PATH_STATUS        = "status"
    const val FIREBASE_PATH_REALTIME      = "realtime"
    const val FIREBASE_PATH_STREAMS       = "streams"
    const val FIREBASE_PATH_AURACAST_CFG  = "auracast_config"
    const val FIREBASE_PATH_SERVER_URL    = "serverUrl"

    /** Milliseconds before a Firebase one-shot fetch is considered timed out. */
    const val FIREBASE_FETCH_TIMEOUT_MS   = 5_000L

    // ── Networking defaults ───────────────────────────────────────────────────
    const val DEFAULT_SERVER_URL =
        "wss://nonmanifestly-smudgeless-lamonica.ngrok-free.dev/stream"

    // ── Command dedup history ─────────────────────────────────────────────────
    /** Maximum number of commandIds kept in the in-memory dedup ring. */
    const val COMMAND_DEDUP_MAX_HISTORY = 50

    // ── Watchdog timing ───────────────────────────────────────────────────────
    const val WATCHDOG_ALARM_INTERVAL_MS  = 15L * 60 * 1_000   // 15 minutes
    const val WATCHDOG_COLD_START_GRACE_MS = 30_000L            // skip check within 30 s of start

    // ── Metrics ───────────────────────────────────────────────────────────────
    const val METRICS_INTERVAL_MS = 60_000L
}
