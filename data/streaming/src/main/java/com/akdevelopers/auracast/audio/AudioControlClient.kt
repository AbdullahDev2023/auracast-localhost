package com.akdevelopers.auracast.audio
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.akdevelopers.auracast.analytics.Analytics
import com.akdevelopers.auracast.service.StreamIdentity
import com.akdevelopers.auracast.service.StreamStatus

/**
 * AudioControlClient — always-on command channel.
 *
 * Connects to wss://<host>/control?id=<uuid>&name=<X>
 * JSON only — never sends binary audio frames.
 * Uses exponential backoff on reconnect: 1s → 2s → 4s → 8s → 16s → 30s cap.
 *
 * Commands are delivered via onCmd(commandId, action, url).
 * CommandProcessor handles deduplication so both channels can deliver safely.
 */
class AudioControlClient {

    companion object {
        private const val TAG              = "AC_Control"

        // Dedicated client — separate from the audio stream client.
        // Protocol-level pings (every 25 s) keep the control socket alive through
        // OEM Doze / power-saving modes that freeze the main-thread Handler.
        // The old app-level ping/pong loop (main-handler-based) was unreliable and
        // has been removed; OkHttp's dispatcher-thread pings replace it.
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    /** Called with (commandId, action, url) for every incoming cmd message. */
    var onCmd: ((commandId: String, action: String, url: String) -> Unit)? = null

    /** Status changes driven by the control connection itself. */
    var onStatusChange: ((StreamStatus) -> Unit)? = null

    @Volatile var isOpen: Boolean = false
        private set

    private val mainHandler     = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private val shouldReconnect = AtomicBoolean(false)
    private var resolvedUrl     = ""

    /**
     * Incremented on every intentional reconnect or disconnect.
     * Each Listener captures this at construction time. If the value has changed
     * by the time onClosing/onFailure fires, the callback belongs to a stale
     * (already-replaced) socket and must NOT trigger scheduleReconnect().
     * This breaks the "Replaced → onClosing → openSocket → Replaced …" avalanche
     * that previously produced 4+ simultaneous connections and cascading
     * SSLHandshakeExceptions.
     */
    @Volatile private var generation = 0
    private var reconnectAttempt = 0


    // ── Public API ────────────────────────────────────────────────────────────

    /** Open and maintain a persistent control WebSocket. Derives /control URL from serverUrl. */
    fun connect(serverUrl: String, context: Context) {
        val controlUrl = serverUrl.replace(Regex("/stream(\\?.*)?$"), "/control")
        resolvedUrl = StreamIdentity.appendToUrl(context, controlUrl)
        // Guard against double-connect (same root cause as AudioWebSocketClient.connect).
        // If shouldReconnect is already true and a socket is in flight, calling connect()
        // again (e.g. from Watchdog → start() → remoteCommandSource.start()) would open
        // a second socket on the same instance with the same generation counter. When the
        // server closes the first with "Replaced", isCurrent() returns true → reconnects →
        // infinite replacement storm.
        if (shouldReconnect.get() && webSocket != null) {
            Log.d(TAG, "connect: already connecting/connected — calling reconnectNow instead")
            reconnectNow()
            return
        }
        shouldReconnect.set(true)
        reconnectAttempt = 0
        Log.i(TAG, "connect → $resolvedUrl")
        openSocket()
    }

    /** Close and stop all reconnects. */
    fun disconnect() {
        Log.i(TAG, "disconnect")
        generation++                          // invalidate all in-flight listeners
        shouldReconnect.set(false)
        isOpen = false
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        onStatusChange?.invoke(StreamStatus.IDLE)
    }

    /** Force-reconnect immediately (e.g. on network change). */
    fun reconnectNow() {
        Log.i(TAG, "reconnectNow")
        generation++                          // invalidate all in-flight listeners
        mainHandler.removeCallbacksAndMessages(null)
        reconnectAttempt = 0
        isOpen = false
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        openSocket()
    }

    /** Update resolvedUrl and reconnect immediately — used by change_url command. */
    fun reconnectTo(serverUrl: String, context: Context) {
        val controlUrl = serverUrl.replace(Regex("/stream(\\?.*)?$"), "/control")
        resolvedUrl = StreamIdentity.appendToUrl(context, controlUrl)
        Log.i(TAG, "reconnectTo → $resolvedUrl")
        reconnectNow()
    }

    /** Notify server of current streaming status so dashboard stays accurate. */
    fun sendStatusUpdate(status: StreamStatus) {
        if (!isOpen) return
        runCatching {
            webSocket?.send("""{"type":"statusUpdate","status":"${status.name}"}""")
        }
    }


    // ── Internals ─────────────────────────────────────────────────────────────

    private fun openSocket() {
        if (!shouldReconnect.get()) return
        onStatusChange?.invoke(StreamStatus.CONNECTING)
        val request = Request.Builder()
            .url(resolvedUrl)
            .header("ngrok-skip-browser-warning", "true")
            .header("User-Agent", "AuraCast/1.0-control")
            .build()
        webSocket = sharedClient.newWebSocket(request, Listener(generation))
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return
        reconnectAttempt++
        // Exponential backoff: 1s → 2s → 4s → 8s → 16s → 30s cap
        // Mirrors AudioWebSocketClient so both channels back off together when
        // ngrok is unreachable, preventing connection-rate throttling.
        val delayMs = minOf(1_000L shl (reconnectAttempt - 1), 30_000L)
        Log.d(TAG, "scheduleReconnect: attempt=$reconnectAttempt delay=${delayMs}ms")
        Analytics.logWsReconnectScheduled(attempt = reconnectAttempt, delayMs = delayMs)
        onStatusChange?.invoke(StreamStatus.RECONNECTING)
        mainHandler.postDelayed({ openSocket() }, delayMs)
    }

    // ── WebSocketListener ─────────────────────────────────────────────────────

    /**
     * @param gen  Generation value captured at socket-creation time.
     *             If [generation] has advanced past [gen] by the time a callback
     *             fires, this listener belongs to an already-replaced socket and
     *             must not trigger any reconnect logic.
     */
    private inner class Listener(private val gen: Int) : WebSocketListener() {

        private fun isCurrent(): Boolean = gen == generation

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) {
                Log.d(TAG, "onOpen — stale socket (gen=$gen, current=$generation), closing")
                webSocket.close(1000, "Stale")
                return
            }
            Log.i(TAG, "onOpen ✓  controlWs connected")
            isOpen = true
            reconnectAttempt = 0
            // OkHttp protocol-level pings (pingInterval=25s) keep socket alive —
            // no app-level ping loop needed.
            onStatusChange?.invoke(StreamStatus.CONNECTED_IDLE)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            when {
                text.contains(""""type":"pong"""") -> {
                    // Protocol pong from server's app-level ping — safely ignored;
                    // OkHttp handles protocol-level keepalive internally.
                    Log.v(TAG, "pong ← (ignored, using OkHttp pings)")
                }
                text.contains(""""type":"ping"""") -> {
                    runCatching { webSocket.send("""{"type":"pong"}""") }
                }
                text.contains(""""type":"cmd"""") -> {
                    runCatching {
                        val p         = JSONObject(text)
                        val commandId = p.optString("commandId", UUID.randomUUID().toString())
                        val action    = p.optString("action", "")
                        val url       = p.optString("url", "")
                        if (action.isNotBlank()) {
                            Log.i(TAG, "CMD ← action=$action id=${commandId.take(8)}")
                            onCmd?.invoke(commandId, action, url)
                        }
                    }.onFailure { Log.w(TAG, "Failed to parse cmd: $text") }
                }
                text.contains(""""type":"welcome"""") -> Log.i(TAG, "Control channel ready ✓")
                else -> Log.v(TAG, "msg: ${text.take(80)}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) {
                Log.d(TAG, "onFailure — stale socket (gen=$gen), ignoring")
                return
            }
            Log.e(TAG, "onFailure: ${t.message}")
            isOpen = false
            Analytics.logWsFailure(attempt = reconnectAttempt, errorType = t.javaClass.simpleName)
            scheduleReconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) {
                // Stale socket being closed by server ("Replaced") — acknowledge but don't reconnect.
                Log.d(TAG, "onClosing — stale socket (gen=$gen) code=$code reason=$reason, skipping reconnect")
                webSocket.close(1000, null)
                return
            }
            Log.w(TAG, "onClosing code=$code reason=$reason")
            isOpen = false
            webSocket.close(1000, null)
            if (shouldReconnect.get()) scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return   // stale — ignore
            if (isOpen) {
                isOpen = false
                if (shouldReconnect.get()) scheduleReconnect()
            }
        }
    }
}
