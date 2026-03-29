package com.akdevelopers.auracast.ui.setup
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * OemAutostartHelper — deep-links to the OEM-specific "Autostart" / "Background launch"
 * permission screen so the user can whitelist AuraCast.
 *
 * Why this is needed:
 *   Stock Android honours BOOT_COMPLETED + START_STICKY automatically.
 *   However every major OEM ships a proprietary background-app manager that silently
 *   blocks BOOT_COMPLETED delivery and kills foreground services unless the user grants
 *   an explicit "Autostart" permission that has no Android API — it lives entirely inside
 *   the OEM's own system app. The only way to reach it is to fire a manufacturer-specific
 *   intent and hope the activity exists on this particular firmware version.
 *
 * Strategy:
 *   For each OEM a prioritised list of (package, class) pairs is tried in order.
 *   The first one whose package is installed AND whose Activity can be resolved is launched.
 *   If nothing resolves, we fall back to the standard App Info page where the user can at
 *   least find Battery > Unrestricted manually.
 */
object OemAutostartHelper {

    private const val TAG = "AC_OemAutostart"
    private const val PREF_KEY = "autostart_prompted"

    /** Returns true if this device is known to need manual autostart setup. */
    fun needsAutostartSetup(): Boolean = resolveOem() != Oem.UNKNOWN

    /** Human-readable OEM brand name for display in setup UI. */
    fun getOemName(): String = when (resolveOem()) {
        Oem.XIAOMI   -> "Xiaomi / MIUI"
        Oem.SAMSUNG  -> "Samsung / One UI"
        Oem.HUAWEI   -> "Huawei / EMUI"
        Oem.HONOR    -> "Honor"
        Oem.ONEPLUS  -> "OnePlus / OxygenOS"
        Oem.OPPO     -> "OPPO / ColorOS"
        Oem.REALME   -> "Realme / ColorOS"
        Oem.VIVO     -> "Vivo / FuntouchOS"
        Oem.ASUS     -> "ASUS / ZenUI"
        Oem.MEIZU    -> "Meizu / Flyme"
        Oem.UNKNOWN  -> Build.MANUFACTURER
    }

    /** True if we have already shown the prompt on this device. */
    fun hasBeenPrompted(context: Context): Boolean =
        context.getSharedPreferences("auracast", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false)

    fun markPrompted(context: Context) {
        context.getSharedPreferences("auracast", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, true).apply()
    }

