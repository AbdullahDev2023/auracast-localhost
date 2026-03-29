package com.akdevelopers.auracast.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import com.akdevelopers.auracast.analytics.Analytics
import com.akdevelopers.auracast.core.AppConstants
import com.akdevelopers.auracast.core.BaseViewModel
import com.akdevelopers.auracast.di.AppGraphProvider
import com.akdevelopers.auracast.domain.streaming.StreamRuntimeStore
import com.akdevelopers.auracast.remote.FirebaseRemoteController

/**
 * MainViewModel — drives the streaming UI in [MainActivity].
 *
 * Extends [BaseViewModel] so the app-scoped SharedPreferences are available
 * via [prefs] without boilerplate. All prefs keys are referenced via
 * [AppConstants] so typos are caught at compile time.
 */
class MainViewModel(app: Application) : BaseViewModel(app) {

    companion object {
        private const val TAG = "MainViewModel"

        /**
         * Exposed so [com.akdevelopers.auracast.AuraCastApp] can reference the default
         * URL in one place. Delegates to [AppConstants.DEFAULT_SERVER_URL].
         */
        const val DEFAULT_SERVER_URL = AppConstants.DEFAULT_SERVER_URL
    }

    private val appGraph = (app as AppGraphProvider).appGraph

    val status    = StreamRuntimeStore.status.asLiveData()
    val isRunning = StreamRuntimeStore.isRunning.asLiveData()

    private val _fetchStatusMessage = MutableStateFlow<String?>(null)
    val fetchStatusMessage: StateFlow<String?> = _fetchStatusMessage

    // Guard against concurrent Firebase fetches from rapid double-taps
    private val isFetching = AtomicBoolean(false)

    var serverUrl: String
        get() = prefs.getString(AppConstants.PREF_SERVER_URL, AppConstants.DEFAULT_SERVER_URL)
            ?: AppConstants.DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(AppConstants.PREF_SERVER_URL, value).apply()

    var autoRestart: Boolean
        get() = prefs.getBoolean(AppConstants.PREF_AUTO_RESTART, true)
        set(value) = prefs.edit().putBoolean(AppConstants.PREF_AUTO_RESTART, value).apply()

    /** Ensure the always-on service is running (WS connected, mic off). */
    fun ensureServiceRunning(context: Context) {
        if (isRunning.value == true) return
        val url = serverUrl
        if (url.isBlank() || !isUrlValid()) return
        autoRestart = true
        appGraph.streamServiceLauncher.ensureServiceRunning(context.applicationContext, url)
    }

    /** Tell the service to open the mic and start sending frames. */
    fun startMic(context: Context) {
        ensureServiceRunning(context)
        appGraph.streamServiceLauncher.startMic(context)
    }

    /** Tell the service to close the mic — WS stays open. */
    fun stopMic(context: Context) {
        appGraph.streamServiceLauncher.stopMic(context)
    }

    /** Hard kill — destroys service and WS. User explicitly chose to exit. */
    fun killService(context: Context) {
        autoRestart = false
        appGraph.streamServiceLauncher.stopFull(context)
    }

    /**
     * Fetches the server URL from Firebase RTDB (with 5 s timeout),
     * falls back to the saved prefs URL, then starts the service.
     */
    fun fetchUrlAndAutoConnect(context: Context) {
        if (isRunning.value == true) return
        // Prevent concurrent fetches from rapid taps or onCreate + button-tap race
        if (!isFetching.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        _fetchStatusMessage.value = "🔄 Fetching server URL from Firebase…"
        FirebaseRemoteController(appCtx).fetchServerUrl(
            onSuccess = { url ->
                isFetching.set(false)
                serverUrl = url
                _fetchStatusMessage.value = "✅ Connected via Firebase"
                Analytics.logFirebaseUrlFetched("firebase")
                ensureServiceRunning(appCtx)
            },
            onFailure = {
                isFetching.set(false)
                if (isUrlValid()) {
                    _fetchStatusMessage.value = "⚠️ Using saved URL"
                    Analytics.logFirebaseUrlFetched("prefs")
                    ensureServiceRunning(appCtx)
                } else {
                    _fetchStatusMessage.value = "❌ No URL configured"
                    Analytics.logFirebaseUrlFetched("none")
                    Log.w(TAG, "fetchUrlAndAutoConnect: no valid URL available")
                }
            }
        )
    }

    fun clearFetchStatus() { _fetchStatusMessage.value = null }

    fun isUrlValid(): Boolean =
        serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://")
}
