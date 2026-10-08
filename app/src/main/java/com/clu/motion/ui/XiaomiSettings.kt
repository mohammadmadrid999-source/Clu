package com.clu.motion.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * Deep links into Xiaomi's own settings screens (MIUI / HyperOS, including POCO and Redmi).
 *
 * None of these are public APIs: the component names come from open-source projects that use them
 * in production (Key Mapper, AutoStarter, FCMGuard for HyperOS 3, QuickBall, XXPermissions), and
 * Xiaomi renames or removes them between releases. Every link therefore tries its candidates in
 * order and ends at the standard App info page, and the one that worked is remembered for the
 * device report. Launching another app's exported activity by explicit component needs no
 * <queries> entry; we never call resolveActivity, so none is declared.
 */
object XiaomiSettings {

    /** POCO and Redmi phones report MANUFACTURER "Xiaomi"; the brand check covers odd ROMs. */
    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.lowercase() in setOf("xiaomi", "poco", "redmi")

    /** Per-app Battery saver, where "No restrictions" keeps Clu from being frozen or killed. */
    fun openBatterySaver(context: Context): String {
        val pkg = context.packageName
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        return launchFirst(
            context,
            KEY_BATTERY,
            // HyperOS 3: per-app power detail with the battery-saver selector.
            "PowerDetailActivity" to Intent().setComponent(ComponentName(SECURITY_CENTER, "com.miui.powercenter.legacypowerrank.PowerDetailActivity"))
                .setData("package:$pkg".toUri())
                .putExtra("package_name", pkg)
                .putExtra("uid", Process.myUid())
                .putExtra("UserId", Process.myUid() / PER_USER_RANGE),
            // MIUI 12–14 / early HyperOS.
            "HiddenAppsConfigActivity" to Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", pkg)
                .putExtra("package_label", label),
        )
    }

    /** Background autostart list; lets the system bring Clu back (e.g. after a reboot). */
    fun openAutostart(context: Context): String = launchFirst(
        context,
        KEY_AUTOSTART,
        "OP_AUTO_START" to Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        "AutoStartManagementActivity" to Intent().setComponent(
            ComponentName(SECURITY_CENTER, "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
    )

    /** Which screen each link opened last time, for the device report. */
    fun outcomes(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return listOf(KEY_BATTERY, KEY_AUTOSTART).associateWith { prefs.getString(it, "not tried") ?: "not tried" }
    }

    private fun launchFirst(context: Context, key: String, vararg candidates: Pair<String, Intent>): String {
        for ((name, intent) in candidates) {
            if (start(context, intent)) return remember(context, key, name)
        }
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        start(context, appInfo)
        return remember(context, key, "App info (fallback)")
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "Not on this ROM: ${intent.component ?: intent.action}")
        false
    } catch (e: SecurityException) {
        Log.i(TAG, "Not exported on this ROM: ${intent.component ?: intent.action}")
        false
    }

    private fun remember(context: Context, key: String, outcome: String): String {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(key, outcome) }
        return outcome
    }

    private const val TAG = "XiaomiSettings"
    private const val PREFS = "xiaomi_settings"
    private const val KEY_BATTERY = "battery_saver"
    private const val KEY_AUTOSTART = "autostart"
    private const val SECURITY_CENTER = "com.miui.securitycenter"
    private const val PER_USER_RANGE = 100_000
}