    /**
     * Attempt to open the OEM autostart screen.
     * Returns true if an intent was successfully fired, false if no match found.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val candidates = candidatesFor(resolveOem())
        for ((pkg, cls) in candidates) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Xiaomi and a few others need the package name as an extra
                putExtra("package_name", context.packageName)
                putExtra("package", context.packageName)
                putExtra("app_package", context.packageName)
                putExtra("app_name", context.packageManager.getApplicationLabel(context.applicationInfo).toString())
            }
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                return try {
                    context.startActivity(intent)
                    Log.i(TAG, "Opened autostart screen: $pkg/$cls")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start $pkg/$cls: ${e.message}")
                    false
                }
            }
        }
        // Nothing matched — open standard App Info as fallback
        return try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            Log.i(TAG, "Fell back to App Info settings")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Even fallback failed: ${e.message}")
            false
        }
    }

    // ── OEM detection ─────────────────────────────────────────────────────────

    private enum class Oem {
        XIAOMI, SAMSUNG, HUAWEI, HONOR, ONEPLUS, OPPO, REALME, VIVO, ASUS, MEIZU, UNKNOWN
    }

    private fun resolveOem(): Oem {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("poco") || manufacturer.contains("xiaomi") -> Oem.XIAOMI
            brand.contains("samsung") || manufacturer.contains("samsung")                          -> Oem.SAMSUNG
            brand.contains("honor")                                                                -> Oem.HONOR
            brand.contains("huawei") || manufacturer.contains("huawei")                           -> Oem.HUAWEI
            brand.contains("oneplus") || manufacturer.contains("oneplus")                         -> Oem.ONEPLUS
            brand.contains("realme")                                                               -> Oem.REALME
            brand.contains("oppo") || manufacturer.contains("oppo")                               -> Oem.OPPO
            brand.contains("vivo") || manufacturer.contains("vivo")                               -> Oem.VIVO
            brand.contains("asus") || manufacturer.contains("asus")                               -> Oem.ASUS
            brand.contains("meizu") || manufacturer.contains("meizu")                             -> Oem.MEIZU
            else                                                                                   -> Oem.UNKNOWN
        }
    }

    // ── Intent candidates per OEM (tried in order, first match wins) ──────────

    private data class Target(val pkg: String, val cls: String)

    private fun candidatesFor(oem: Oem): List<Target> = when (oem) {

        Oem.XIAOMI -> listOf(
            // MIUI 10+ Autostart management
            Target("com.miui.securitycenter",
                   "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // Older MIUI
            Target("com.miui.securitycenter",
                   "com.miui.securitycenter.MainActivity"),
            Target("com.miui.securitycenter",
                   "com.miui.permcenter.MainAcitvity")
        )

        Oem.SAMSUNG -> listOf(
            // One UI 4+ Device Care → Battery → Background usage limits
            Target("com.samsung.android.lool",
                   "com.samsung.android.sm.battery.ui.BatteryActivity"),
            // One UI 3 / older
            Target("com.samsung.android.lool",
                   "com.samsung.android.sm.ui.battery.BatteryActivity"),
            // Samsung Device Health Services
            Target("com.samsung.android.sm_cn",
                   "com.samsung.android.sm.battery.ui.BatteryActivity")
        )

        Oem.HUAWEI -> listOf(
            // EMUI 8+ Startup manager
            Target("com.huawei.systemmanager",
                   "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            // EMUI 5–7
            Target("com.huawei.systemmanager",
                   "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            Target("com.huawei.systemmanager",
                   "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
        )

        Oem.HONOR -> listOf(
            // Honor uses same system manager as Huawei
            Target("com.huawei.systemmanager",
                   "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Target("com.huawei.systemmanager",
                   "com.huawei.systemmanager.optimize.process.ProtectActivity")
        )

        Oem.ONEPLUS -> listOf(
            // OxygenOS Battery Optimization / Auto-launch
            Target("com.oneplus.security",
                   "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            Target("com.android.settings",
                   "com.android.settings.Settings\$AppAndNotificationDashboardActivity")
        )

        Oem.OPPO -> listOf(
            // ColorOS 11+
            Target("com.coloros.safecenter",
                   "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            // ColorOS 7–10
            Target("com.coloros.safecenter",
                   "com.coloros.safecenter.permission.startup.FakeActivity"),
            Target("com.oppo.safe",
                   "com.oppo.safe.permission.startup.StartupAppListActivity"),
            Target("com.coloros.oppoguardelf",
                   "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")
        )

        Oem.REALME -> listOf(
            // RealmeUI (based on ColorOS)
            Target("com.realme.permissionmanager",
                   "com.realme.permissionmanager.ui.PermissionTopActivity"),
            Target("com.coloros.safecenter",
                   "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            Target("com.oppo.safe",
                   "com.oppo.safe.permission.startup.StartupAppListActivity")
        )

        Oem.VIVO -> listOf(
            // FuntouchOS / Origin OS
            Target("com.vivo.permissionmanager",
                   "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Target("com.iqoo.secure",
                   "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            Target("com.iqoo.secure",
                   "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        )

        Oem.ASUS -> listOf(
            // ZenUI / ROG UI
            Target("com.asus.mobilemanager",
                   "com.asus.mobilemanager.autostart.AutoStartActivity"),
            Target("com.asus.mobilemanager",
                   "com.asus.mobilemanager.entry.FunctionActivity"),
            Target("com.asus.mobilemanager.apower",
                   "com.asus.mobilemanager.apower.PowerSaverSettings")
        )

        Oem.MEIZU -> listOf(
            // Flyme OS
            Target("com.meizu.safe",
                   "com.meizu.safe.security.SHOW_APPSEC"),
            Target("com.meizu.safe",
                   "com.meizu.safe.permission.SmartPermissionActivity")
        )

        Oem.UNKNOWN -> emptyList()
    }
}
