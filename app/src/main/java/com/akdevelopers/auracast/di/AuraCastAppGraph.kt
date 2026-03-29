package com.akdevelopers.auracast.di

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import java.net.URI
import com.akdevelopers.auracast.audio.AudioQualityConfig
import com.akdevelopers.auracast.audio.MediaProjectionStore
import com.akdevelopers.auracast.data.streaming.CompositeMetricsPublisher
import com.akdevelopers.auracast.data.streaming.CompositeRemoteCommandSource
import com.akdevelopers.auracast.data.streaming.CompositeStatusPublisher
import com.akdevelopers.auracast.data.streaming.ControlSocketRemoteCommandSource
import com.akdevelopers.auracast.data.streaming.DefaultAudioCaptureFactory
import com.akdevelopers.auracast.data.streaming.DefaultStreamOrchestrator
import com.akdevelopers.auracast.data.streaming.FirebaseSyncBridge
import com.akdevelopers.auracast.data.streaming.MixedCallCaptureFactory
import com.akdevelopers.auracast.data.streaming.RoutingAudioTransport
import com.akdevelopers.auracast.domain.streaming.StreamOrchestrator
import com.akdevelopers.auracast.service.StreamingService

class AuraCastAppGraph(
    override val application: Application
) : AppGraph {

    override val featureRegistry = FeatureRegistry(application)

    override val streamServiceLauncher: StreamServiceLauncher = object : StreamServiceLauncher {
        override fun ensureServiceRunning(context: Context, url: String) {
            context.applicationContext.startForegroundService(
                StreamingService.buildStartIntent(context.applicationContext, url)
            )
        }

        override fun startMic(context: Context) {
            context.startService(
                Intent(context, StreamingService::class.java)
                    .apply { action = StreamingService.ACTION_START_MIC }
            )
        }

        override fun stopMic(context: Context) {
            context.startService(
                Intent(context, StreamingService::class.java)
                    .apply { action = StreamingService.ACTION_STOP_MIC }
            )
        }

        override fun stopFull(context: Context) {
            context.startService(
                Intent(context, StreamingService::class.java)
                    .apply { action = StreamingService.ACTION_STOP_FULL }
            )
        }
    }

    override fun createStreamOrchestrator(context: Context): StreamOrchestrator {
        val appContext = context.applicationContext
        val controlBridge = ControlSocketRemoteCommandSource()
        val firebaseBridge = FirebaseSyncBridge(appContext)

        // RoutingAudioTransport defers the UDP vs WebSocket decision to connect()
        // time, when the real serverUrl is finally known.  At graph-construction
        // time the URL is not yet available (it only arrives via orchestrator.start),
        // so we cannot select the transport here.  The lambda below is evaluated
        // on each connect/reconnectTo call with the actual URL.
        val audioTransport = RoutingAudioTransport(
            isUdpCapable = { url -> isUdpCapable(url) }
        )

        return DefaultStreamOrchestrator(
            appContext = appContext,
            audioTransport = audioTransport,
            remoteCommandSource = CompositeRemoteCommandSource(
                listOf(controlBridge, firebaseBridge)
            ),
            statusPublisher = CompositeStatusPublisher(
                listOf(controlBridge, firebaseBridge)
            ),
            metricsPublisher = CompositeMetricsPublisher(
                listOf(firebaseBridge)
            ),
            audioCaptureFactory = DefaultAudioCaptureFactory(),
            captureFactoryBuilder = { config -> DefaultAudioCaptureFactory(config) },
            // When a phone call starts, use MixedCallCaptureFactory if a MediaProjection
            // token is held (user already approved the permission prompt). Falls back to
            // mic-only call mode if no token is available (API < 29 or user denied).
            callModeSelector = {
                val mp = MediaProjectionStore.projection
                if (mp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MixedCallCaptureFactory(mp, AudioQualityConfig.CALL) to "call_mixed"
                } else {
                    DefaultAudioCaptureFactory(AudioQualityConfig.CALL) to "call"
                }
            },
        )
    }

    companion object {
        /**
         * Returns true when the server URL points to a raw host:port that can accept
         * UDP datagrams directly — i.e. it is not a tunnel URL and not a loopback-style
         * debug URL where TCP may work via adb reverse but UDP definitely will not.
         *
         * Enable UDP only for non-tunnel URLs.  The flag is safe to default true for
         * LAN / VPS deployments without changing any user-visible setting.
         */
        fun isUdpCapable(serverUrl: String?): Boolean {
            if (serverUrl.isNullOrBlank()) return false
            val host = runCatching { URI(serverUrl).host }
                .getOrNull()
                ?.lowercase()
                ?: serverUrl
                    .removePrefix("wss://").removePrefix("ws://")
                    .removePrefix("https://").removePrefix("http://")
                    .substringBefore("/")
                    .substringBefore(":")
                    .lowercase()
            val tunnelSuffixes = listOf(
                ".ngrok-free.app", ".ngrok-free.dev", ".ngrok.io", ".ngrok.dev",
                ".trycloudflare.com", ".workers.dev",
            )
            val isLoopbackHost = host == "localhost" ||
                host == "::1" ||
                host == "[::1]" ||
                host == "0.0.0.0" ||
                host.startsWith("127.")
            return !isLoopbackHost && tunnelSuffixes.none { host.endsWith(it) }
        }
    }
}
