package com.akdevelopers.auracast.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AudioCastPlayer — full-featured audio player for server-pushed media.
 *
 * ── Commands supported ──────────────────────────────────────────────────────
 *  play(url)           — download + play once; cancels current playback
 *  pause()             — pause at current position
 *  resume()            — resume from paused position
 *  stop()              — stop + discard temp file + clear loops
 *  loop(url, n)        — play exactly n times (n ≥ 1)
 *  loopInfinite(url)   — play forever until stop()
 *  queueAdd(url)       — append URL to play queue; auto-starts if idle
 *  queueClear()        — clear pending queue (does not stop current track)
 *  setVolume(0f..1f)   — hot-change volume on active MediaPlayer
 *  seekTo(positionMs)  — jump to position in currently playing/paused track
 *
 * ── State callbacks ─────────────────────────────────────────────────────────
 *  onStateChange(State)               — IDLE | DOWNLOADING | PLAYING | PAUSED
 *  onDownloadComplete(url, bytes)     — fired after each successful download
 *  onQueueChanged(List<String>)       — fired whenever queue list changes
 *  onPlaybackComplete()               — fired when a non-looping track ends naturally
 *  onVideoAdded(url)                  — fired when any URL is accepted for playback
 *  onVideoDownloaded(url, bytes)      — alias of onDownloadComplete for dashboard
 *
 * Thread-safety: all public methods are safe to call from any thread.
 */
class AudioCastPlayer(private val context: Context) {

    // ── State ─────────────────────────────────────────────────────────────────
    enum class State { IDLE, DOWNLOADING, PLAYING, PAUSED }

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onStateChange:      ((State) -> Unit)?                   = null
    var onDownloadComplete: ((url: String, bytes: Long) -> Unit)? = null
    /** Alias exposed to server dashboard — same event as onDownloadComplete. */
    var onVideoDownloaded:  ((url: String, bytes: Long) -> Unit)? = null
    /** Fired the moment any URL is accepted (queued or played directly). */
    var onVideoAdded:       ((url: String) -> Unit)?             = null
    var onQueueChanged:     ((List<String>) -> Unit)?            = null
    var onPlaybackComplete: (() -> Unit)?                        = null

    companion object {
        private const val TAG   = "AC_AudioCast"
        /** Pass as [loop] count to play forever. */
        const val LOOP_INFINITE = -1
    }

    // ── Internal state ────────────────────────────────────────────────────────
    private val scopeJob     = SupervisorJob()
    private val scope        = CoroutineScope(scopeJob + Dispatchers.IO)
    private val client       = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest:  AudioFocusRequest? = null
    private var mediaPlayer:   MediaPlayer?       = null
    private var currentFile:   File?              = null

    @Volatile private var loopsRemaining: Int    = 0   // -1 = infinite
    @Volatile private var currentUrl:     String = ""
    @Volatile private var volume:         Float  = 1f
    @Volatile private var currentState:   State  = State.IDLE

    private val _queue = ArrayDeque<String>()

    /** Read-only snapshot of the pending queue. */
    val queue: List<String> get() = synchronized(_queue) { _queue.toList() }

    /** All (url, bytes) pairs that have completed downloading this session. */
    val downloadedFiles = mutableListOf<Pair<String, Long>>()

    // ── Public API ────────────────────────────────────────────────────────────

    /** Download [url] in the background and play once. Cancels active playback. */
    fun play(url: String) {
        Log.i(TAG, "play requested: $url")
        onVideoAdded?.invoke(url)
        launchPlay(url, loops = 1)
    }

    /** Pause playback at the current position. No-op if not playing. */
    fun pause() {
        Log.i(TAG, "pause requested (state=$currentState)")
        mediaPlayer?.runCatching { if (isPlaying) { pause(); setState(State.PAUSED) } }
    }

    /** Resume from the paused position. No-op if not paused. */
    fun resume() {
        Log.i(TAG, "resume requested (state=$currentState)")
        mediaPlayer?.runCatching {
            if (!isPlaying) { start(); setState(State.PLAYING) }
        }
    }

    /** Stop playback, clear temp file, and clear any remaining loop count. */
    fun stop() {
        Log.i(TAG, "stop requested")
        loopsRemaining = 0
        stopInternal()
    }

    /**
     * Play [url] exactly [count] times (1 = once, 2 = twice …).
     * Cancels current playback first.
     */
    fun loop(url: String, count: Int) {
        require(count >= 1) { "loop count must be ≥ 1" }
        Log.i(TAG, "loop requested: $url × $count")
        onVideoAdded?.invoke(url)
        launchPlay(url, loops = count)
    }

    /** Play [url] forever until [stop] is called. */
    fun loopInfinite(url: String) {
        Log.i(TAG, "loopInfinite requested: $url")
        onVideoAdded?.invoke(url)
        launchPlay(url, loops = LOOP_INFINITE)
    }

    // ── Queue API ─────────────────────────────────────────────────────────────

