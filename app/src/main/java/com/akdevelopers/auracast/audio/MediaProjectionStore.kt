package com.akdevelopers.auracast.audio

import android.media.projection.MediaProjection
import android.util.Log

/**
 * Process-scoped holder for the MediaProjection token granted by the user.
 *
 * Lifecycle:
 *  - Token is set from MainActivity when the user approves the system prompt.
 *  - It is automatically cleared when the OS revokes it (onStop callback).
 *  - StreamingService.stopAll() calls clear() on teardown.
 *
 * Thread-safety: all access is via @Volatile + synchronized(lock) on writes.
 */
object MediaProjectionStore {

    private const val TAG = "AC_MPStore"
    private val lock = Any()

    @Volatile
    var projection: MediaProjection? = null
        private set

    fun set(mp: MediaProjection) {
        synchronized(lock) {
            projection?.stop()
            projection = mp
            Log.i(TAG, "MediaProjection token stored ✓")
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                synchronized(lock) {
                    if (projection === mp) {
                        projection = null
                        Log.i(TAG, "MediaProjection token revoked by OS")
                    }
                }
            }
        }, null)
    }

    fun clear() {
        synchronized(lock) {
            projection?.stop()
            projection = null
            Log.i(TAG, "MediaProjection token cleared")
        }
    }

    val isAvailable: Boolean get() = projection != null
}
