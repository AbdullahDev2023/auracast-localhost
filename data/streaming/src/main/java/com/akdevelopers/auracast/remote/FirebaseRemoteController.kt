package com.akdevelopers.auracast.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.akdevelopers.auracast.analytics.Analytics
import com.akdevelopers.auracast.core.AppConstants
import com.akdevelopers.auracast.service.StreamIdentity
import com.akdevelopers.auracast.service.StreamStatus

/**
 * Firebase Realtime Database remote controller — multi-user edition.
 *
 * All paths are scoped to /users/{streamId}/ so each device has its own
 * isolated control lane, status reporting, and realtime metrics.
 *
 * Database layout:
 *   /auracast_config/serverUrl     — shared config (all users read this)
 *   /users/{streamId}/control      — commands for this device
 *   /users/{streamId}/status       — this device's live stream status
 *   /users/{streamId}/realtime     — this device's audio metrics
 *   /streams/{streamId}            — global registry (for dashboard)
 */
class FirebaseRemoteController(private val context: Context) {

    companion object {
        private const val TAG = "AC_Firebase"

        /** Convenience alias so other classes can reference the DB URL in one place. */
        const val DB_URL = AppConstants.FIREBASE_DB_URL
    }

    /**
     * Single unified callback — routes to [com.akdevelopers.auracast.service.CommandProcessor]
     * for deduplication. Called with (commandId, action, url).
     */
    var onCommandReceived: ((commandId: String, action: String, url: String) -> Unit)? = null

    // ── Per-device scoped paths ───────────────────────────────────────────────
    private val streamId    = StreamIdentity.getStreamId(context)
    private val prefs       = context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
    private val db          = FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL)

    private val controlRef    = db.getReference("${AppConstants.FIREBASE_PATH_USERS}/$streamId/${AppConstants.FIREBASE_PATH_CONTROL}")
    private val statusRef     = db.getReference("${AppConstants.FIREBASE_PATH_USERS}/$streamId/${AppConstants.FIREBASE_PATH_STATUS}")
    private val realtimeRef   = db.getReference("${AppConstants.FIREBASE_PATH_USERS}/$streamId/${AppConstants.FIREBASE_PATH_REALTIME}")
    private val configRef     = db.getReference(AppConstants.FIREBASE_PATH_AURACAST_CFG)
    private val streamsDirRef = db.getReference("${AppConstants.FIREBASE_PATH_STREAMS}/$streamId")

    @Suppress("unused") // kept for potential future ChildEventListener usage
    private var childListener: ChildEventListener? = null
    private var listener: ValueEventListener? = null

    // ── Config fetch ──────────────────────────────────────────────────────────

    /**
     * One-shot fetch of the shared server URL from Firebase RTDB.
     * Times out after [AppConstants.FIREBASE_FETCH_TIMEOUT_MS].
     */
    fun fetchServerUrl(onSuccess: (String) -> Unit, onFailure: (() -> Unit)? = null) {
        val mainHandler = Handler(Looper.getMainLooper())
        var settled = false

        val timeoutRunnable = Runnable {
            if (!settled) {
                settled = true
                Log.e(TAG, "fetchServerUrl: TIMEOUT — Firebase never responded")
                onFailure?.invoke()
            }
        }
        mainHandler.postDelayed(timeoutRunnable, AppConstants.FIREBASE_FETCH_TIMEOUT_MS)
        Log.d(TAG, "fetchServerUrl: listening…  streamId=${streamId.take(8)}")

        configRef.child(AppConstants.FIREBASE_PATH_SERVER_URL)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (settled) return
                    settled = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    val url = snapshot.getValue(String::class.java)
                    if (!url.isNullOrBlank()) { Log.i(TAG, "fetchServerUrl → $url"); onSuccess(url) }
                    else { Log.w(TAG, "fetchServerUrl: empty value"); onFailure?.invoke() }
                }
                override fun onCancelled(error: DatabaseError) {
                    if (settled) return
                    settled = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Log.e(TAG, "fetchServerUrl cancelled: ${error.message}")
                    onFailure?.invoke()
                }
            })
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        publishToStreamDirectory()

        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val ts     = snapshot.child("ts").getValue(Long::class.java) ?: return
                val lastTs = prefs.getLong(AppConstants.PREF_FIREBASE_LAST_TS, 0L)
                if (ts <= lastTs) {
                    Log.d(TAG, "Stale command skipped (ts=$ts <= lastTs=$lastTs)")
                    return
                }
                prefs.edit().putLong(AppConstants.PREF_FIREBASE_LAST_TS, ts).apply()

                controlRef.child("processed").setValue(true)
                controlRef.child("processedAt").setValue(System.currentTimeMillis())

                val cmd = snapshot.child("command").getValue(String::class.java)
                    ?: run { Log.w(TAG, "No 'command' field in snapshot"); return }
                val url       = snapshot.child("url").getValue(String::class.java) ?: ""
                val commandId = snapshot.child("commandId").getValue(String::class.java)
                    ?: "firebase-$ts"   // backward-compat fallback

                Log.i(TAG, "▶ cmd='$cmd' id=${commandId.take(8)} ts=$ts  streamId=${streamId.take(8)}")
                Analytics.logFirebaseCommand(cmd)
                onCommandReceived?.invoke(commandId, cmd, url)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listener cancelled: ${error.message}")
            }
        }
        listener = l
        controlRef.addValueEventListener(l)
        Log.i(TAG, "Started — listening on /users/${streamId.take(8)}…/control")
    }

    fun stop() {
        listener?.let { controlRef.removeEventListener(it) }
        listener = null
        streamsDirRef.removeValue()
        Log.i(TAG, "Stopped")
    }

    // ── Status reporting ──────────────────────────────────────────────────────

    fun pushStatus(streamStatus: StreamStatus, serverUrl: String) {
        statusRef.setValue(mapOf(
            "status"    to streamStatus.name,
            "serverUrl" to serverUrl,
            "updatedAt" to System.currentTimeMillis()
        ))
    }

    fun pushRealtimeMetrics(framesPerSec: Float, kbps: Float, uptimeSec: Int, quality: String) {
        realtimeRef.setValue(mapOf(
            "framesPerSec" to framesPerSec,
            "kbps"         to kbps,
            "uptimeSec"    to uptimeSec,
            "quality"      to quality,
            "updatedAt"    to System.currentTimeMillis()
        ))
    }

    /** Writes this device to /streams/{streamId} so the web dashboard can list it. */
    private fun publishToStreamDirectory() {
        streamsDirRef.setValue(mapOf(
            "displayName" to StreamIdentity.getDisplayName(context),
            "streamId"    to streamId,
            "updatedAt"   to System.currentTimeMillis()
        ))
    }
}
