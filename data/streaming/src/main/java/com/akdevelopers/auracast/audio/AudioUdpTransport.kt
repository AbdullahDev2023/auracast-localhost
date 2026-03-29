package com.akdevelopers.auracast.audio

import android.content.Context
import android.util.Log
import com.akdevelopers.auracast.service.StreamStatus
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * AudioUdpTransport — [com.akdevelopers.auracast.domain.streaming.AudioTransport] implementation
 * that sends Opus audio frames via UDP while delegating all signalling to an injected
 * [AudioWebSocketClient].
 *
 * ## Lifecycle
 * 1. [connect] — opens the WS signalling connection and the [DatagramChannel] to serverHost:UDP_PORT.
 *    After the WS opens, [AudioWebSocketClient] sends `{"type":"udpReady","udpToken":"<token>"}`
 *    automatically (via [AudioWebSocketClient.pendingUdpToken]).
 * 2. [sendFrame] — encodes the 12-byte header via [UdpPacket] and dispatches on [sendThread].
 * 3. [disconnect] — closes both the WS and the datagram channel.
 *
 * ## Thread safety
 * [sendFrame] is called from the capture thread; all sends are offloaded to [sendThread].
 * [DatagramChannel] is opened in non-blocking mode to avoid stalling the capture thread if the
 * OS send buffer is momentarily full.
 *
 * ## Fallback
 * If UDP is not available (detected by [AuraCastAppGraph.isUdpCapable]), the orchestrator
 * will wire in [AudioWebSocketTransport] instead — zero change to the rest of the stack.
 */
class AudioUdpTransport(
    private val wsSignalling: AudioWebSocketClient,
    private val udpPort: Int = DEFAULT_UDP_PORT,
) : com.akdevelopers.auracast.domain.streaming.AudioTransport {

    companion object {
        private const val TAG = "UdpTransport"
        const val DEFAULT_UDP_PORT = 4001
    }

    private val seq = AtomicInteger(0)

    private var datagramChannel: DatagramChannel? = null
    private var serverAddress: InetSocketAddress? = null

    /** Dedicated single thread for non-blocking UDP sends — never touches the main thread. */
    private val sendThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "udp-send").also { it.isDaemon = true }
    }

    // ── AudioTransport delegation ─────────────────────────────────────────────

    override var onStatusChange: ((StreamStatus) -> Unit)?
        get() = wsSignalling.onStatusChange
        set(value) { wsSignalling.onStatusChange = value }

    override val isOpen: Boolean
        get() = wsSignalling.isOpen

    override fun connect(serverUrl: String, context: Context) {
        val host = extractHost(serverUrl)
        Log.i(TAG, "connect: host=$host udpPort=$udpPort")

        // Generate a fresh session token for this connection attempt.
        val token = generateToken()
        wsSignalling.pendingUdpToken = token

        // When the server confirms the UDP port, re-point the DatagramChannel.
        // This replaces the hardcoded DEFAULT_UDP_PORT with the actual port the
        // server is listening on, as sent in {"type":"udpAck","udpPort":<N>}.
        wsSignalling.onUdpAck = { port ->
            Log.i(TAG, "udpAck: re-pointing UDP → $host:$port")
            serverAddress = InetSocketAddress(host, port)
        }

        // Open the WS (which will send udpReady once connected).
        wsSignalling.connect(serverUrl, context)

        // Prepare datagram channel — non-blocking so sends never stall capture.
        runCatching {
            datagramChannel?.close()
            datagramChannel = DatagramChannel.open().apply { configureBlocking(false) }
            serverAddress = InetSocketAddress(host, udpPort)
            Log.d(TAG, "DatagramChannel opened → $host:$udpPort")
        }.onFailure {
            Log.e(TAG, "Failed to open DatagramChannel: ${it.message}")
        }

        seq.set(0)
    }

    /**
     * Dispatch one Opus frame as a UDP datagram.
     * If the datagram channel is not yet ready the frame is silently dropped
     * (matches the existing WebSocket behaviour when the socket is not open).
     */
    override fun sendFrame(data: ByteArray) {
        val ch = datagramChannel ?: return
        val addr = serverAddress ?: return
        val tokenBytes = wsSignalling.sessionToken8 ?: return
        val seqVal = seq.getAndIncrement().toUShort()
        val packet = UdpPacket.encode(seqVal, tokenBytes, data)
        sendThread.submit {
            runCatching {
                ch.send(ByteBuffer.wrap(packet), addr)
            }.onFailure { e ->
                Log.w(TAG, "sendFrame failed: ${e.message}")
            }
        }
    }

    override fun disconnect() {
        Log.i(TAG, "disconnect")
        sendThread.submit {
            runCatching { datagramChannel?.close() }
            datagramChannel = null
            serverAddress = null
        }
        wsSignalling.disconnect()
    }

    override fun reconnectNow() {
        Log.i(TAG, "reconnectNow")
        seq.set(0)
        wsSignalling.reconnectNow()
    }

    override fun reconnectTo(serverUrl: String, context: Context) {
        disconnect()
        connect(serverUrl, context)
    }

    override fun sendStreamingState(active: Boolean) = wsSignalling.sendStreamingState(active)
    override fun sendAudioMode(mode: String)          = wsSignalling.sendAudioMode(mode)
    override fun sendCodecConfig(sampleRate: Int, frameMs: Int, bitrate: Int) =
        wsSignalling.sendCodecConfig(sampleRate, frameMs, bitrate)

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Extract the hostname from a ws[s]://host[:port]/path URL.
     * Used to open the UDP socket to the same host as the WebSocket.
     */
    private fun extractHost(url: String): String =
        url.removePrefix("wss://").removePrefix("ws://")
            .substringBefore("/")
            .substringBefore(":")   // strip explicit port, if any

    /** Generate a 32-char lowercase hex token (128-bit random). */
    private fun generateToken(): String =
        java.security.SecureRandom().let { rng ->
            val bytes = ByteArray(16).also { rng.nextBytes(it) }
            bytes.joinToString("") { "%02x".format(it) }
        }
}
