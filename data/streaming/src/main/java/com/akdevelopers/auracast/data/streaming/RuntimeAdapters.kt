package com.akdevelopers.auracast.data.streaming

import android.content.Context
import com.akdevelopers.auracast.audio.AudioControlClient
import com.akdevelopers.auracast.audio.AudioQualityConfig
import com.akdevelopers.auracast.audio.AudioUdpTransport
import com.akdevelopers.auracast.audio.AudioWebSocketClient
import com.akdevelopers.auracast.domain.streaming.AudioCaptureController
import com.akdevelopers.auracast.domain.streaming.AudioCaptureFactory
import com.akdevelopers.auracast.domain.streaming.AudioTransport
import com.akdevelopers.auracast.domain.streaming.MetricsPublisher
import com.akdevelopers.auracast.domain.streaming.RemoteCommandSource
import com.akdevelopers.auracast.domain.streaming.StatusPublisher
import com.akdevelopers.auracast.remote.FirebaseRemoteController
import com.akdevelopers.auracast.service.StreamStatus

class AudioWebSocketTransport(
    private val client: AudioWebSocketClient = AudioWebSocketClient(),
    private val config: AudioQualityConfig = AudioQualityConfig.HIGH_QUALITY
) : AudioTransport {

    override var onStatusChange: ((StreamStatus) -> Unit)?
        get() = client.onStatusChange
        set(value) {
            client.sampleRate = config.sampleRate
            client.frameMs = config.frameMs
            client.bitrate = config.opusBitrate
            client.onStatusChange = value
        }

    override val isOpen: Boolean
        get() = client.isOpen

    override fun connect(serverUrl: String, context: Context) {
        client.sampleRate = config.sampleRate
        client.frameMs = config.frameMs
        client.bitrate = config.opusBitrate
        client.connect(serverUrl, context)
    }

    override fun disconnect() {
        client.disconnect()
    }

    override fun reconnectNow() {
        client.reconnectNow()
    }

    override fun reconnectTo(serverUrl: String, context: Context) {
        client.disconnect()
        connect(serverUrl, context)
    }

    override fun sendFrame(data: ByteArray) {
        client.sendFrame(data)
    }

    override fun sendStreamingState(active: Boolean) {
        client.sendStreamingState(active)
    }

    override fun sendAudioMode(mode: String) {
        client.sendAudioMode(mode)
    }

    override fun sendCodecConfig(sampleRate: Int, frameMs: Int, bitrate: Int) {
        client.sendCodecConfig(sampleRate = sampleRate, frameMs = frameMs, bitrate = bitrate)
    }
}

/**
 * [AudioTransport] adapter that sends Opus frames over UDP while keeping all
 * signalling (codec config, streaming state, audio mode, ping/pong) on the
 * existing WebSocket connection.
 *
 * Drop-in replacement for [AudioWebSocketTransport] — the orchestrator never
 * needs to know which transport is active.
 */
class AudioUdpTransportWrapper(
    private val config: AudioQualityConfig = AudioQualityConfig.HIGH_QUALITY,
    private val wsClient: AudioWebSocketClient = AudioWebSocketClient(),
    udpPort: Int = AudioUdpTransport.DEFAULT_UDP_PORT,
) : AudioTransport {

    private val transport = AudioUdpTransport(wsSignalling = wsClient, udpPort = udpPort)

    override var onStatusChange: ((StreamStatus) -> Unit)?
        get() = transport.onStatusChange
        set(value) {
            wsClient.sampleRate = config.sampleRate
            wsClient.frameMs = config.frameMs
            wsClient.bitrate = config.opusBitrate
            transport.onStatusChange = value
        }

    override val isOpen: Boolean get() = transport.isOpen

    override fun connect(serverUrl: String, context: Context) {
        wsClient.sampleRate = config.sampleRate
        wsClient.frameMs = config.frameMs
        wsClient.bitrate = config.opusBitrate
        transport.connect(serverUrl, context)
    }

    override fun disconnect()                                        = transport.disconnect()
    override fun reconnectNow()                                      = transport.reconnectNow()
    override fun reconnectTo(serverUrl: String, context: Context)    = transport.reconnectTo(serverUrl, context)
    override fun sendFrame(data: ByteArray)                          = transport.sendFrame(data)
    override fun sendStreamingState(active: Boolean)                 = transport.sendStreamingState(active)
    override fun sendAudioMode(mode: String)                         = transport.sendAudioMode(mode)
    override fun sendCodecConfig(sampleRate: Int, frameMs: Int, bitrate: Int) =
        transport.sendCodecConfig(sampleRate, frameMs, bitrate)

    override fun supportsUdp(): Boolean = true
}

