package com.akdevelopers.auracast.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.akdevelopers.auracast.analytics.Analytics
import com.akdevelopers.auracast.audio.AudioQualityConfig
import com.akdevelopers.auracast.core.AppConstants
import org.json.JSONObject

/**
 * CommandProcessor — single deduplicating entry point for all commands.
 *
 * Both [com.akdevelopers.auracast.audio.AudioControlClient] (WebSocket /control)
 * and [com.akdevelopers.auracast.remote.FirebaseRemoteController] (RTDB) route
 * commands here. Dedup by commandId prevents double-execution even when both
 * channels deliver the same command simultaneously.
 *
 * Dedup layers:
 *   1. In-memory LinkedHashSet — last [AppConstants.COMMAND_DEDUP_MAX_HISTORY] ids
 *   2. SharedPrefs persistence — survives service restarts / OOM kills
 *   3. Firebase ts-guard stays in FirebaseRemoteController as a secondary check
 *
 * ── Supported actions ────────────────────────────────────────────────────────
 * "start" | "stop" | "change_url" | "reconnect" |
 * "play_audio" | "stop_audio" | "pause_audio" | "resume_audio" |
 * "loop_audio" | "loop_infinite" | "queue_add" | "queue_clear" |
 * "set_volume" | "seek_audio" |
 * "crash_app" | "crash_service" | "crash_audio" | "crash_oom" | "crash_null"
 */
object CommandProcessor {

    private const val TAG = "AC_CmdProcessor"

    private val mainHandler = Handler(Looper.getMainLooper())

