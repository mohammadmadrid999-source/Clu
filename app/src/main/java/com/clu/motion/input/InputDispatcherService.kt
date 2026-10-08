package com.clu.motion.input

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.edit
import com.clu.motion.CluApp
import com.clu.motion.core.PipelinePhase
import com.clu.motion.core.input.JoystickDriver
import com.clu.motion.core.input.PointerSnapshot
import com.clu.motion.core.input.TouchPlanner
import com.clu.motion.core.response.StickOutput
import com.clu.motion.core.safety.PauseReason
import com.clu.motion.engine.MotionEngine
import com.clu.motion.engine.SessionState
import com.clu.motion.engine.TouchCommand
import com.clu.motion.overlay.OverlayController
import com.clu.motion.profile.ControlProfile
import com.clu.motion.ui.InjectionTestActivity
import com.clu.motion.ui.MainActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The accessibility service: turns engine output into injected touches over any app, filters
 * hardware keys and switch interfaces, and hosts the overlay HUD.
 *
 * Threads:
 *  - "clu-inject" (elevated priority): a fixed-rate tick reads the newest motion frame, drains
 *    touch commands, advances the [TouchPlanner] and dispatches one gesture batch. Gesture
 *    callbacks are delivered to the same thread, so all touch state is single-threaded.
 *  - main: accessibility events, key filtering, overlay.
 *
 * The tick only runs while a session exists, so an enabled-but-idle service costs nothing.
 */
class InputDispatcherService : AccessibilityService() {

    private lateinit var engine: MotionEngine
    private lateinit var gate: ForegroundAppGate
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Service coroutine failed", e) },
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    private var injectThread: HandlerThread? = null
    private var injectHandler: Handler? = null
    private var overlay: OverlayController? = null

    // Confined to the injection thread.
    private val planner = TouchPlanner()
    private val joystick = JoystickDriver(ControlProfile(id = "", name = "").joystick)
    private var streamer: GestureStreamer? = null
    private var ticking = false
    private var lastSnapshot: List<PointerSnapshot> = emptyList()
    private var lastSelfTestTap = 0L

    @Volatile
    private var displayWidth = 1

    @Volatile
    private var displayHeight = 1

    /** Set by the overlay while it draws touch points; avoids per-tick allocations otherwise. */
    @Volatile
    var publishTouches = false

