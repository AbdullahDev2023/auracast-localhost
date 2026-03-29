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
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.akdevelopers.auracast.analytics.Analytics
import com.akdevelopers.auracast.analytics.CrashManager
import com.akdevelopers.auracast.service.StreamIdentity
import com.akdevelopers.auracast.service.StreamStatus

/**
 * AudioWebSocketClient — always-connected edition.
 *
 * Connection lifecycle is independent of streaming state.
 * - connect()            → opens WebSocket, auto-reconnects forever
 * - disconnect()         → cleanly closes, stops reconnect loop
 * - sendFrame()          → sends an encoded audio frame (no-op if not open)
 * - sendStreamingState() → notifies server whether mic is active
 *
 * Callbacks:
 *   onStatusChange  — CONNECTING | CONNECTED_IDLE | RECONNECTING
 *
 * NOTE: Commands (start/stop/change_url etc.) are no longer received here.
 * They come via AudioControlClient over the dedicated /control WebSocket.
 */
class AudioWebSocketClient {

    private val TAG = "AC_WebSocket"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private val shouldReconnect = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var resolvedUrl: String = ""
    private var serverUrl: String = ""

    var onStatusChange: ((StreamStatus) -> Unit)? = null

    var sampleRate: Int = 48000
    var frameMs: Int = 60
    /**
     * Current encoding bitrate in bps. Included in every codec announcement so
     * the server's [channel.codecConfig] and all browser listeners always reflect
     * the real bitrate, not a stale default.
     * Set from [AudioWebSocketTransport] / [AudioUdpTransportWrapper] before [connect].
     */
    var bitrate: Int = 192_000

    /**
     * Called when the server sends a `{"type":"udpAck","udpPort":<N>}` message
     * confirming the UDP port it is listening on.
     * Wired by [AudioUdpTransport] so it can re-point its [DatagramChannel] to the
     * confirmed port instead of assuming the default (4001).
     */
    var onUdpAck: ((port: Int) -> Unit)? = null

    // ── UDP transport support ─────────────────────────────────────────────────
    /**
     * When set before [connect] (or before the next [openSocket] call), the
     * client will send a `{"type":"udpReady","udpToken":"<token>"}` message
     * immediately after the WebSocket opens, then clear this field.
     * Used by [com.akdevelopers.auracast.audio.AudioUdpTransport] to register
     * the session token on the server.
     */
    var pendingUdpToken: String? = null

    /**
     * The 8-byte binary representation of the active session token (first 8 bytes
     * of the 32-char hex string). Set when [pendingUdpToken] is consumed in
     * [Listener.onOpen] so [AudioUdpTransport.sendFrame] can build packet headers.
     * Null until the first successful UDP handshake.
     */
    @Volatile var sessionToken8: ByteArray? = null
        private set

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

    /**
     * True only while the OkHttp WebSocket is in the OPEN state (after onOpen,
     * before onFailure / onClosing / disconnect). Used by NetworkChangeReceiver
     * to decide whether a forced reconnect is needed.
     */
    @Volatile var isOpen: Boolean = false
        private set

    // App-level ping/pong loop was removed. Connection liveness is now monitored
    // exclusively by OkHttp protocol-level pings (pingInterval = 25 s, set on
    // sharedClient). OkHttp calls onFailure() if a pong is not received within
    // the next ping window, which triggers scheduleReconnect() below.
    // This is more reliable than a Handler-based loop because OkHttp's dispatcher
    // threads continue to run under OEM power-saving modes that pause the main looper.

