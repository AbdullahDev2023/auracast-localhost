package com.akdevelopers.auracast

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.akdevelopers.auracast.analytics.Analytics
import com.akdevelopers.auracast.core.AppConstants
import com.akdevelopers.auracast.core.AppFeature
import com.akdevelopers.auracast.di.AppGraph
import com.akdevelopers.auracast.di.AppGraphProvider
import com.akdevelopers.auracast.di.AuraCastAppGraph
import com.akdevelopers.auracast.domain.streaming.StreamRuntimeStore
import com.akdevelopers.auracast.remote.FirebaseRemoteController
import com.akdevelopers.auracast.service.StreamIdentity

/**
 * AuraCastApp — Application entry point.
 *
 * Responsibilities:
 *  1. Build the application [AppGraph] composition root.
 *  2. Register pluggable [AppFeature] modules via [registerFeatures].
 *  3. Initialise Firebase Analytics / Crashlytics user properties.
 *  4. Install the process-lifetime Firebase command listener that handles
 *     "start" commands when [StreamingService] is stopped, and acknowledges
 *     every remote command so the browser dashboard shows ✓.
 *
 * ── Adding a new feature ─────────────────────────────────────────────────────
 * Implement [AppFeature] in a new sub-package and register it in [registerFeatures]:
 * ```kotlin
 * appGraph.featureRegistry.register(RecordingFeature())
 * ```
 */
class AuraCastApp : Application(), AppGraphProvider {

    companion object {
        private const val TAG = "AuraCastApp"
    }

    override val appGraph: AppGraph by lazy { AuraCastAppGraph(this) }

    override fun onCreate() {
        super.onCreate()

        // 1. Register pluggable feature modules
        registerFeatures()

        // 2. Firebase telemetry
        Analytics.initUserProperties(this)
        if (BuildConfig.DEBUG) {
            Analytics.enableDebugMode(this)
            Log.i(TAG, "Firebase DebugView activated for DEBUG build ✓")
        }

        // 3. App-level Firebase command listener (bridges service-off gap)
        initAppLevelCommandListener()
    }

    /**
     * Register all [AppFeature] modules here. Each call is a one-liner;
     * the feature is initialised automatically.
     *
     * ```kotlin
     * appGraph.featureRegistry.register(RecordingFeature())
     * appGraph.featureRegistry.register(ScreenCastFeature())
     * ```
     */
    private fun registerFeatures() {
        // Built-in features are wired directly in onCreate — no AppFeature needed.
        // Add future optional feature modules here as the app grows.
    }

    // ── App-level Firebase command listener ───────────────────────────────────

    /**
     * Process-lifetime listener on /users/{streamId}/control.
     *
     * Ensures every remote command gets `processed: true` written to Firebase
     * even when [StreamingService] is not running. Also handles "start" when
     * the service is down by launching it directly.
     *
     * Dedup strategy: commandId-first (same as [com.akdevelopers.auracast.service.CommandProcessor]);
     * falls back to timestamp guard for schema versions without commandId.
     */
    private fun initAppLevelCommandListener() {
        val streamId   = StreamIdentity.getStreamId(this)
        val controlRef = FirebaseDatabase
            .getInstance(AppConstants.FIREBASE_DB_URL)
            .getReference("${AppConstants.FIREBASE_PATH_USERS}/$streamId/${AppConstants.FIREBASE_PATH_CONTROL}")

        controlRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cmd       = snapshot.child("command").getValue(String::class.java) ?: return
                val ts        = snapshot.child("ts").getValue(Long::class.java)        ?: return
                val commandId = snapshot.child("commandId").getValue(String::class.java)
                    ?: "app-$ts"  // backward-compat fallback

                val prefs     = getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)

                // Primary dedup: commandId (matches CommandProcessor behaviour)
                val lastCommandId = prefs.getString(AppConstants.PREF_LAST_COMMAND_ID, null)
                if (commandId == lastCommandId) {
                    Log.d(TAG, "App listener: duplicate commandId=${commandId.take(8)} — skipping")
                    return
                }
                // Secondary dedup: timestamp (guards against missing commandId in old schema)
                val lastTs = prefs.getLong(AppConstants.PREF_APP_CMD_LAST_TS, 0L)
                if (ts <= lastTs) {
                    Log.d(TAG, "App listener: stale ts=$ts — skipping")
                    return
                }

                prefs.edit()
                    .putString(AppConstants.PREF_LAST_COMMAND_ID, commandId)
                    .putLong(AppConstants.PREF_APP_CMD_LAST_TS, ts)
                    .apply()

                Log.i(TAG, "App listener: cmd='$cmd' id=${commandId.take(8)} serviceRunning=${StreamRuntimeStore.isRunning.value}")

                // Always acknowledge so the browser dashboard shows ✓
                controlRef.child("processed").setValue(true)
                controlRef.child("processedAt").setValue(System.currentTimeMillis())

                if (cmd == "start") {
                    if (StreamRuntimeStore.isRunning.value) {
                        Log.d(TAG, "App listener: start — service already running, skipping")
                        return
                    }
                    val url = prefs.getString(AppConstants.PREF_SERVER_URL, AppConstants.DEFAULT_SERVER_URL)
                        ?: AppConstants.DEFAULT_SERVER_URL
                    if (url.isBlank()) {
                        Log.w(TAG, "App listener: start — no server URL configured")
                        return
                    }
                    Log.i(TAG, "App listener: ▶ launching StreamingService  url=$url")
                    appGraph.streamServiceLauncher.ensureServiceRunning(this@AuraCastApp, url)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "App listener cancelled: ${error.message}")
            }
        })

        Log.i(TAG, "App-level command listener ready  streamId=${streamId.take(8)}")
    }

    override fun onTerminate() {
        appGraph.featureRegistry.tearDownAll()
        super.onTerminate()
    }
}
