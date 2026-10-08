package com.clu.motion.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.clu.motion.core.diag.InjectionStats
import com.clu.motion.overlay.TargetPainter
import com.clu.motion.profile.ControlProfile
import com.clu.motion.profile.JoystickMode

/**
 * Stand-in for a game: records every MotionEvent it receives into [InjectionStats] and draws
 * fading trails per pointer, with markers for DOWN (green), UP (white) and CANCEL (red).
 * Coordinates are converted to absolute display pixels, the space dispatchGesture uses.
 */
@SuppressLint("ViewConstructor") // Created in code only, never inflated.
class TouchTestPadView(context: Context, private val stats: InjectionStats) : View(context) {

    private class Dot(val x: Float, val y: Float, val t: Long, val kind: Int)

    private val painter = TargetPainter(context)
    private val location = IntArray(2)
    private val dots = ArrayDeque<Dot>()
    private val xs = FloatArray(MAX_POINTERS)
    private val ys = FloatArray(MAX_POINTERS)
    private var profile: ControlProfile? = null
    private var displayW = 1
    private var displayH = 1
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xFF, 0xD4, 0x00) }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2E, 0xCC, 0x71) }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xFF, 0x45, 0x45)
        strokeWidth = 4 * painter.density
    }

    init {
        setBackgroundColor(Color.rgb(0x12, 0x16, 0x1A))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setLayout(profile: ControlProfile, displayW: Int, displayH: Int) {
        this.profile = profile
        this.displayW = displayW
        this.displayH = displayH
        invalidate()
    }

    fun clearTrails() {
        dots.clear()
        invalidate()
    }

    @Suppress("ClickableViewAccessibility") // A measurement surface, not a control.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        getLocationOnScreen(location)
        val ox = location[0]
        val oy = location[1]
        val n = minOf(event.pointerCount, MAX_POINTERS)
        for (i in 0 until n) {
            xs[i] = event.getX(i) + ox
            ys[i] = event.getY(i) + oy
        }
        val action = event.actionMasked
        val signature = "src=0x${Integer.toHexString(event.source)} tool=${event.getToolType(0)} " +
            "dev=${event.deviceId} flags=0x${Integer.toHexString(event.flags)}"
        val now = SystemClock.uptimeMillis()
        stats.onTouch(action, event.eventTime, now, xs, ys, n, signature)

        val kind = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> KIND_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> KIND_UP
            MotionEvent.ACTION_CANCEL -> KIND_CANCEL
            else -> KIND_MOVE
        }
        if (kind == KIND_MOVE) {
            for (i in 0 until n) dots.addLast(Dot(xs[i], ys[i], now, KIND_MOVE))
        } else {
            val idx = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else 0
            if (idx < n) dots.addLast(Dot(xs[idx], ys[idx], now, kind))
        }
        while (dots.size > MAX_DOTS) dots.removeFirst()
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        val ox = location[0].toFloat()
        val oy = location[1].toFloat()
        profile?.let { p ->
            if (p.joystick.mode != JoystickMode.OFF) painter.drawJoystick(canvas, p.joystick, displayW, displayH, ox, oy, false, "")
            for (b in p.buttons) if (b.enabled) painter.drawButton(canvas, b, displayW, displayH, ox, oy, false)
        }
        val now = SystemClock.uptimeMillis()
        val d = painter.density
        var stale = false
        for (dot in dots) {
            val age = now - dot.t
            if (age > TRAIL_MS) {
                stale = true
                continue
            }
            val x = dot.x - ox
            val y = dot.y - oy
            when (dot.kind) {
                KIND_MOVE -> {
                    trail.alpha = (255 * (1 - age.toFloat() / TRAIL_MS)).toInt().coerceIn(30, 255)
                    canvas.drawCircle(x, y, 3 * d, trail)
                }
                KIND_DOWN -> canvas.drawCircle(x, y, 9 * d, downPaint)
                KIND_UP -> canvas.drawCircle(x, y, 7 * d, upPaint)
                KIND_CANCEL -> {
                    canvas.drawLine(x - 10 * d, y - 10 * d, x + 10 * d, y + 10 * d, cancelPaint)
                    canvas.drawLine(x - 10 * d, y + 10 * d, x + 10 * d, y - 10 * d, cancelPaint)
                }
            }
        }
        if (stale) {
            while (dots.isNotEmpty() && now - dots.first().t > TRAIL_MS) dots.removeFirst()
        }
        if (dots.isNotEmpty()) postInvalidateOnAnimation()
    }

    private companion object {
        const val MAX_POINTERS = 10
        const val MAX_DOTS = 4000
        const val TRAIL_MS = 2_500L
        const val KIND_MOVE = 0
        const val KIND_DOWN = 1
        const val KIND_UP = 2
        const val KIND_CANCEL = 3
    }
}