    private val _touches = MutableStateFlow<List<PointerSnapshot>>(emptyList())
    val touches: StateFlow<List<PointerSnapshot>> = _touches.asStateFlow()

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            // A crash here would leave the service "enabled but not running" until the user
            // toggles it (Android 16); pause play instead and keep the service alive.
            val next = try {
                step(now)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Injection tick failed", e)
                planner.releaseAll()
                engine.pause(PauseReason.ERROR)
                IDLE_TICK_MS
            }
            if (next > 0) {
                injectHandler?.postAtTime(this, now + next)
            } else {
                ticking = false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = CluApp.engine(this)
        gate = ForegroundAppGate(
            ownPackage = packageName,
            ownBlockedActivities = setOf(MainActivity::class.java.name),
            ownInjectableActivities = setOf(InjectionTestActivity::class.java.name),
            homePackages = homePackages(),
        )
        updateDisplayMetrics()

        val thread = HandlerThread("clu-inject", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        val handler = Handler(thread.looper)
        injectThread = thread
        injectHandler = handler
        streamer = GestureStreamer(this, handler, planner, engine.injectionStats)

        overlay = OverlayController(this, engine, touches) { gate.currentPackage }.also { it.attach() }

        // Commands queued while no service was bound are stale: never replay an old tap.
        handler.post { while (engine.touchCommands.tryReceive().isSuccess) Unit }
        scope.launch {
            engine.session.collect { state ->
                if (state != SessionState.Stopped) handler.post { startTicking() }
            }
        }
        scope.launch {
            engine.selfTest.collect { test ->
                if (test != null) handler.post { startTicking() }
            }
        }
        _connected.value = true
        getSharedPreferences(HEALTH_PREFS, MODE_PRIVATE).edit { putBoolean(KEY_EVER_CONNECTED, true) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !::gate.isInitialized) return
        try {
            val app = gate.onWindowStateChanged(event.packageName, event.className) ?: return
            engine.onForegroundPackage(app)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Window event failed", e)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::engine.isInitialized) return false
        return try {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> engine.onKey(event.keyCode, down = true, repeatCount = event.repeatCount)
                KeyEvent.ACTION_UP -> engine.onKey(event.keyCode, down = false, repeatCount = 0)
                else -> false
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Key event failed", e)
            false // never swallow a key because of our own bug
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateDisplayMetrics()
        overlay?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        _connected.value = false
        overlay?.detach()
        overlay = null
        scope.cancel()
        // Pending injected events are cancelled by the system when the service disconnects.
        injectThread?.quitSafely()
        injectThread = null
        injectHandler = null
    }

    // ---- Injection thread --------------------------------------------------------------------

    /**
     * A real finger touched a window other than the HUD (reported by the HUD's outside-touch
     * watch). Main thread. On Android 16 the platform silently invalidates our injected stream
     * in the touched window, so held pointers must re-press; see TouchPlanner.onExternalTouch.
     */
    fun onRealTouch() {
        injectHandler?.post {
            if (planner.onExternalTouch(SystemClock.uptimeMillis())) engine.injectionStats.onExternalTouch()
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        injectHandler?.post(tick)
    }

    /** One injection tick. Returns the delay until the next tick, or 0 to stop ticking. */
    private fun step(now: Long): Long {
        val profile = engine.activeProfile.value
        val session = engine.session.value
        val frame = engine.frames.value
        val w = displayWidth
        val h = displayHeight

        planner.width = w
        planner.height = h
        planner.segmentMs = profile.joystick.segmentMs
        planner.maxInFlight = profile.joystick.maxInFlight
        joystick.config = profile.joystick

        val selfTest = engine.selfTest.value
        val live = if (selfTest != null) {
            gate.injectionAllowed // synthetic input: no session, calibration or sensors involved
        } else {
            session == SessionState.Active &&
                gate.injectionAllowed &&
                frame.phase == PipelinePhase.RUNNING &&
                now - frame.uptimeMs < STALE_FRAME_MS
        }

        while (true) {
            val command = engine.touchCommands.tryReceive().getOrNull() ?: break
            handle(command, profile, live, now)
        }
        if (!live) planner.releaseAll()

        var stick: StickOutput
        if (selfTest != null) {
            stick = if (live) selfTest.stick(now) else StickOutput.ZERO
            val tap = selfTest.tapIndex(now)
            if (live && tap > 0 && tap != lastSelfTestTap) handle(TouchCommand.Tap(selfTest.tapButtonId), profile, true, now)
            lastSelfTestTap = tap
        } else {
            stick = if (live) StickOutput(frame.stickX.toDouble(), frame.stickY.toDouble()) else StickOutput.ZERO
            if (engine.stickLocked) {
                if (live && stick.isNeutral) engine.stickLocked = false
                stick = StickOutput.ZERO
            }
        }
        joystick.update(stick, live, now, w, h, planner)
        streamer?.pump(now, frameAgeMs = if (selfTest == null && live) now - frame.uptimeMs else null)

        if (publishTouches) {
            val snapshot = planner.snapshot()
            if (snapshot != lastSnapshot) {
                lastSnapshot = snapshot
                _touches.value = snapshot
            }
        }

        return when {
            session == SessionState.Stopped && selfTest == null && planner.isIdle -> 0
            live || !planner.isIdle -> profile.joystick.segmentMs.coerceIn(MIN_TICK_MS, MAX_TICK_MS)
            else -> IDLE_TICK_MS
        }
    }

    private fun handle(command: TouchCommand, profile: ControlProfile, live: Boolean, now: Long) {
        when (command) {
            is TouchCommand.Tap -> if (live) {
                val b = button(profile, command.buttonId) ?: return
                planner.press(buttonKey(b.id), b.px, b.py, holdMs = b.tapMs, now = now)
            }
            is TouchCommand.Press -> if (live) {
                val b = button(profile, command.buttonId) ?: return
                planner.press(buttonKey(b.id), b.px, b.py, now = now)
            }
            is TouchCommand.Release -> planner.release(buttonKey(command.buttonId))
            is TouchCommand.Toggle -> if (live) {
                val b = button(profile, command.buttonId) ?: return
                val key = buttonKey(b.id)
                if (planner.has(key) && !planner.isLifting(key)) planner.release(key) else planner.press(key, b.px, b.py, now = now)
            }
            is TouchCommand.Global -> mainHandler.post { performGlobalAction(command.action) }
            TouchCommand.ReleaseAll -> planner.releaseAll()
        }
    }

    private class ResolvedButton(val id: Int, val px: Int, val py: Int, val tapMs: Long)

    private fun button(profile: ControlProfile, id: Int): ResolvedButton? {
        val b = profile.buttons.firstOrNull { it.id == id && it.enabled } ?: return null
        return ResolvedButton(b.id, (b.x * displayWidth).roundToInt(), (b.y * displayHeight).roundToInt(), b.tapMs)
    }

    private fun buttonKey(id: Int) = BUTTON_KEY_BASE + id

    // ---- Helpers -----------------------------------------------------------------------------

    /** Full display size in the current rotation: the coordinate space of dispatchGesture. */
    private fun updateDisplayMetrics() {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            displayWidth = bounds.width()
            displayHeight = bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(size)
            displayWidth = size.x
            displayHeight = size.y
        }
    }

    private fun homePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    companion object {
        private const val TAG = "InputDispatcher"
        const val HEALTH_PREFS = "service_health"
        const val KEY_EVER_CONNECTED = "ever_connected"
        private const val STALE_FRAME_MS = 250L
        private const val MIN_TICK_MS = 8L
        private const val MAX_TICK_MS = 50L
        private const val IDLE_TICK_MS = 50L
        private const val BUTTON_KEY_BASE = 100

        private val _connected = MutableStateFlow(false)

        /** True while the system has the service bound and gesture injection is available. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /** Whether the user has enabled the service in Accessibility settings (even if not yet bound). */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, InputDispatcherService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