    companion object {
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            // Use OkHttp protocol-level pings (every 25 s) instead of the old
            // app-level ping/pong loop. OkHttp pings run on its dispatcher thread
            // pool, which is independent of the main-thread Handler and therefore
            // survive OEM Doze / power-saving pauses that freeze the main looper.
            // If a pong does not arrive within the next ping window, OkHttp calls
            // onFailure() → scheduleReconnect(). The old dual-loop (OkHttp + app)
            // was removed because it caused duplicate reconnect storms.
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    /** Open and maintain a persistent WebSocket to [url]. */
    fun connect(url: String, context: Context) {
        Log.i(TAG, "connect: url=$url")
        serverUrl   = url
        resolvedUrl = StreamIdentity.appendToUrl(context, url)
        // Guard against double-connect (e.g. DefaultStreamOrchestrator.start()
        // called twice by Watchdog). Without this, a second connect() while a
        // TLS handshake is already in progress creates a second Listener with
        // generation=0. Both listeners think they are current (each instance has
        // its own generation counter), so when the server closes the first socket
        // with "Replaced", onClosing fires with isCurrent()=true → reconnects →
        // infinite replacement storm.
        if (shouldReconnect.get() && webSocket != null) {
            Log.d(TAG, "connect: already connecting/connected, calling reconnectNow instead")
            reconnectNow()
            return
        }
        shouldReconnect.set(true)
        reconnectAttempt = 0
        openSocket()
    }

    private fun openSocket() {
        if (!shouldReconnect.get()) { Log.w(TAG, "openSocket: shouldReconnect=false — abort"); return }
        val status = if (reconnectAttempt == 0) StreamStatus.CONNECTING else StreamStatus.RECONNECTING
        onStatusChange?.invoke(status)
        val request = Request.Builder()
            .url(resolvedUrl)
            .header("ngrok-skip-browser-warning", "true")
            .header("User-Agent", "AuraCast/1.0")
            .build()
        webSocket = sharedClient.newWebSocket(request, Listener(generation))
    }

    /** Send a binary audio frame. Silently dropped if socket not ready. */
    fun sendFrame(data: ByteArray) {
        val ws = webSocket ?: return
        if (ws.queueSize() > 200_000L) return
        ws.send(data.toByteString())
    }

    /**
     * Notify the server of the current audio source mode.
     * Called when a phone call starts/ends so browsers can show 📞 Call mode.
     * @param mode "mic" (normal) | "call" (VOICE_COMMUNICATION during a call)
     */
    fun sendAudioMode(mode: String) {
        try {
            webSocket?.send("""{"type":"audioMode","mode":"$mode"}""")
            Log.d(TAG, "sendAudioMode: $mode")
        } catch (e: Exception) {
            Log.w(TAG, "sendAudioMode failed: ${e.message}")
        }
    }

    /**
     * Re-announce the Opus codec parameters after a bitrate hot-swap.
     * The server forwards this to all browser listeners so they can update
     * their buffer/display without restarting the audio stream.
     */
    fun sendCodecConfig(sampleRate: Int, frameMs: Int, bitrate: Int) {
        try {
            webSocket?.send(
                """{"type":"codec","codec":"opus","sampleRate":$sampleRate,"channels":1,"frameMs":$frameMs,"bitrate":$bitrate}"""
            )
            Log.d(TAG, "sendCodecConfig: ${bitrate / 1000} kbps")
        } catch (e: Exception) {
            Log.w(TAG, "sendCodecConfig failed: ${e.message}")
        }
    }

    /**
     * Notify the server whether the mic is actively capturing.
     * Called when AudioCaptureEngine starts/stops.
     */
    fun sendStreamingState(active: Boolean) {
        try {
            webSocket?.send("""{"type":"streamingState","active":$active}""")
            Log.d(TAG, "sendStreamingState: active=$active")
        } catch (e: Exception) {
            Log.w(TAG, "sendStreamingState failed: ${e.message}")
        }
    }

    /**
     * Send a UDP session-registration handshake message over the stream WebSocket.
     * The server replies with `{"type":"udpAck","udpPort":<port>}`.
     * Called automatically from [Listener.onOpen] when [pendingUdpToken] is set;
     * exposed publicly for testing / manual invocation.
     */
    fun sendUdpReady(token: String) {
        try {
            webSocket?.send("""{"type":"udpReady","udpToken":"$token"}""")
            Log.d(TAG, "sendUdpReady: token=${token.take(8)}…")
        } catch (e: Exception) {
            Log.w(TAG, "sendUdpReady failed: ${e.message}")
        }
    }

    /** Permanently close the WebSocket. Stops reconnect loop. */
    fun disconnect() {
        Log.i(TAG, "disconnect")
        generation++                          // invalidate all in-flight listeners
        shouldReconnect.set(false)
        reconnectAttempt = 0
        isOpen = false
        sessionToken8 = null
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        onStatusChange?.invoke(StreamStatus.IDLE)
    }

    /** Force-reconnect now (e.g. network change or watchdog). */
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

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return
        reconnectAttempt++
        val delayMs = minOf(1_000L shl (reconnectAttempt - 1), 30_000L)
        Log.d(TAG, "scheduleReconnect: attempt=$reconnectAttempt delay=${delayMs}ms")
        Analytics.logWsReconnectScheduled(attempt = reconnectAttempt, delayMs = delayMs)
        mainHandler.postDelayed({ openSocket() }, delayMs)
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {

        private fun isCurrent(): Boolean = gen == generation

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) {
                Log.d(TAG, "onOpen — stale socket (gen=$gen, current=$generation), closing")
                webSocket.close(1000, "Stale")
                return
            }
            Log.i(TAG, "onOpen: CONNECTED ✓")
            isOpen = true
            reconnectAttempt = 0
            // No app-level ping loop — OkHttp protocol-level pings (pingInterval=25s) handle keepalive.
            webSocket.send("""{"type":"codec","codec":"opus","sampleRate":$sampleRate,"channels":1,"frameMs":$frameMs,"bitrate":$bitrate}""")
            Analytics.logWsConnected(attempt = 0, sampleRate = sampleRate)

            // UDP handshake — consume pending token (if any) and send udpReady.
            val token = pendingUdpToken
            if (!token.isNullOrEmpty()) {
                pendingUdpToken = null
                sessionToken8 = UdpPacket.tokenStringToBytes8(token)
                sendUdpReady(token)
            }

            onStatusChange?.invoke(StreamStatus.CONNECTED_IDLE)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            when {
                text == """{"type":"pong"}""" -> {
                    // Server responded to an old app-level ping — safely ignored;
                    // liveness is now tracked by OkHttp protocol-level pings.
                    Log.v(TAG, "app-pong ← (ignored, using OkHttp pings)")
                }
                text == """{"type":"ping"}""" -> { webSocket.send("""{"type":"pong"}""") }
                text.startsWith("""{"type":"udpAck"""") -> {
                    try {
                        val port = org.json.JSONObject(text).getInt("udpPort")
                        Log.d(TAG, "udpAck ← port=$port")
                        onUdpAck?.invoke(port)
                    } catch (e: Exception) {
                        Log.w(TAG, "udpAck parse failed: ${e.message}")
                    }
                }
                else -> Log.v(TAG, "server msg ignored: ${text.take(80)}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) {
                Log.d(TAG, "onFailure — stale socket (gen=$gen), ignoring")
                return
            }
            Log.e(TAG, "onFailure: ${t.javaClass.simpleName}: ${t.message}")
            isOpen = false
            CrashManager.recordNonFatal(t, "WebSocket failure — attempt=$reconnectAttempt")
            Analytics.logWsFailure(attempt = reconnectAttempt, errorType = t.javaClass.simpleName)
            onStatusChange?.invoke(StreamStatus.RECONNECTING)
            scheduleReconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) {
                // Stale socket being closed by server ("Replaced") — acknowledge but don't reconnect.
                Log.d(TAG, "onClosing — stale socket (gen=$gen) code=$code reason=$reason, skipping reconnect")
                webSocket.close(1000, null)
                return
            }
            Log.w(TAG, "onClosing: code=$code reason='$reason'")
            isOpen = false
            webSocket.close(1000, null)
            if (shouldReconnect.get()) {
                onStatusChange?.invoke(StreamStatus.RECONNECTING)
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return   // stale — ignore
            Log.w(TAG, "onClosed: code=$code reason='$reason'")
            if (isOpen) {
                isOpen = false
                if (shouldReconnect.get()) {
                    onStatusChange?.invoke(StreamStatus.RECONNECTING)
                    scheduleReconnect()
                }
            }
        }
    }
}