    /** Append [url] to the end of the play queue. Auto-starts if currently idle. */
    fun queueAdd(url: String) {
        synchronized(_queue) { _queue.addLast(url) }
        Log.i(TAG, "Queue add: $url (size=${_queue.size})")
        onVideoAdded?.invoke(url)
        notifyQueue()
        if (currentState == State.IDLE) advanceQueue()
    }

    /** Remove all pending queue entries without stopping the current track. */
    fun queueClear() {
        synchronized(_queue) { _queue.clear() }
        Log.i(TAG, "Queue cleared")
        notifyQueue()
    }

    /** Set playback volume [0f … 1f]. Applies immediately if a track is active. */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        mediaPlayer?.setVolume(volume, volume)
        Log.d(TAG, "Volume set to $volume")
    }

    /**
     * Seek to [positionMs] milliseconds in the currently playing or paused track.
     * No-op if no track is loaded. Safe to call from any thread.
     */
    fun seekTo(positionMs: Int) {
        val mp = mediaPlayer ?: run {
            Log.w(TAG, "seekTo($positionMs ms) — no active MediaPlayer, ignored")
            return
        }
        val clamped = positionMs.coerceAtLeast(0)
        mp.seekTo(clamped)
        Log.i(TAG, "seekTo → ${clamped}ms")
    }

    /** Full teardown — call from StreamingService.stopAll(). */
    fun release() {
        Log.i(TAG, "release")
        loopsRemaining = 0
        synchronized(_queue) { _queue.clear() }
        stopInternal()
        scopeJob.cancel()
        client.dispatcher.executorService.shutdown()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun launchPlay(url: String, loops: Int) {
        loopsRemaining = loops
        currentUrl     = url
        stopInternal(keepLoopState = true)
        setState(State.DOWNLOADING)
        scope.launch {
            try {
                val file = downloadToCache(url)
                withContext(Dispatchers.Main) { startPlayback(file) }
            } catch (e: Exception) {
                Log.e(TAG, "AudioCast play error: $e")
                setState(State.IDLE)
            }
        }
    }

    private fun stopInternal(keepLoopState: Boolean = false) {
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
            reset()
            release()
        }
        mediaPlayer  = null
        currentFile?.delete()
        currentFile  = null
        if (!keepLoopState) loopsRemaining = 0
        abandonAudioFocus()
        if (!keepLoopState) setState(State.IDLE)
    }

    private fun downloadToCache(url: String): File {
        Log.d(TAG, "Downloading audio from $url")
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code} for $url")
            val ext  = url.substringAfterLast('.', "").let { if (it.length in 2..4) ".$it" else ".audio" }
            val file = File(context.cacheDir, "audio_cast_${System.currentTimeMillis()}$ext")
            file.outputStream().use { out ->
                response.body?.byteStream()?.copyTo(out)
                    ?: throw RuntimeException("Empty body for $url")
            }
            val bytes = file.length()
            Log.d(TAG, "Downloaded → ${file.name} ($bytes B)")
            downloadedFiles.add(url to bytes)
            onDownloadComplete?.invoke(url, bytes)
            onVideoDownloaded?.invoke(url, bytes)
            return file
        }
    }

    private fun startPlayback(file: File) {
        requestAudioFocus()
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setVolume(volume, volume)
            setDataSource(file.absolutePath)
            setOnCompletionListener { onTrackComplete() }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                stopInternal()
                true
            }
            prepare()
            start()
        }
        mediaPlayer = mp
        currentFile = file
        setState(State.PLAYING)
        Log.i(TAG, "Playback started: ${file.name}")
    }

    /** Called when a track finishes naturally. Handles loops and queue advance. */
    private fun onTrackComplete() {
        Log.i(TAG, "Track complete (loopsRemaining=$loopsRemaining)")
        stopInternal(keepLoopState = true)

        when {
            loopsRemaining == LOOP_INFINITE -> replayCurrentUrl()
            loopsRemaining > 1             -> { loopsRemaining--; replayCurrentUrl() }
            else -> {
                loopsRemaining = 0
                onPlaybackComplete?.invoke()
                advanceQueue()
            }
        }
    }

    private fun replayCurrentUrl() {
        scope.launch {
            try {
                val file = downloadToCache(currentUrl)
                withContext(Dispatchers.Main) { startPlayback(file) }
            } catch (e: Exception) {
                Log.e(TAG, "Replay error: $e")
                setState(State.IDLE)
            }
        }
    }

    private fun advanceQueue() {
        val next = synchronized(_queue) { _queue.removeFirstOrNull() } ?: run {
            setState(State.IDLE); return
        }
        notifyQueue()
        Log.i(TAG, "Queue advance → $next")
        launchPlay(next, loops = 1)
    }

    private fun notifyQueue() = onQueueChanged?.invoke(queue)

    private fun setState(s: State) {
        currentState = s
        onStateChange?.invoke(s)
    }

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener {}
            .build()
        audioManager.requestAudioFocus(focusRequest!!)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
