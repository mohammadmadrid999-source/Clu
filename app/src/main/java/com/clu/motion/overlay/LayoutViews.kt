package com.clu.motion.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.clu.motion.core.input.PointerSnapshot
import com.clu.motion.core.input.TouchPlanner
import com.clu.motion.profile.JoystickConfig
import com.clu.motion.profile.JoystickMode
import com.clu.motion.profile.VirtualButton
import kotlin.math.hypot
import kotlin.math.min

/** Shared geometry/paint for drawing touch targets in display coordinates. */
private class TargetPainter(context: Context) {
    val density = context.resources.displayMetrics.density
    val buttonRadius = 26 * density
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.argb(200, 255, 255, 255)
    }
    val selected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        color = Color.rgb(0xFF, 0xD4, 0x00)
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    val disabled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.argb(110, 160, 160, 160)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16 * density
        isFakeBoldText = true
        setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
    }
    val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xFF, 0xD4, 0x00) }

    fun drawJoystick(canvas: Canvas, cfg: JoystickConfig, w: Int, h: Int, ox: Float, oy: Float, highlight: Boolean, name: String) {
        val cx = (cfg.centerX * w).toFloat() - ox
        val cy = (cfg.centerY * h).toFloat() - oy
        val r = (cfg.radiusFraction * min(w, h)).toFloat()
        val paint = if (highlight) selected else ring
        if (cfg.mode == JoystickMode.CAMERA_DRAG) {
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fill)
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, paint)
        } else {
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, paint)
        }
        canvas.drawLine(cx - 10 * density, cy, cx + 10 * density, cy, ring)
        canvas.drawLine(cx, cy - 10 * density, cx, cy + 10 * density, ring)
        canvas.drawText(name, cx, cy - r - 8 * density, label)
    }

    fun drawButton(canvas: Canvas, b: VirtualButton, w: Int, h: Int, ox: Float, oy: Float, highlight: Boolean) {
        val cx = (b.x * w).toFloat() - ox
        val cy = (b.y * h).toFloat() - oy
        if (b.enabled) canvas.drawCircle(cx, cy, buttonRadius, fill)
        canvas.drawCircle(cx, cy, buttonRadius, if (highlight) selected else if (b.enabled) ring else disabled)
        canvas.drawText(b.label, cx, cy + label.textSize / 3, label)
    }
}

/** Pass-through (non-touchable) overlay showing targets and live virtual fingers. */
class TouchVisualizerView(context: Context) : View(context) {
    private val painter = TargetPainter(context)
    private val location = IntArray(2)
    private var joystick: JoystickConfig? = null
    private var buttons: List<VirtualButton> = emptyList()
    private var pointers: List<PointerSnapshot> = emptyList()
    private var displayW = 1
    private var displayH = 1
    var joystickLabel = ""

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun update(joystick: JoystickConfig, buttons: List<VirtualButton>, pointers: List<PointerSnapshot>, w: Int, h: Int) {
        this.joystick = joystick
        this.buttons = buttons
        this.pointers = pointers
        displayW = w
        displayH = h
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        val ox = location[0].toFloat()
        val oy = location[1].toFloat()
        joystick?.takeIf { it.mode != JoystickMode.OFF }?.let {
            painter.drawJoystick(canvas, it, displayW, displayH, ox, oy, false, joystickLabel)
        }
        for (b in buttons) if (b.enabled) painter.drawButton(canvas, b, displayW, displayH, ox, oy, false)
        for (p in pointers) {
            canvas.drawCircle(p.x - ox, p.y - oy, (if (p.key == TouchPlanner.JOYSTICK_KEY) 14 else 18) * painter.density, painter.pointer)
        }
    }
}

/**
 * Full-screen layout editor drawn over the game: drag the stick and buttons onto the game's own
 * controls. Every action is also available from the control bar (next target, nudge, resize),
 * so it can be operated by switch, Voice Access or TalkBack without any dragging.
 */
@SuppressLint("ViewConstructor")
class LayoutEditorView(
    context: Context,
    private val displayW: Int,
    private val displayH: Int,
    var joystick: JoystickConfig,
    val buttons: MutableList<VirtualButton>,
    private val joystickName: String,
    private val onSelectionChanged: () -> Unit,
) : View(context) {

    private val painter = TargetPainter(context)
    private val location = IntArray(2)
    private val dim = Paint().apply { color = Color.argb(90, 0, 0, 0) }
    private var dragging = false

    /** 0 = joystick, 1..n = buttons[index - 1]. */
    var selected = 0
        private set

    val targetCount get() = 1 + buttons.size

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        val ox = location[0].toFloat()
        val oy = location[1].toFloat()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        painter.drawJoystick(canvas, joystick, displayW, displayH, ox, oy, selected == 0, joystickName)
        buttons.forEachIndexed { i, b -> painter.drawButton(canvas, b, displayW, displayH, ox, oy, selected == i + 1) }
    }

    @SuppressLint("ClickableViewAccessibility") // Dragging has a full non-touch alternative in the control bar.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        getLocationOnScreen(location)
        val x = event.x + location[0]
        val y = event.y + location[1]
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(x, y)
                if (hit < 0) return false
                select(hit)
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                moveSelectedTo(x.toDouble() / displayW, y.toDouble() / displayH)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun selectNext() = select((selected + 1) % targetCount)

    fun nudge(dx: Double, dy: Double) {
        val (x, y) = selectedPosition()
        moveSelectedTo(x + dx, y + dy)
    }

    fun resizeJoystick(delta: Double) {
        joystick = joystick.copy(radiusFraction = (joystick.radiusFraction + delta).coerceIn(0.04, 0.35))
        invalidate()
        onSelectionChanged()
    }

    fun toggleSelectedButton() {
        if (selected == 0) return
        val b = buttons[selected - 1]
        buttons[selected - 1] = b.copy(enabled = !b.enabled)
        invalidate()
        onSelectionChanged()
    }

    /** Position of the selected target as display fractions. */
    fun selectedPosition(): Pair<Double, Double> =
        if (selected == 0) joystick.centerX to joystick.centerY else buttons[selected - 1].let { it.x to it.y }

    fun selectedButton(): VirtualButton? = if (selected == 0) null else buttons[selected - 1]

    private fun select(index: Int) {
        selected = index
        invalidate()
        onSelectionChanged()
    }

    private fun moveSelectedTo(fx: Double, fy: Double) {
        val x = fx.coerceIn(0.0, 1.0)
        val y = fy.coerceIn(0.0, 1.0)
        if (selected == 0) {
            joystick = joystick.copy(centerX = x, centerY = y)
        } else {
            buttons[selected - 1] = buttons[selected - 1].copy(x = x, y = y)
        }
        invalidate()
        onSelectionChanged()
    }

    private fun hitTest(x: Float, y: Float): Int {
        val slop = 48 * painter.density
        var best = -1
        var bestDist = Float.MAX_VALUE
        buttons.forEachIndexed { i, b ->
            val d = hypot(x - (b.x * displayW).toFloat(), y - (b.y * displayH).toFloat())
            if (d < maxOf(slop, painter.buttonRadius) && d < bestDist) {
                best = i + 1
                bestDist = d
            }
        }
        if (best >= 0) return best
        val r = (joystick.radiusFraction * min(displayW, displayH)).toFloat()
        val d = hypot(x - (joystick.centerX * displayW).toFloat(), y - (joystick.centerY * displayH).toFloat())
        return if (d < maxOf(slop, r)) 0 else -1
    }
}
