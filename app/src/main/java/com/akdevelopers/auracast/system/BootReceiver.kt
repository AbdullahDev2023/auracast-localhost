package com.akdevelopers.auracast.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.akdevelopers.auracast.analytics.Analytics
import com.akdevelopers.auracast.core.AppConstants
import com.akdevelopers.auracast.service.StreamingService

/**
 * Restarts the streaming service after device reboot or app update — but only
 * if the user had previously started streaming ([AppConstants.PREF_AUTO_RESTART] == true).
 *
 * Handles:
 *  - ACTION_BOOT_COMPLETED        — device rebooted
 *  - ACTION_LOCKED_BOOT_COMPLETED — fires before first unlock (Direct Boot)
 *  - ACTION_MY_PACKAGE_REPLACED   — app was updated (service killed during install)
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AC_BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        if (intent.action !in validActions) {
            Log.d(TAG, "onReceive: ignoring unrelated action=${intent.action}")
            return
        }

        // LOCKED_BOOT_COMPLETED fires before decryption — only Device Protected Storage
        // is available at that point. Use credential-protected storage for all other cases.
        val prefs = if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
        } else {
            context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
        }

        // Default false — don't auto-start on a fresh install where the user has
        // never explicitly started streaming.
        val autoRestart = prefs.getBoolean(AppConstants.PREF_AUTO_RESTART, false)
        val url         = prefs.getString(AppConstants.PREF_SERVER_URL, null)

        Log.i(TAG, "onReceive: action=${intent.action}  autoRestart=$autoRestart  url=${url?.take(40)}")

        when {
            !autoRestart      -> Log.i(TAG, "auto_restart=false — user stopped manually, skipping.")
            url.isNullOrBlank() -> Log.w(TAG, "No server_url saved — cannot restart.")
            else -> {
                Log.i(TAG, "Starting foreground service → $url")
                Analytics.logBootRestart()
                context.startForegroundService(StreamingService.buildStartIntent(context, url))
                // AlarmManager alarms are wiped on reboot — re-arm the watchdog chain.
                WatchdogWorker.scheduleExactAlarm(context)
            }
        }
    }
}