/**
 * Deferred-routing [AudioTransport] that resolves UDP vs WebSocket at connection
 * time, once the server URL is actually known.
 *
 * ### Why this exists
 * The orchestrator (and therefore the transport) is constructed at DI-graph time
 * via [AppGraph.createStreamOrchestrator].  The server URL is only supplied later,
 * when [DefaultStreamOrchestrator.start] calls [connect].  This means we cannot
 * select the correct transport at construction time — the URL is simply not
 * available yet.
 *
 * [RoutingAudioTransport] solves the chicken-and-egg problem by holding the
 * decision open.  The caller (app-module's [AuraCastAppGraph]) passes in
 * [isUdpCapable] as a lambda, keeping the data-layer free of any app-layer
 * dependency.  On every [connect] / [reconnectTo] call the lambda is evaluated
 * against the real URL and the appropriate concrete transport is created and
 * wired up on-the-fly.
 *
 * @param config         Opus / capture quality forwarded to the concrete transport.
 * @param isUdpCapable   Returns `true` when [serverUrl] can accept UDP datagrams
 *                       directly (non-tunnel LAN / VPS).  Injected by the app graph.
 */
class RoutingAudioTransport(
    private var config: AudioQualityConfig = AudioQualityConfig.HIGH_QUALITY,
    private val isUdpCapable: (serverUrl: String) -> Boolean,
) : AudioTransport {

    private var delegate: AudioTransport? = null

    // Cache the callback so it can be forwarded once the delegate is created.
    private var pendingStatusChange: ((StreamStatus) -> Unit)? = null

    override var onStatusChange: ((StreamStatus) -> Unit)?
        get() = delegate?.onStatusChange ?: pendingStatusChange
        set(value) {
            pendingStatusChange = value
            delegate?.onStatusChange = value
        }

    override val isOpen: Boolean
        get() = delegate?.isOpen ?: false

    override fun connect(serverUrl: String, context: Context) {
        // Disconnect the previous delegate BEFORE replacing it. Without this,
        // the old AudioWebSocketClient instance (with its own generation=0) stays
        // alive with shouldReconnect=true. When the server closes it with code 1000
        // "Replaced", the old instance's onClosing fires, isCurrent() returns true
        // (its own generation hasn't been incremented), and it reconnects — which
        // the server replaces again — creating an infinite connection storm.
        delegate?.disconnect()
        delegate = buildDelegate(serverUrl).also { t ->
            t.onStatusChange = pendingStatusChange
            t.connect(serverUrl, context)
        }
    }

    override fun disconnect() { delegate?.disconnect() }
    override fun reconnectNow() { delegate?.reconnectNow() }

    override fun reconnectTo(serverUrl: String, context: Context) {
        // Same as connect(): disconnect the old delegate before replacing it to
        // prevent the abandoned instance from reconnecting behind our back.
        delegate?.disconnect()
        delegate = buildDelegate(serverUrl).also { t ->
            t.onStatusChange = pendingStatusChange
            t.connect(serverUrl, context)
        }
    }

    override fun sendFrame(data: ByteArray)                        { delegate?.sendFrame(data) }
    override fun sendStreamingState(active: Boolean)               { delegate?.sendStreamingState(active) }
    override fun sendAudioMode(mode: String)                       { delegate?.sendAudioMode(mode) }
    override fun sendCodecConfig(sampleRate: Int, frameMs: Int, bitrate: Int) {
        // Keep config in sync so the next buildDelegate (reconnect after quality change)
        // starts the new delegate with the correct codec params, not the stale defaults.
        config = config.copy(sampleRate = sampleRate, frameMs = frameMs, opusBitrate = bitrate)
        delegate?.sendCodecConfig(sampleRate, frameMs, bitrate)
    }
    override fun supportsUdp(): Boolean = delegate?.supportsUdp() ?: false

    private fun buildDelegate(serverUrl: String): AudioTransport =
        if (isUdpCapable(serverUrl)) AudioUdpTransportWrapper(config)
        else AudioWebSocketTransport(config = config)
}

