package com.clu.motion.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.clu.motion.R
import com.clu.motion.core.PipelinePhase
import com.clu.motion.core.calibration.AxisLearner
import com.clu.motion.core.input.PointerSnapshot
import com.clu.motion.core.safety.PauseReason
import com.clu.motion.engine.MotionEngine
import com.clu.motion.engine.Notice
import com.clu.motion.engine.SessionState
import com.clu.motion.input.InputDispatcherService
import com.clu.motion.profile.ControlProfile
import com.clu.motion.profile.JoystickMode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Floating controls drawn over the game, owned by the accessibility service.
 *
 * Window type TYPE_ACCESSIBILITY_OVERLAY: needs no SYSTEM_ALERT_WINDOW permission, is removed
 * automatically when the service is disabled, and is a *trusted* overlay — Android 12+ does not
 * block touches passing through it (untrusted SYSTEM_ALERT_WINDOW overlays above 0.8 opacity do).
 *
 * Windows:
 *  - HUD: a small, movable panel (collapsible to a bubble) with the tilt indicator, a status line
 *    (live region, so screen readers announce changes) and large Pause/Recenter/Profile/Layout
 *    buttons. Not focusable, so it never steals key input from the game.
 *  - Touch visualizer (optional): full-screen, NOT_TOUCHABLE, shows targets and virtual fingers.
 *  - Layout editor: full-screen, touchable, used while play is paused.
 *
 * Rendering is pulled on vsync and throttled to [RENDER_INTERVAL_MS] (~30 Hz) so the HUD never
 * competes with the game for frames; nothing is drawn per sensor sample. Main thread only.
 */