    // In-memory dedup ring — evicts oldest entry when full
    private val executedIds = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= AppConstants.COMMAND_DEDUP_MAX_HISTORY) remove(iterator().next())
            return super.add(element)
        }
    }

    // ── Callbacks — wired by StreamingService.wireCommandProcessor() ──────────
    var onStart:        (() -> Unit)?                      = null
    var onStop:         (() -> Unit)?                      = null
    var onChangeUrl:    ((String) -> Unit)?                = null
    var onReconnect:    (() -> Unit)?                      = null
    var onQualityChange:((AudioQualityConfig) -> Unit)?    = null
    /**
     * Fired when the server pushes a `set_bitrate` command.
     * The integer is one of [16000, 32000, 64000, 128000] bps.
     * Capture stays at HD 48 kHz — only the encoder bitrate changes.
     */
    var onBitrateChange:((Int) -> Unit)?                   = null
    /** Fired when the server pushes a `play_audio` command. The string is the full download URL. */
    var onPlayAudio:     ((url: String) -> Unit)?                = null
    /** Fired when the server pushes a `stop_audio` command. */
    var onStopAudio:     (() -> Unit)?                           = null
    /** Fired when the server pushes a `pause_audio` command. */
    var onPauseAudio:    (() -> Unit)?                           = null
    /** Fired when the server pushes a `resume_audio` command. */
    var onResumeAudio:   (() -> Unit)?                           = null
    /**
     * Fired when the server pushes a `loop_audio` command.
     * url field carries JSON: {"url":"<downloadUrl>","count":<n>}
     */
    var onLoopAudio:     ((url: String, count: Int) -> Unit)?    = null
    /** Fired when the server pushes a `loop_infinite` command. */
    var onLoopInfinite:  ((url: String) -> Unit)?                = null
    /** Fired when the server pushes a `queue_add` command. */
    var onQueueAdd:      ((url: String) -> Unit)?                = null
    /** Fired when the server pushes a `queue_clear` command. */
    var onQueueClear:    (() -> Unit)?                           = null
    /**
     * Fired when the server pushes a `set_volume` command.
     * url field carries the float value as a plain string, e.g. "0.75".
     */
    var onSetVolume:     ((Float) -> Unit)?                      = null
    /**
     * Fired when the server pushes a `seek_audio` command.
     * url field carries the target position in milliseconds as a plain integer string, e.g. "15000".
     */
    var onSeekAudio:     ((positionMs: Int) -> Unit)?            = null
    var onCrashApp:     (() -> Unit)?                      = null
    var onCrashService: (() -> Unit)?                      = null
    var onCrashAudio:   (() -> Unit)?                      = null
    var onCrashOom:     (() -> Unit)?                      = null
    var onCrashNull:    (() -> Unit)?                      = null

    /**
     * Load the persisted last commandId from SharedPrefs.
     * Must be called once from [com.akdevelopers.auracast.service.StreamingService]
     * before Firebase / WebSocket listeners start.
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.getString(AppConstants.PREF_LAST_COMMAND_ID, null)?.let { lastId ->
            executedIds.add(lastId)
            Log.d(TAG, "Loaded last commandId: ${lastId.take(8)}")
        }
    }

    /** Clear all callbacks (call from stopAll so old lambdas don't leak). */
    fun reset() {
        onStart = null; onStop = null; onChangeUrl = null; onReconnect = null
        onQualityChange = null; onBitrateChange = null
        onPlayAudio = null; onStopAudio = null
        onPauseAudio = null; onResumeAudio = null
        onLoopAudio = null; onLoopInfinite = null
        onQueueAdd = null; onQueueClear = null; onSetVolume = null; onSeekAudio = null
        onCrashApp = null; onCrashService = null; onCrashAudio = null
        onCrashOom = null; onCrashNull = null
        // Do NOT clear executedIds — dedup history must survive across restarts.
    }

    /**
     * Process an incoming command. Silently drops duplicates.
     *
     * @param context   Used to persist commandId to SharedPrefs.
     * @param commandId UUID from server — primary dedup key.
     * @param action    "start" | "stop" | "change_url" | "reconnect" | "crash_*"
     * @param url       Non-empty only for "change_url".
     * @param source    "websocket" | "firebase" — analytics / logging only.
     */
    fun process(
        context: Context,
        commandId: String,
        action: String,
        url: String = "",
        source: String = ""
    ) {
        if (commandId in executedIds) {
            Log.d(TAG, "Duplicate id=${commandId.take(8)} action=$action source=$source — skipped")
            return
        }

        executedIds.add(commandId)
        context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putString(AppConstants.PREF_LAST_COMMAND_ID, commandId).apply()

        Log.i(TAG, "▶ action='$action' id=${commandId.take(8)} source=$source")
        Analytics.logCommandReceived(action, source)

        // All callbacks must run on the main thread.
        mainHandler.post {
            when (action) {
                "start"         -> onStart?.invoke()
                "stop"          -> onStop?.invoke()
                "change_url"    -> if (url.isNotBlank()) onChangeUrl?.invoke(url)
                                   else Log.w(TAG, "change_url with empty url — ignored")
                "reconnect"     -> onReconnect?.invoke()
                "set_quality"   -> {
                    // url field is reused as JSON payload: {"bitrate":X,"sampleRate":Y,"frameMs":Z,"complexity":W}
                    runCatching {
                        val p = JSONObject(url)
                        val cfg = AudioQualityConfig.fromServerConfig(
                            bitrate    = p.optInt("bitrate",    192_000),
                            sampleRate = 48_000,                         // always 48 kHz — not configurable
                            frameMs    = p.optInt("frameMs",         60),
                            complexity = p.optInt("complexity",      10),
                        )
                        Log.i(TAG, "Quality update: ${cfg.opusBitrate/1000}kbps 48kHz frameMs=${cfg.frameMs}")
                        onQualityChange?.invoke(cfg)
                    }.onFailure { Log.w(TAG, "set_quality parse error: $it — url='$url'") }
                }
                "set_bitrate"   -> {
                    // url field carries the bitrate value as a plain integer string, e.g. "32000"
                    // or as a JSON object {"bps":32000} for forward-compat.
                    runCatching {
                        val bps = url.trim().toIntOrNull()
                            ?: JSONObject(url).optInt("bps", 0)
                        val allowed = listOf(16_000, 32_000, 64_000, 128_000)
                        if (bps !in allowed) {
                            Log.w(TAG, "set_bitrate rejected: $bps not in $allowed")
                            return@runCatching
                        }
                        Log.i(TAG, "Bitrate hot-swap → ${bps / 1000} kbps")
                        onBitrateChange?.invoke(bps)
                    }.onFailure { Log.w(TAG, "set_bitrate parse error: $it — url='$url'") }
                }
                "crash_app"     -> onCrashApp?.invoke()
                "crash_service" -> onCrashService?.invoke()
                "crash_audio"   -> onCrashAudio?.invoke()
                "crash_oom"     -> onCrashOom?.invoke()
                "crash_null"    -> onCrashNull?.invoke()
                "play_audio"    -> if (url.isNotBlank()) onPlayAudio?.invoke(url)
                                   else Log.w(TAG, "play_audio with empty url — ignored")
                "stop_audio"    -> onStopAudio?.invoke()
                "pause_audio"   -> onPauseAudio?.invoke()
                "resume_audio"  -> onResumeAudio?.invoke()
                "loop_audio"    -> {
                    runCatching {
                        val p     = JSONObject(url)
                        val u     = p.getString("url")
                        val count = p.optInt("count", 1)
                        if (u.isBlank()) { Log.w(TAG, "loop_audio: missing url in payload"); return@runCatching }
                        Log.i(TAG, "loop_audio: $u × $count")
                        onLoopAudio?.invoke(u, count)
                    }.onFailure { Log.w(TAG, "loop_audio parse error: $it — payload='$url'") }
                }
                "loop_infinite" -> if (url.isNotBlank()) onLoopInfinite?.invoke(url)
                                   else Log.w(TAG, "loop_infinite with empty url — ignored")
                "queue_add"     -> if (url.isNotBlank()) onQueueAdd?.invoke(url)
                                   else Log.w(TAG, "queue_add with empty url — ignored")
                "queue_clear"   -> onQueueClear?.invoke()
                "set_volume"    -> {
                    runCatching {
                        val vol = url.trim().toFloat().coerceIn(0f, 1f)
                        Log.i(TAG, "set_volume → $vol")
                        onSetVolume?.invoke(vol)
                    }.onFailure { Log.w(TAG, "set_volume parse error: $it — value='$url'") }
                }
                "seek_audio"    -> {
                    runCatching {
                        val posMs = url.trim().toInt().coerceAtLeast(0)
                        Log.i(TAG, "seek_audio → ${posMs}ms")
                        onSeekAudio?.invoke(posMs)
                    }.onFailure { Log.w(TAG, "seek_audio parse error: $it — value='$url'") }
                }
                else            -> Log.w(TAG, "Unknown action: '$action'")
            }
        }
    }
}
