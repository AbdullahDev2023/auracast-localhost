package com.akdevelopers.auracast.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.akdevelopers.auracast.core.AppConstants
import java.util.UUID

/**
 * Generates and persists a permanent per-device stream identity (UUID).
 *
 * The UUID is created once on first launch and reused forever.
 * It is appended to every WebSocket connection URL (?id=<uuid>) so the
 * server can route audio to the correct isolated channel.
 *
 * SharedPreferences key: [AppConstants.PREF_STREAM_ID] (in [AppConstants.PREFS_FILE])
 */
object StreamIdentity {

    private const val TAG = "AC_Identity"

    /**
     * Returns the permanent stream UUID for this device.
     * Creates and persists a new UUID if one does not exist yet.
     */
    fun getStreamId(context: Context): String {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getString(AppConstants.PREF_STREAM_ID, null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(AppConstants.PREF_STREAM_ID, newId).apply()
            Log.i(TAG, "Generated new streamId: $newId")
            newId
        }
    }

    /**
     * Returns a human-readable label for this stream (e.g. "samsung Galaxy S23").
     * Used as the display name in the server dashboard.
     * Defaults to device manufacturer + model. Truncated to 30 chars.
     */
    fun getDisplayName(context: Context): String {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getString(AppConstants.PREF_DISPLAY_NAME, null)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(30)
    }

    /**
     * Overrides the display name shown in the server dashboard.
     * Optional — the auto-generated device name is used by default.
     */
    fun setDisplayName(context: Context, name: String) {
        context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putString(AppConstants.PREF_DISPLAY_NAME, name.trim().take(30)).apply()
    }

    /**
     * Appends `?id=<streamId>&name=<displayName>` to a WebSocket URL.
     * Handles URLs that already have a query string (uses `&` instead of `?`).
     */
    fun appendToUrl(context: Context, rawUrl: String): String {
        val id   = getStreamId(context)
        val name = android.net.Uri.encode(getDisplayName(context))
        val sep  = if ('?' in rawUrl) '&' else '?'
        return "${rawUrl}${sep}id=${id}&name=${name}"
    }
}