// ─────────────────────────────────────────────────────────────────────────────

class ControlSocketRemoteCommandSource(
    private val client: AudioControlClient = AudioControlClient()
) : RemoteCommandSource, StatusPublisher {

    override var onCommandReceived: ((commandId: String, action: String, url: String, source: String) -> Unit)? = null

    override var onStatusChange: ((StreamStatus) -> Unit)?
        get() = client.onStatusChange
        set(value) {
            client.onStatusChange = value
        }

    override val isOpen: Boolean
        get() = client.isOpen

    override fun start(serverUrl: String, context: Context) {
        client.onCmd = { commandId, action, url ->
            onCommandReceived?.invoke(commandId, action, url, "websocket")
        }
        client.connect(serverUrl, context)
    }

    override fun stop() {
        client.disconnect()
    }

    override fun reconnectNow() {
        client.reconnectNow()
    }

    override fun reconnectTo(serverUrl: String, context: Context) {
        client.reconnectTo(serverUrl, context)
    }

    override fun publishStatus(status: StreamStatus, serverUrl: String) {
        client.sendStatusUpdate(status)
    }
}

class FirebaseSyncBridge(private val appContext: Context) :
    RemoteCommandSource,
    StatusPublisher,
    MetricsPublisher {

    private var controller: FirebaseRemoteController? = null
    private var started = false

    override var onCommandReceived: ((commandId: String, action: String, url: String, source: String) -> Unit)? = null
    override var onStatusChange: ((StreamStatus) -> Unit)? = null

    override val isOpen: Boolean
        get() = started

    override fun start(serverUrl: String, context: Context) {
        if (started) return
        controller = FirebaseRemoteController(appContext).apply {
            onCommandReceived = { commandId, action, url ->
                this@FirebaseSyncBridge.onCommandReceived?.invoke(commandId, action, url, "firebase")
            }
            start()
        }
        started = true
    }

    override fun stop() {
        controller?.stop()
        controller = null
        started = false
    }

    override fun reconnectNow() = Unit

    override fun reconnectTo(serverUrl: String, context: Context) = Unit

    override fun publishStatus(status: StreamStatus, serverUrl: String) {
        controller?.pushStatus(status, serverUrl)
    }

    override fun publishRealtimeMetrics(framesPerSec: Float, kbps: Float, uptimeSec: Int, quality: String) {
        controller?.pushRealtimeMetrics(framesPerSec, kbps, uptimeSec, quality)
    }
}

class CompositeRemoteCommandSource(
    private val sources: List<RemoteCommandSource>
) : RemoteCommandSource {

    override var onCommandReceived: ((commandId: String, action: String, url: String, source: String) -> Unit)? = null
        set(value) {
            field = value
            sources.forEach { source ->
                source.onCommandReceived = { commandId, action, url, sourceName ->
                    value?.invoke(commandId, action, url, sourceName)
                }
            }
        }

    override var onStatusChange: ((StreamStatus) -> Unit)? = null
        set(value) {
            field = value
            sources.forEach { source ->
                source.onStatusChange = { status -> value?.invoke(status) }
            }
        }

    override val isOpen: Boolean
        get() = sources.any { it.isOpen }

    override fun start(serverUrl: String, context: Context) {
        sources.forEach { it.start(serverUrl, context) }
    }

    override fun stop() {
        sources.forEach { it.stop() }
    }

    override fun reconnectNow() {
        sources.forEach { it.reconnectNow() }
    }

    override fun reconnectTo(serverUrl: String, context: Context) {
        sources.forEach { it.reconnectTo(serverUrl, context) }
    }
}

class CompositeStatusPublisher(
    private val publishers: List<StatusPublisher>
) : StatusPublisher {
    override fun publishStatus(status: StreamStatus, serverUrl: String) {
        publishers.forEach { it.publishStatus(status, serverUrl) }
    }
}

class CompositeMetricsPublisher(
    private val publishers: List<MetricsPublisher>
) : MetricsPublisher {
    override fun publishRealtimeMetrics(framesPerSec: Float, kbps: Float, uptimeSec: Int, quality: String) {
        publishers.forEach { it.publishRealtimeMetrics(framesPerSec, kbps, uptimeSec, quality) }
    }
}
