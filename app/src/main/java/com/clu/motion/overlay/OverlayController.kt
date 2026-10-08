package com.clu.motion.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.edit
import androidx.core.view.ViewCompat
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
    private val widgets = OverlayWidgets(ctx)
    private val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var scope: CoroutineScope? = null
    private var attached = false

    // HUD
    private lateinit var hud: HudPanel
    private lateinit var hudRoot: View
    private lateinit var hudParams: WindowManager.LayoutParams

    /** The person's own choice (persisted): panel expanded or a bubble. */
    private var userExpanded = prefs.getBoolean(KEY_EXPANDED, true)

    /** Shrunk to the bubble because play started; the next tap on the bubble expands it again. */
    private var shrunkForPlay = false
    private var shrunkThisRun = false
    private var hudPlacement = loadPlacement(KEY_HUD_X, KEY_HUD_Y) ?: legacyHudPlacement()
    private var hudDragStart = hudPlacement

    // Visualizer
    private var visualizer: TouchVisualizerView? = null

    // Layout editor
    private var editorRoot: FrameLayout? = null
    private var editor: LayoutEditorView? = null
    private var editorBar: EditorBar? = null
    private var editorLinked: MutableList<String> = mutableListOf()
    private var resumeAfterEdit = false
    private var barPlacement = loadPlacement(KEY_BAR_X, KEY_BAR_Y) ?: PanelPlacement(0.5f, 1f)
    private var barDragStart = barPlacement

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
        placeHud(measure = true)
        wm.addView(hudRoot, hudParams)
        val s = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Overlay coroutine failed", e) },
        )
        scope = s
        s.launch { engine.notices.collect(::showNotice) }
        s.launch {
            engine.session.collect { state ->
                hud.pauseButton.setText(
                    when (state) {
                        SessionState.Stopped -> R.string.hud_start
                        SessionState.Active -> R.string.hud_pause
                        is SessionState.Paused -> R.string.hud_resume
                    },
                )
                applyHudState()
                ensureRendering()
            }
        }
        s.launch {
            engine.activeProfile.collect { profile ->
                hud.profileButton.contentDescription = ctx.getString(R.string.hud_profile_description, profile.name)
                hud.profileButton.text = profile.name
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
        placeHud(measure = true)
        hudRoot.post { checkOverlap() }
    }

    // ---- HUD ---------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility") // The root's listener only observes ACTION_OUTSIDE; it never clicks or consumes.
    private fun buildHud() {
        hud = HudPanel(
            widgets,
            HudActions(
                togglePause = { engine.togglePause() },
                recenter = { engine.recalibrate() },
                layout = { openEditor() },
                nextProfile = { engine.nextProfile() },
                learn = { engine.learnMoves() },
                movePanel = { moveHud(PanelGeometry.nextAnchor(hudPlacement)) },
                stop = { engine.stopSession() },
                toggleExpanded = { toggleExpanded() },
            ),
        )
        hud.indicator.lowFrameRate()
        hud.status.lowFrameRate()
        hudRoot = hud.root.apply {
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                // Grown or shrunk (expanded, a notice): keep the edge it hugs, stay on screen.
                if (r - l != or - ol || b - t != ob - ot) placeHud(measure = false)
                checkOverlap()
            }
            // Real touches elsewhere arrive here as ACTION_OUTSIDE (FLAG_WATCH_OUTSIDE_TOUCH).
            // Our own injected events carry deviceId VIRTUAL_KEYBOARD (-1): ignore those.
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && e.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD) {
                    service.onRealTouch()
                }
                false
            }
        }
        // The indicator is the handle: drag to move the panel anywhere, tap to expand/collapse.
        DragTouchListener.attach(
            hud.indicator,
            onStart = { hudDragStart = hudPlacement },
            onDrag = { dx, dy ->
                val (sw, sh) = displaySize()
                hudPlacement = PanelGeometry.dragged(hudDragStart, dx, dy, hudRoot.width, hudRoot.height, sw, sh, dp(MARGIN_DP))
                placeHud(measure = false)
            },
            onEnd = { moveHud(hudPlacement) },
        )
        // Pause straight from the bubble (Switch Access / TalkBack actions), without expanding first.
        ViewCompat.addAccessibilityAction(hud.indicator, ctx.getString(R.string.hud_pause_action)) { _, _ ->
            engine.togglePause()
            true
        }
        addMoveActions(hud.indicator, R.string.hud_move_left, R.string.hud_move_right, R.string.hud_move_up, R.string.hud_move_down) { dx, dy ->
            moveHud(PanelGeometry.stepped(hudPlacement, dx, dy))
        }
        applyHudState()

        hudParams = baseParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, touchable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            // Absolute screen coordinates (as for the full-screen windows), so x/y are exactly where
            // the panel is drawn; placeHud keeps it on screen.
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN // not NO_LIMITS: the system also keeps it on screen
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            title = ctx.getString(R.string.hud_window_title)
        }
    }

    /** Tap on the indicator or "Hide": the person's choice, remembered. */
    private fun toggleExpanded() {
        val showing = hud.expanded
        userExpanded = !showing
        shrunkForPlay = false
        prefs.edit { putBoolean(KEY_EXPANDED, userExpanded) }
        applyHudState()
    }

    /**
     * Expanded only when the person wants it and play isn't running: during play the panel shrinks
     * to the bubble so it covers as little of the game as possible. Calibration and learning
     * prompts and notices still show next to the bubble.
     */
    private fun applyHudState() {
        if (!::hud.isInitialized) return
        val session = engine.session.value
        val phase = engine.frames.value.phase
        if (session !is SessionState.Active) {
            shrunkForPlay = false
            shrunkThisRun = false
        } else if (phase == PipelinePhase.RUNNING && !shrunkThisRun) {
            shrunkForPlay = true
            shrunkThisRun = true
        }
        val prompting = session != SessionState.Stopped && (phase == PipelinePhase.CALIBRATING || phase == PipelinePhase.LEARNING)
        val noticeShowing = notice != null && SystemClock.uptimeMillis() < noticeUntil
        // An important notice ("paused: …, tap Resume") brings the buttons it talks about.
        val important = noticeShowing && notice?.important == true
        hud.show(expanded = (userExpanded && !shrunkForPlay) || important, showStatus = prompting || noticeShowing)
    }

    /** Moves the panel to [p] and remembers it. "Move panel" cycles edges: no dragging needed. */
    private fun moveHud(p: PanelPlacement) {
        hudPlacement = p
        savePlacement(KEY_HUD_X, KEY_HUD_Y, p)
        overlapWarned = false
        placeHud(measure = false)
        hudRoot.post { checkOverlap() }
    }

    /** Positions the HUD window for [hudPlacement] and its current (or freshly measured) size. */
    private fun placeHud(measure: Boolean) {
        if (!::hudParams.isInitialized) return
        val (sw, sh) = displaySize()
        if (measure || hudRoot.width == 0) {
            hudRoot.measure(
                View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST),
            )
        }
        val w = if (measure || hudRoot.width == 0) hudRoot.measuredWidth else hudRoot.width
        val h = if (measure || hudRoot.height == 0) hudRoot.measuredHeight else hudRoot.height
        val p = PanelGeometry.topLeft(hudPlacement, w, h, sw, sh, dp(MARGIN_DP))
        if (p.x == hudParams.x && p.y == hudParams.y) return
        hudParams.x = p.x
        hudParams.y = p.y
        if (attached && hudRoot.isAttachedToWindow) wm.updateViewLayout(hudRoot, hudParams)
    }

    private fun showNotice(n: Notice) {
        notice = n
        noticeUntil = SystemClock.uptimeMillis() + if (n.important) IMPORTANT_NOTICE_MS else NOTICE_MS
        applyHudState() // the status shows next to the bubble too
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
        hud.indicator.render(frame, session is SessionState.Paused)
        val text = statusText(frame.phase, frame.learnStep, frame.learnReturning, session)
        if (hud.status.text.toString() != text) hud.status.text = text
        applyHudState() // cheap when nothing changed
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
            onSelectionChanged = { updateEditorStatus() },
            onDragChanged = { dragging -> onTargetDrag(dragging) },
        )
        editor = ed
        val bar = EditorBar(
            widgets,
            EditorActions(
                nudge = { dx, dy -> ed.nudge(dx, dy) },
                // No auto-move here: the bar must not jump away from under the finger tapping Next.
                next = { ed.selectNext() },
                resize = { ed.resizeJoystick(it) },
                toggleButton = { ed.toggleSelectedButton() },
                toggleLink = { toggleLink() },
                cycleBar = { moveBar(PanelGeometry.nextAnchor(barPlacement)) },
                cancel = { closeEditor(save = false) },
                save = { closeEditor(save = true) },
            ),
            NUDGE_STEP,
        )
        editorBar = bar
        DragTouchListener.attach(
            bar.handle,
            onStart = { barDragStart = barPlacement },
            onDrag = { dx, dy ->
                barPlacement = PanelGeometry.dragged(barDragStart, dx, dy, bar.root.width, bar.root.height, w, h, dp(MARGIN_DP))
                positionBar()
            },
            onEnd = { moveBar(barPlacement) },
        )
        addMoveActions(bar.handle, R.string.editor_bar_left, R.string.editor_bar_right, R.string.editor_bar_up, R.string.editor_bar_down) { dx, dy ->
            moveBar(PanelGeometry.stepped(barPlacement, dx, dy))
        }
        val root = FrameLayout(ctx).apply {
            addView(ed, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Placed by translation from the top-left corner (positionBar), so it can go anywhere.
            addView(bar.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        }
        bar.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionBar() }
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

    /** Moves the editor bar to [p] and remembers it. The handle's tap cycles edges: no dragging needed. */
    private fun moveBar(p: PanelPlacement) {
        barPlacement = p
        savePlacement(KEY_BAR_X, KEY_BAR_Y, p)
        positionBar()
    }

    private fun positionBar() {
        val root = editorRoot ?: return
        val bar = editorBar?.root ?: return
        if (bar.width == 0 || root.width == 0) return
        val p = PanelGeometry.topLeft(barPlacement, bar.width, bar.height, root.width, root.height, dp(MARGIN_DP))
        bar.translationX = p.x.toFloat()
        bar.translationY = p.y.toFloat()
    }

    /** While a target is dragged the bar fades, so nothing under it is hidden; then it moves away if needed. */
    private fun onTargetDrag(dragging: Boolean) {
        val bar = editorBar ?: return
        bar.root.alpha = if (dragging) DRAG_FADE_ALPHA else 1f
        if (!dragging) keepBarOffSelection()
    }

    /** If the bar covers the selected target, move it to the other half of the screen. */
    private fun keepBarOffSelection() {
        val ed = editor ?: return
        val bar = editorBar ?: return
        val root = editorRoot ?: return
        if (bar.minimized || bar.root.width == 0) return
        val left = bar.root.translationX.roundToInt()
        val top = bar.root.translationY.roundToInt()
        val barRect = IntRect(left, top, left + bar.root.width, top + bar.root.height)
        PanelGeometry.awayFrom(barPlacement, barRect, ed.selectedBounds(), root.height)?.let(::moveBar)
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
        val bar = editorBar ?: return
        bar.status.text = if (ed.aimSelected) {
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
        val linked = pkg != null && pkg in editorLinked
        bar.linkButton.apply {
            isEnabled = pkg != null
            setText(if (linked) R.string.editor_unlink else R.string.editor_link)
            contentDescription = ctx.getString(if (linked) R.string.editor_unlink_description else R.string.editor_link_description)
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
        editorBar = null
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

    /**
     * Accessibility actions that move a panel without dragging (TalkBack, Switch Access and
     * Voice Access list them for the view).
     */
    private fun addMoveActions(view: View, left: Int, right: Int, up: Int, down: Int, move: (Int, Int) -> Unit) {
        listOf(left to (-1 to 0), right to (1 to 0), up to (0 to -1), down to (0 to 1)).forEach { (label, dir) ->
            ViewCompat.addAccessibilityAction(view, ctx.getString(label)) { _, _ ->
                move(dir.first, dir.second)
                true
            }
        }
    }

    private fun loadPlacement(keyX: String, keyY: String): PanelPlacement? {
        if (!prefs.contains(keyX) || !prefs.contains(keyY)) return null
        return PanelPlacement.of(prefs.getFloat(keyX, 0f), prefs.getFloat(keyY, 0f))
    }

    private fun savePlacement(keyX: String, keyY: String, p: PanelPlacement) = prefs.edit {
        putFloat(keyX, p.fx)
        putFloat(keyY, p.fy)
    }

    /** Placement saved by earlier versions, which only offered the eight edge anchors. */
    private fun legacyHudPlacement() = PanelPlacement.ANCHORS[prefs.getInt(KEY_ANCHOR, 0).mod(PanelPlacement.ANCHORS.size)]

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

    private fun dp(v: Int) = widgets.dp(v)

    private companion object {
        const val TAG = "OverlayController"
        const val PREFS = "hud"
        const val KEY_EXPANDED = "expanded"
        const val KEY_ANCHOR = "anchor"
        const val KEY_HUD_X = "hud_x"
        const val KEY_HUD_Y = "hud_y"
        const val KEY_BAR_X = "editor_bar_x"
        const val KEY_BAR_Y = "editor_bar_y"
        const val MARGIN_DP = 8
        const val DRAG_FADE_ALPHA = 0.25f
        const val RENDER_INTERVAL_MS = 33L
        const val NOTICE_MS = 3_000L
        const val IMPORTANT_NOTICE_MS = 7_000L
        const val NUDGE_STEP = 0.01
    }
}
