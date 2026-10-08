package com.clu.motion.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.clu.motion.engine.MotionEngine
import com.clu.motion.input.InputDispatcherService

/**
 * Plain-text diagnostics the user can paste into a bug report or chat: device, display, sensors,
 * the OS settings that commonly stop accessibility apps, and the injection test results.
 * Contains no personal data (no accounts, no location, no installed-app list).
 */
object DeviceReport {

    fun build(activity: Activity, engine: MotionEngine): String = buildString {
        val pm = activity.packageManager
        val info = pm.getPackageInfo(activity.packageName, 0)
        appendLine("== Clu device report ==")
        appendLine("Clu ${info.versionName} (${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else 0})")

        appendLine("\n-- Device --")
        appendLine("${Build.MANUFACTURER} / ${Build.BRAND} / ${Build.MODEL} (device=${Build.DEVICE}, product=${Build.PRODUCT})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Build: ${Build.DISPLAY} / ${Build.VERSION.INCREMENTAL}")

        appendLine("\n-- Display --")
        val wm = activity.windowManager
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p.x to p.y
        }
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else null
        @Suppress("DEPRECATION")
        val d = display ?: wm.defaultDisplay
        appendLine("${w}x$h px, ${activity.resources.displayMetrics.densityDpi} dpi, rotation ${d.rotation}")
        appendLine("Refresh now %.1f Hz; modes: %s".format(d.refreshRate, d.supportedModes.map { "%.0f".format(it.refreshRate) }.distinct().joinToString()))

        appendLine("\n-- Sensors --")
        val sm = activity.getSystemService(SensorManager::class.java)
        for ((type, name) in SENSORS) {
            val s = sm.getDefaultSensor(type)
            if (s == null) {
                appendLine("$name: MISSING")
            } else {
                val maxHz = if (s.minDelay > 0) "%.0f Hz max".format(1e6 / s.minDelay) else "on-change"
                appendLine("$name: ${s.name} [${s.vendor} v${s.version}] $maxHz, fifo ${s.fifoMaxEventCount}")
            }
        }
        val status = engine.sensorStatus.value
        appendLine("Clu source: ${status.source ?: "not running"}, reliable=${status.reliable}, measured %.1f Hz".format(status.sampleRateHz))

        appendLine("\n-- System settings that affect Clu --")
        appendLine("Accessibility service enabled=${InputDispatcherService.isEnabled(activity)} connected=${InputDispatcherService.connected.value}")
        val power = activity.getSystemService(PowerManager::class.java)
        appendLine("Ignoring battery optimizations=${power.isIgnoringBatteryOptimizations(activity.packageName)}")
        appendLine("Background restricted=${activity.getSystemService(ActivityManager::class.java).isBackgroundRestricted}")
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(activity, Manifest.permission.POST_NOTIFICATIONS).toString()
        } else {
            "n/a"
        }
        appendLine("Notifications granted=$notifications")
        appendLine("Microphone granted=${granted(activity, Manifest.permission.RECORD_AUDIO)}")

        appendLine("\n-- Session --")
        val profile = engine.activeProfile.value
        appendLine("State ${engine.session.value}, profile '${profile.name}' (${profile.id})")
        val j = profile.joystick
        appendLine("Joystick ${j.mode} at (%.2f, %.2f) r=%.2f, segment ${j.segmentMs} ms, inFlight ${j.maxInFlight}".format(j.centerX, j.centerY, j.radiusFraction))
        appendLine("Filter ${profile.filter.type} minCutoff ${profile.filter.minCutoffHz} beta ${profile.filter.beta}; axes ${profile.axes.mode}")

        appendLine("\n-- Injection test --")
        val s = engine.injectionStats.snapshot()
        appendLine("Gestures sent ${s.dispatched}, completed ${s.completed}, cancelled ${s.cancelled}, rejected ${s.rejected}, back-pressure waits ${s.backpressureSkips}")
        appendLine("Received: DOWN ${s.downs}, UP ${s.ups}, CANCEL ${s.cancels}, POINTER_DOWN ${s.pointerDowns}, POINTER_UP ${s.pointerUps}, MOVE ${s.moves}, max pointers ${s.maxPointers}")
        appendLine("MOVE gap: ${s.moveGap ?: "-"}")
        appendLine("Frame age at dispatch: ${s.frameAge ?: "-"}")
        appendLine("Dispatch -> delivered: ${s.dispatchToDelivery ?: "-"}")
        appendLine("Event age at app: ${s.eventAge ?: "-"}")
        for ((sig, count) in s.signatures) appendLine("Events $sig: $count")
    }

    private fun granted(activity: Activity, permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private val SENSORS = listOf(
        Sensor.TYPE_GAME_ROTATION_VECTOR to "Game rotation vector",
        Sensor.TYPE_ROTATION_VECTOR to "Rotation vector",
        Sensor.TYPE_GRAVITY to "Gravity",
        Sensor.TYPE_GYROSCOPE to "Gyroscope",
        Sensor.TYPE_ACCELEROMETER to "Accelerometer",
        Sensor.TYPE_MAGNETIC_FIELD to "Magnetometer",
    )
}
