package com.akdevelopers.auracast.system
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the exact AlarmManager alarm fired by WatchdogWorker.scheduleExactAlarm().
 * Checks whether the service is alive/connected and restarts it if not.
 * Re-arms the next alarm so the chain continues even if WorkManager is deferred.
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "AlarmManager watchdog fired")
        WatchdogWorker.checkAndRestart(context)
        // Re-arm: each alarm is one-shot, so schedule the next one immediately
        WatchdogWorker.scheduleExactAlarm(context)
    }

    companion object { private const val TAG = "AC_WatchdogAlarm" }
}
