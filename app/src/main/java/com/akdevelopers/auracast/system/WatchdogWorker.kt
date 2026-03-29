package com.akdevelopers.auracast.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.akdevelopers.auracast.core.AppConstants
import com.akdevelopers.auracast.domain.streaming.StreamRuntimeStore
import com.akdevelopers.auracast.service.StreamingService
import java.util.concurrent.TimeUnit

/**
 * Two-layer watchdog that keeps the streaming service alive on OEM-aggressive devices.
 *
 * Layer 1 — WorkManager (every 15 min, survives process kill)
 * Layer 2 — AlarmManager SCHEDULE_EXACT_ALARM (every 15 min, fires precisely)
 *
 * WorkManager alone can be deferred 10+ minutes by the OS scheduler. The exact
 * alarm wakes the device at wall-clock time, acting as a reliable secondary layer.
 *
 * Both layers run [checkAndRestart]: service dead or WS disconnected → restart it.
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        Log.d(TAG, "WorkManager watchdog fired")
        checkAndRestart(applicationContext)
        // Re-arm the exact alarm from here too, in case it was lost after a reboot.
        scheduleExactAlarm(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG       = "AC_Watchdog"
        private const val WORK_NAME = "auracast_watchdog"
        private const val ALARM_RC  = 0xAC_1   // request code — must be unique per PendingIntent

        /** Check service health and restart if needed. Returns true if a restart was triggered. */
        fun checkAndRestart(ctx: Context): Boolean {
            val prefs       = ctx.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
            val autoRestart = prefs.getBoolean(AppConstants.PREF_AUTO_RESTART, false)
            val url         = prefs.getString(AppConstants.PREF_SERVER_URL, null)
            val isRunning   = StreamRuntimeStore.isRunning.value
            val isConnected = StreamRuntimeStore.isConnected.value

            Log.d(TAG, "check: autoRestart=$autoRestart running=$isRunning connected=$isConnected")

            if (!autoRestart || url.isNullOrBlank()) return false

            // Don't act if the service started within the cold-start grace window.
            // WorkManager can fire almost immediately after schedule(), and at that point
            // the WebSocket TLS handshake is still in progress — isConnected = false.
            // Intervening here would abort the handshake (SSLHandshakeException).
            val startEpoch = prefs.getLong(AppConstants.PREF_SERVICE_START_EPOCH, 0L)
            if (startEpoch > 0L &&
                System.currentTimeMillis() - startEpoch < AppConstants.WATCHDOG_COLD_START_GRACE_MS
            ) {
                Log.d(TAG, "check: service started <${AppConstants.WATCHDOG_COLD_START_GRACE_MS / 1000}s ago — skipping")
                return false
            }

            return when {
                !isRunning -> {
                    // Service is completely dead — do a full cold start.
                    Log.i(TAG, "Service dead — restarting")
                    try {
                        ctx.startForegroundService(StreamingService.buildStartIntent(ctx, url))
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "startForegroundService failed: ${e.message}")
                        false
                    }
                }
                !isConnected -> {
                    // Service is alive but WebSocket dropped — reconnect only.
                    // Sending a full buildStartIntent here would trigger onStartCommand
                    // with a null action → orchestrator.start() → disconnects the live
                    // socket mid-handshake. Use the dedicated reconnect action instead.
                    Log.i(TAG, "WS dead — sending ACTION_RECONNECT_TRANSPORT")
                    try {
                        ctx.startForegroundService(StreamingService.buildReconnectIntent(ctx))
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "startForegroundService (reconnect) failed: ${e.message}")
                        false
                    }
                }
                else -> false
            }
        }

        /** Schedule WorkManager periodic work (survives force-stop). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            scheduleExactAlarm(context)
            Log.d(TAG, "Watchdog scheduled (WorkManager + AlarmManager)")
        }

        /** Cancel both layers. Call when the user explicitly exits. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
            cancelExactAlarm(context)
            Log.d(TAG, "Watchdog cancelled")
        }

        fun scheduleExactAlarm(context: Context) {
            val am       = context.getSystemService(AlarmManager::class.java) ?: return
            val pi       = alarmPendingIntent(context) ?: return
            val triggerAt = System.currentTimeMillis() + AppConstants.WATCHDOG_ALARM_INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    // Fallback to inexact — still better than nothing
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.w(TAG, "SCHEDULE_EXACT_ALARM not granted — using inexact alarm")
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.d(TAG, "Exact alarm set for +${AppConstants.WATCHDOG_ALARM_INTERVAL_MS / 60_000} min")
                }
            } catch (e: Exception) {
                Log.e(TAG, "scheduleExactAlarm failed: ${e.message}")
            }
        }

        private fun cancelExactAlarm(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            alarmPendingIntent(context)?.let { am.cancel(it) }
        }

        private fun alarmPendingIntent(context: Context): PendingIntent? =
            PendingIntent.getBroadcast(
                context, ALARM_RC,
                Intent(context, WatchdogAlarmReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
