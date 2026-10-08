package com.clu.motion.overlay

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Turns a view into a drag handle that is still a normal button: a press that moves past the
 * touch slop drags, anything else is a click (performClick, so TalkBack, Switch Access and Voice
 * Access keep working through the view's click action). Uses raw screen coordinates, so it works
 * on a handle inside a window that moves while being dragged.
 */
class DragTouchListener(
    view: View,
    private val onStart: () -> Unit,
    /** Total movement since the press, in screen pixels. */
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onEnd: () -> Unit,
) : View.OnTouchListener {

    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility") // A press without movement calls performClick().
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                v.isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    v.isPressed = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    onStart()
                }
                if (dragging) onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                if (dragging) onEnd() else v.performClick()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                if (dragging) onEnd()
                dragging = false
            }
        }
        return true
    }

    companion object {
        /** Makes [view] draggable; returns the listener (already installed). */
        fun attach(view: View, onStart: () -> Unit, onDrag: (Float, Float) -> Unit, onEnd: () -> Unit) =
            DragTouchListener(view, onStart, onDrag, onEnd).also { view.setOnTouchListener(it) }
    }
}
