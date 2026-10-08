package com.clu.motion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.clu.motion.CluApp
import com.clu.motion.R
import com.clu.motion.input.InputDispatcherService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * On-device injection self-test. A full-screen, immersive, landscape pad stands in for a game:
 * the accessibility service injects into it exactly as it would into a game (this activity is
 * on the injection allow-list), and every received MotionEvent is measured.
 *
 * "Synthetic test" drives a circling virtual stick plus periodic button taps without sensors,
 * which isolates the platform's gesture injection from motion processing. With a normal session
 * running instead, the pad shows what real tilting produces.
 */
class InjectionTestActivity : ComponentActivity() {

    private val engine by lazy { CluApp.engine(this) }
    private lateinit var pad: TouchTestPadView
    private lateinit var status: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Cover the whole display, cutout included, like a game does.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        pad = TouchTestPadView(this, engine.injectionStats)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(4), 0, dp(4), dp(6))
            ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        }
        runButton = button(R.string.test_run) { toggleSynthetic() }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(230, 16, 20, 24))
            }
            addView(status, LinearLayout.LayoutParams(dp(BAR_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                LinearLayout(this@InjectionTestActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(runButton)
                    addView(button(R.string.test_reset) { reset() })
                    addView(button(R.string.test_copy) { copyReport() })
                    addView(button(R.string.test_share) { shareReport() })
                    addView(button(R.string.test_close) { finish() })
                },
            )
        }
        val root = FrameLayout(this).apply {
            addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                bar,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                    .apply { topMargin = dp(8) },
            )
        }
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { engine.activeProfile.collect { pad.setLayout(it, displayW(), displayH()) } }
                launch {
                    while (isActive) {
                        updateStatus()
                        delay(STATUS_INTERVAL_MS)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        engine.injectionStats.enabled = true
        pad.setLayout(engine.activeProfile.value, displayW(), displayH())
    }

    override fun onPause() {
        engine.stopSelfTest()
        engine.injectionStats.enabled = false
        super.onPause()
    }

    private fun toggleSynthetic() {
        if (engine.selfTest.value != null) {
            engine.stopSelfTest()
        } else {
            if (!InputDispatcherService.connected.value) {
                Toast.makeText(this, R.string.test_need_service, Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            pad.clearTrails()
            engine.startSelfTest(SystemClock.uptimeMillis())
        }
        updateStatus()
    }

    private fun reset() {
        engine.injectionStats.reset()
        pad.clearTrails()
        updateStatus()
    }

    private fun updateStatus() {
        val running = engine.selfTest.value != null
        runButton.setText(if (running) R.string.test_stop else R.string.test_run)
        val s = engine.injectionStats.snapshot()
        status.text = when {
            !InputDispatcherService.connected.value -> getString(R.string.test_need_service)
            s.dispatched == 0 && s.moves == 0 -> getString(R.string.test_intro)
            else -> getString(
                R.string.test_summary,
                s.dispatched,
                s.completed,
                s.cancelled + s.rejected,
                s.downs + s.pointerDowns,
                s.ups + s.pointerUps,
                s.cancels,
                s.moves,
                s.moveGap?.p95 ?: 0L,
                s.dispatchToDelivery?.p50 ?: 0L,
                s.maxPointers,
            )
        }
    }

    private fun copyReport() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.test_report_title), DeviceReport.build(this, engine)))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, R.string.test_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.test_report_title))
            .putExtra(Intent.EXTRA_TEXT, DeviceReport.build(this, engine))
        startActivity(Intent.createChooser(send, getString(R.string.test_share)))
    }

    private fun button(@StringRes label: Int, onClick: () -> Unit) = Button(this).apply {
        setText(label)
        isAllCaps = false
        minHeight = dp(48)
        minimumHeight = dp(48)
        setOnClickListener { onClick() }
    }

    private fun displayW() = metrics().first
    private fun displayH() = metrics().second

    private fun metrics(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        }
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(p)
        return p.x to p.y
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val STATUS_INTERVAL_MS = 500L
        const val BAR_WIDTH_DP = 460
    }
}