class OverlayController(
    private val service: InputDispatcherService,
    private val engine: MotionEngine,
    private val touches: StateFlow<List<PointerSnapshot>>,
    private val currentApp: () -> String?,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val ctx: Context = ContextThemeWrapper(service, R.style.Theme_Clu_Overlay)
    private val density = ctx.resources.displayMetrics.density
    private val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var scope: CoroutineScope? = null
    private var attached = false

    // HUD
    private lateinit var hudRoot: LinearLayout
    private lateinit var hudParams: WindowManager.LayoutParams
    private lateinit var indicator: TiltIndicatorView
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var pauseButton: Button
    private lateinit var profileButton: Button
    private var expanded = prefs.getBoolean(KEY_EXPANDED, true)
    private var anchor = prefs.getInt(KEY_ANCHOR, 0).mod(ANCHORS.size)

    // Visualizer
    private var visualizer: TouchVisualizerView? = null

    // Layout editor
    private var editorRoot: View? = null
    private var editor: LayoutEditorView? = null
    private var editorStatus: TextView? = null
    private var editorBar: LinearLayout? = null
    private var editorLinked: MutableList<String> = mutableListOf()
    private var linkButton: Button? = null
    private var resumeAfterEdit = false

    // Rendering
    private var notice: Notice? = null
    private var noticeUntil = 0L
    private var rendering = false
    private var overlapWarned = false

    /**
     * Vsync-aligned but only ~30 times a second: a delayed frame callback rather than one per
     * vsync, so a 120 Hz panel doesn't wake the main thread 120 times a second during play.
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!attached) {
                rendering = false
                return
            }
            try {
                render()
            } catch (e: RuntimeException) {
                Log.e(TAG, "HUD render failed", e) // a broken HUD must not take the service down
            }
            val idle = engine.session.value == SessionState.Stopped &&
                engine.selfTest.value == null &&
                SystemClock.uptimeMillis() > noticeUntil
            if (idle) {
                rendering = false
            } else {
                Choreographer.getInstance().postFrameCallbackDelayed(this, RENDER_INTERVAL_MS)
            }
        }
    }

    fun attach() {
        if (attached) return
        attached = true
        buildHud()
        wm.addView(hudRoot, hudParams)
        val s = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Overlay coroutine failed", e) },
        )
        scope = s
        s.launch { engine.notices.collect(::showNotice) }
        s.launch {
            engine.session.collect { state ->
                pauseButton.setText(
                    when (state) {
                        SessionState.Stopped -> R.string.hud_start
                        SessionState.Active -> R.string.hud_pause
                        is SessionState.Paused -> R.string.hud_resume
                    },
                )
                ensureRendering()
            }
        }
        s.launch {
            engine.activeProfile.collect { profile ->
                profileButton.contentDescription = ctx.getString(R.string.hud_profile_description, profile.name)
                profileButton.text = profile.name
                updateVisualizer(profile)
                checkOverlap()
            }
        }
        s.launch {
            combine(touches, engine.activeProfile) { pointers, profile -> pointers to profile }.collect { (pointers, profile) ->
                val (w, h) = displaySize()
                visualizer?.update(profile.joystick, profile.aim, profile.buttons, pointers, w, h)
            }
        }
        ensureRendering()
    }

    fun detach() {
        if (!attached) return
        attached = false
        scope?.cancel()
        scope = null
        closeEditor(save = false)
        removeVisualizer()
        removeView(hudRoot)
    }

    fun onConfigurationChanged() {
        if (!attached) return
        // Display size changed (rotation, fold): an open editor would save stale fractions, and
        // the full-screen visualizer window was sized for the old display.
        if (editorRoot != null) closeEditor(save = false)
        removeVisualizer()
        updateVisualizer(engine.activeProfile.value)
        hudRoot.post { checkOverlap() }
    }

    // ---- HUD ---------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility") // The touch listener only observes ACTION_OUTSIDE; it never clicks or consumes.
    private fun buildHud() {
        indicator = TiltIndicatorView(ctx).apply {
            lowFrameRate()
            setOnClickListener { toggleExpanded() }
            ViewCompat.replaceAccessibilityAction(
                this,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                ctx.getString(R.string.hud_toggle_controls),
                null,
            )
        }
        status = TextView(ctx).apply {
            lowFrameRate()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 4
            setPadding(dp(8), 0, 0, 0)
            ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(indicator, LinearLayout.LayoutParams(dp(INDICATOR_DP), dp(INDICATOR_DP)))
            addView(status, LinearLayout.LayoutParams(dp(STATUS_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        pauseButton = hudButton(R.string.hud_start) { engine.togglePause() }
        profileButton = hudButton(R.string.hud_profile) { engine.nextProfile() }
        controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(pauseButton, hudButton(R.string.hud_recenter) { engine.recalibrate() }))
            addView(row(profileButton, hudButton(R.string.hud_layout) { openEditor() }))
            addView(row(hudButton(R.string.hud_learn) { engine.learnMoves() }, hudButton(R.string.hud_move) { moveHud() }))
            addView(row(hudButton(R.string.hud_stop) { engine.stopSession() }, hudButton(R.string.hud_hide) { toggleExpanded() }))
        }

        hudRoot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(225, 16, 20, 24))
            }
            addView(header)
            addView(controls)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> checkOverlap() }
            // Real touches elsewhere arrive here as ACTION_OUTSIDE (FLAG_WATCH_OUTSIDE_TOUCH).
            // Our own injected events carry deviceId VIRTUAL_KEYBOARD (-1): ignore those.
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && e.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD) {
                    service.onRealTouch()
                }
                false
            }
        }
        applyExpanded()

        hudParams = baseParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, touchable = true).apply {
            gravity = ANCHORS[anchor]
            x = dp(8)
            y = dp(8)
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            title = ctx.getString(R.string.hud_window_title)
        }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        prefs.edit { putBoolean(KEY_EXPANDED, expanded) }
        applyExpanded()
    }

    private fun applyExpanded() {
        controls.visibility = if (expanded) View.VISIBLE else View.GONE
        status.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    /** Cycles the panel through screen anchors: one tap, no dragging (switch/voice friendly). */
    private fun moveHud() {
        anchor = (anchor + 1) % ANCHORS.size
        prefs.edit { putInt(KEY_ANCHOR, anchor) }
        hudParams.gravity = ANCHORS[anchor]
        overlapWarned = false
        if (attached && hudRoot.isAttachedToWindow) wm.updateViewLayout(hudRoot, hudParams)
    }

    private fun showNotice(n: Notice) {
        notice = n
        noticeUntil = SystemClock.uptimeMillis() + if (n.important) IMPORTANT_NOTICE_MS else NOTICE_MS
        if (n.important && !expanded) toggleExpanded()
        ensureRendering()
    }

    private fun ensureRendering() {
        if (rendering || !attached) return
        rendering = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun render() {
        val frame = engine.frames.value
        val session = engine.session.value
        indicator.render(frame, session is SessionState.Paused)
        val text = statusText(frame.phase, frame.learnStep, frame.learnReturning, session)
        if (status.text.toString() != text) status.text = text
    }

    private fun statusText(phase: PipelinePhase, step: AxisLearner.Step?, returning: Boolean, session: SessionState): String {
        if (session != SessionState.Stopped) {
            when (phase) {
                PipelinePhase.CALIBRATING -> return ctx.getString(R.string.status_calibrating)
                PipelinePhase.LEARNING -> return if (returning || step == null) {
                    ctx.getString(R.string.status_learn_return)
                } else {
                    ctx.getString(R.string.status_learn_move, ctx.getString(step.label()))
                }
                else -> Unit
            }
        }
        val n = notice
        if (n != null && SystemClock.uptimeMillis() < noticeUntil) return n.text
        return when (session) {
            SessionState.Stopped -> ctx.getString(R.string.status_stopped)
            SessionState.Active -> engine.activeProfile.value.name
            is SessionState.Paused -> ctx.getString(
                if (session.reason == PauseReason.DROP_DETECTED) R.string.notice_paused_drop else R.string.status_paused,
            )
        }
    }

    /** Warns once if the panel sits on top of a stick/button (injected taps would hit the panel). */
    private fun checkOverlap() {
        if (!attached || !hudRoot.isAttachedToWindow || editorRoot != null) return
        val loc = IntArray(2)
        hudRoot.getLocationOnScreen(loc)
        val pad = dp(8)
        val rect = Rect(loc[0] - pad, loc[1] - pad, loc[0] + hudRoot.width + pad, loc[1] + hudRoot.height + pad)
        val profile = engine.activeProfile.value
        val (w, h) = displaySize()
        val points = buildList {
            when (profile.joystick.mode) {
                JoystickMode.OFF -> Unit
                JoystickMode.AIM -> add((profile.aim.padX * w).roundToInt() to (profile.aim.padY * h).roundToInt())
                else -> add((profile.joystick.centerX * w).roundToInt() to (profile.joystick.centerY * h).roundToInt())
            }
            profile.buttons.filter { it.enabled }.forEach { add((it.x * w).roundToInt() to (it.y * h).roundToInt()) }
        }
        val overlaps = points.any { (x, y) -> rect.contains(x, y) }
        if (overlaps && !overlapWarned) showNotice(Notice(ctx.getString(R.string.notice_overlap), important = true))
        overlapWarned = overlaps
    }

    // ---- Touch visualizer --------------------------------------------------------------------

    private fun updateVisualizer(profile: ControlProfile) {
        val wanted = attached && profile.showTouchPoints && editorRoot == null
        service.publishTouches = wanted
        if (!wanted) {
            removeVisualizer()
            return
        }
        val (w, h) = displaySize()
        val view = visualizer ?: TouchVisualizerView(ctx).also {
            it.lowFrameRate()
            visualizer = it
            wm.addView(
                it,
                fullScreenParams(touchable = false).apply {
                    title = ctx.getString(R.string.visualizer_window_title)
                },
            )
        }
        view.joystickLabel = joystickName(profile)
        view.aimLabel = ctx.getString(R.string.joystick_name_aim)
        view.update(profile.joystick, profile.aim, profile.buttons, touches.value, w, h)
    }

    private fun removeVisualizer() {
        service.publishTouches = false
        visualizer?.let(::removeView)
        visualizer = null
    }

    // ---- Layout editor -----------------------------------------------------------------------

    private fun openEditor() {
        if (editorRoot != null) return
        val profile = engine.activeProfile.value
        resumeAfterEdit = engine.session.value == SessionState.Active
        engine.pause(PauseReason.LAYOUT_EDIT)
        removeVisualizer()
        hudRoot.visibility = View.GONE

        val (w, h) = displaySize()
        editorLinked = profile.linkedPackages.toMutableList()
        val ed = LayoutEditorView(
            ctx,
            w,
            h,
            profile.joystick,
            profile.aim,
            profile.usesAim,
            profile.buttons.toMutableList(),
            joystickName(profile),
            ctx.getString(R.string.joystick_name_aim),
        ) {
            updateEditorStatus()
        }
        editor = ed
        val statusView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(4), 0, dp(4), dp(6))
            ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        }
        editorStatus = statusView
        val step = NUDGE_STEP
        linkButton = hudButton(R.string.editor_link) { toggleLink() }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(235, 16, 20, 24))
            }
            addView(statusView)
            addView(
                row(
                    iconButton("◀", R.string.editor_nudge_left) { ed.nudge(-step, 0.0) },
                    iconButton("▲", R.string.editor_nudge_up) { ed.nudge(0.0, -step) },
                    iconButton("▼", R.string.editor_nudge_down) { ed.nudge(0.0, step) },
                    iconButton("▶", R.string.editor_nudge_right) { ed.nudge(step, 0.0) },
                    hudButton(R.string.editor_next) { ed.selectNext() },
                ),
            )
            addView(
                row(
                    hudButton(R.string.editor_smaller) { ed.resizeJoystick(-0.01) },
                    hudButton(R.string.editor_bigger) { ed.resizeJoystick(0.01) },
                    hudButton(R.string.editor_toggle) { ed.toggleSelectedButton() },
                    linkButton!!,
                ),
            )
            addView(
                row(
                    hudButton(R.string.editor_bar) { flipEditorBar() },
                    hudButton(R.string.editor_cancel) { closeEditor(save = false) },
                    hudButton(R.string.editor_save) { closeEditor(save = true) },
                ),
            )
        }
        editorBar = bar
        val root = FrameLayout(ctx).apply {
            addView(ed, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                bar,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    setMargins(dp(16), dp(16), dp(16), dp(16))
                },
            )
        }
        editorRoot = root
        ViewCompat.setAccessibilityPaneTitle(root, ctx.getString(R.string.editor_window_title))
        wm.addView(
            root,
            fullScreenParams(touchable = true).apply {
                title = ctx.getString(R.string.editor_window_title)
            },
        )
        updateEditorStatus()
    }

    private fun flipEditorBar() {
        val bar = editorBar ?: return
        val lp = bar.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (lp.gravity and Gravity.BOTTOM == Gravity.BOTTOM) {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        } else {
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        bar.layoutParams = lp
    }

    private fun toggleLink() {
        val pkg = currentApp() ?: return
        if (!editorLinked.remove(pkg)) editorLinked += pkg
        updateEditorStatus()
    }

    private fun updateEditorStatus() {
        val ed = editor ?: return
        val (x, y) = ed.selectedPosition()
        val px = (x * 100).roundToInt()
        val py = (y * 100).roundToInt()
        val b = ed.selectedButton()
        editorStatus?.text = if (ed.aimSelected) {
            ctx.getString(R.string.editor_selected_aim, px, py)
        } else if (b == null) {
            ctx.getString(R.string.editor_selected_stick, joystickName(engine.activeProfile.value), px, py)
        } else {
            ctx.getString(
                R.string.editor_selected_button,
                b.label,
                ctx.getString(if (b.enabled) R.string.editor_on else R.string.editor_off),
                px,
                py,
            )
        }
        val pkg = currentApp()
        linkButton?.apply {
            isEnabled = pkg != null
            setText(if (pkg != null && pkg in editorLinked) R.string.editor_unlink else R.string.editor_link)
        }
    }

    private fun closeEditor(save: Boolean) {
        val root = editorRoot ?: return
        val ed = editor
        if (save && ed != null) {
            val joystick = ed.joystick
            val aim = ed.aim
            val buttons = ed.buttons.toList()
            val linked = editorLinked.toList()
            // Only the layout: the mode may have been switched (move/aim) while editing.
            engine.updateActiveProfile {
                it.copy(
                    joystick = it.joystick.copy(centerX = joystick.centerX, centerY = joystick.centerY, radiusFraction = joystick.radiusFraction),
                    aim = it.aim.copy(padX = aim.padX, padY = aim.padY, padRadiusFraction = aim.padRadiusFraction),
                    buttons = buttons,
                    linkedPackages = linked,
                )
            }
        }
        removeView(root)
        editorRoot = null
        editor = null
        editorStatus = null
        editorBar = null
        linkButton = null
        if (attached) {
            hudRoot.visibility = View.VISIBLE
            overlapWarned = false
            updateVisualizer(engine.activeProfile.value)
            if (resumeAfterEdit) engine.resume()
        }
        resumeAfterEdit = false
    }

    // ---- Window & view helpers ---------------------------------------------------------------

    private fun baseParams(width: Int, height: Int, touchable: Boolean) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    )

    /** Covers the whole display, including cutouts and system bars: gesture coordinates are absolute. */
    private fun fullScreenParams(touchable: Boolean): WindowManager.LayoutParams {
        val (w, h) = displaySize()
        return baseParams(w, h, touchable).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(size)
        return size.x to size.y
    }

    private fun removeView(view: View) {
        if (view.isAttachedToWindow) wm.removeViewImmediate(view)
    }

    private fun row(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
    }

    private fun hudButton(@StringRes label: Int, onClick: () -> Unit) = styledButton().apply {
        setText(label)
        setOnClickListener { onClick() }
    }

    private fun iconButton(glyph: String, @StringRes description: Int, onClick: () -> Unit) = styledButton().apply {
        text = glyph
        contentDescription = ctx.getString(description)
        setOnClickListener { onClick() }
    }

    /** ≥ 48 dp targets, high contrast, and a thick yellow ring when focused (switch/keyboard users). */
    private fun styledButton() = Button(ctx).apply {
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = dp(48)
        minimumWidth = dp(48)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(8), 0, dp(8), 0)
        stateListAnimator = null
        val normal = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.rgb(0x2A, 0x31, 0x38))
        }
        val focused = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.rgb(0x2A, 0x31, 0x38))
            setStroke(dp(3), Color.rgb(0xFF, 0xD4, 0x00))
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(80, 255, 255, 255)), states, null)
    }

    private fun joystickName(profile: ControlProfile) =
        ctx.getString(if (profile.joystick.mode == JoystickMode.CAMERA_DRAG) R.string.joystick_name_camera else R.string.joystick_name_stick)

    private fun AxisLearner.Step.label() = when (this) {
        AxisLearner.Step.RIGHT -> R.string.learn_right
        AxisLearner.Step.LEFT -> R.string.learn_left
        AxisLearner.Step.UP -> R.string.learn_up
        AxisLearner.Step.DOWN -> R.string.learn_down
    }

    /** Android 15+: tell the refresh-rate policy this view's updates don't need a high rate. */
    private fun View.lowFrameRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_LOW)
        }
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    private companion object {
        const val TAG = "OverlayController"
        const val PREFS = "hud"
        const val KEY_EXPANDED = "expanded"
        const val KEY_ANCHOR = "anchor"
        const val INDICATOR_DP = 72
        const val STATUS_WIDTH_DP = 168
        const val RENDER_INTERVAL_MS = 33L
        const val NOTICE_MS = 3_000L
        const val IMPORTANT_NOTICE_MS = 7_000L
        const val NUDGE_STEP = 0.01

        val ANCHORS = intArrayOf(
            Gravity.TOP or Gravity.START,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            Gravity.TOP or Gravity.END,
            Gravity.CENTER_VERTICAL or Gravity.END,
            Gravity.BOTTOM or Gravity.END,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            Gravity.BOTTOM or Gravity.START,
            Gravity.CENTER_VERTICAL or Gravity.START,
        )
    }
}
