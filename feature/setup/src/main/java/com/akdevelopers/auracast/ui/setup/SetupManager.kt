package com.akdevelopers.auracast.ui.setup
import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Tracks completion of mandatory one-time setup steps.
 * All state lives in SharedPreferences "auracast_setup".
 */
object SetupManager {

    private const val PREFS = "auracast_setup"
    private const val KEY_AUTOSTART_CONFIRMED     = "autostart_confirmed"
    /**
     * Set to true once the user has seen the MediaProjection permission rationale
     * dialog (regardless of whether they approved or dismissed).  We only show the
     * prompt once per installation so we never nag the user.
     */
    private const val KEY_MEDIA_PROJECTION_PROMPTED = "media_projection_prompted"

    // ── Step checks ───────────────────────────────────────────────────────────

    fun isAutostartConfirmed(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOSTART_CONFIRMED, false)

    fun markAutostartConfirmed(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOSTART_CONFIRMED, true).apply()
    }

    /** True if the OS is ignoring battery optimizations for this app. */
    fun isBatteryExempt(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(ctx.packageName)

    /**
     * True if this OEM device needs manual autostart setup AND
     * the user hasn't confirmed it yet.
     */
    fun autostartStepRequired(ctx: Context): Boolean =
        OemAutostartHelper.needsAutostartSetup() && !isAutostartConfirmed(ctx)

    /** All mandatory setup is complete — safe to show main UI. */
    fun isComplete(ctx: Context, allPermsGranted: Boolean): Boolean =
        allPermsGranted && isBatteryExempt(ctx) && !autostartStepRequired(ctx)

    /** Open the system battery optimisation exemption dialog. */
    @SuppressLint("BatteryLife")
    fun requestBatteryExemption(ctx: Activity) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                }
            )
        }
    }

    // ── MediaProjection prompt tracking ───────────────────────────────────────

    /**
     * True if the user has already seen the one-time MediaProjection rationale
     * prompt (they may have approved or dismissed — we record either outcome).
     */
    fun isMediaProjectionPrompted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEDIA_PROJECTION_PROMPTED, false)

    /** Mark that the prompt has been shown (call regardless of outcome). */
    fun markMediaProjectionPrompted(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MEDIA_PROJECTION_PROMPTED, true).apply()
    }
}
