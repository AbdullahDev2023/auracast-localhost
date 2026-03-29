package com.akdevelopers.auracast.system
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.akdevelopers.auracast.analytics.Analytics

/**
 * Monitors network connectivity. When a usable network appears (after a drop
 * or when switching WiFi <-> Mobile Data), notifies the StreamingService so
 * the WebSocket can reconnect immediately instead of waiting for backoff.
 *
 * FIX (2026-03-11): Added a 3 s startup grace period.
 * ConnectivityManager fires onAvailable() + onCapabilitiesChanged() immediately
 * on registration when the network is already up. This caused 2–4 spurious
 * reconnectNow() calls before the initial connection could complete its TLS
 * handshake. Callbacks within GRACE_MS of register() are now silently ignored.
 *
 * FIX (2026-03-24): Added a global debounce (DEBOUNCE_MS) across ALL network
 * callbacks. When the phone reconnects to WiFi after a long disconnect, Android
 * fires onAvailable + onCapabilitiesChanged dozens of times in rapid succession
 * (each capability transition fires separately). Without this debounce, every
 * callback triggers reconnectNow(), creating a "connection storm" on the server
 * where 30–60 sockets are opened and replaced within a few seconds. The debounce
 * ensures at most one reconnect attempt per DEBOUNCE_MS window.
 */
class NetworkChangeReceiver(
    private val context: Context,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: () -> Unit
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** Timestamp set in register(). Callbacks within GRACE_MS are suppressed. */
    @Volatile private var registeredAt = 0L
    private val GRACE_MS = 3_000L

    /**
     * Global debounce window. After one reconnect callback fires, subsequent
     * callbacks within this window are suppressed. This prevents the storm of
     * reconnectNow() calls that occurs when the phone reconnects to a network
     * and Android emits many capability-change events in rapid succession.
     */
    @Volatile private var lastCallbackMs = 0L
    private val DEBOUNCE_MS = 5_000L

    private fun shouldSuppress(): Boolean {
        val now = System.currentTimeMillis()
        if (now - registeredAt < GRACE_MS) return true
        if (now - lastCallbackMs < DEBOUNCE_MS) return true
        lastCallbackMs = now
        return false
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (shouldSuppress()) return
            Analytics.logNetworkAvailable()
            onNetworkAvailable()
        }
        override fun onLost(network: Network) {
            Analytics.logNetworkLost()
            onNetworkLost()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
            if (shouldSuppress()) return
            Analytics.logNetworkAvailable()
            onNetworkAvailable()
        }
    }

    fun register() {
        registeredAt = System.currentTimeMillis()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try { cm.registerNetworkCallback(request, callback) } catch (_: Exception) {}
    }

    fun unregister() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }
}
