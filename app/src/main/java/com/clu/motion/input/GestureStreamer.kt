package com.clu.motion.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.clu.motion.core.diag.InjectionStats
import com.clu.motion.core.input.GesturePlan
import com.clu.motion.core.input.TouchPlanner

/**
 * Executes [TouchPlanner] plans with AccessibilityService.dispatchGesture.
 *
 * Each pointer is one long-lived logical stroke built from short segments chained with
 * StrokeDescription.continueStroke(..., willContinue = true). The framework appends a
 * continuation to the gesture still in flight instead of cancelling it, so a steady stream of
 * ~16 ms segments yields one continuous drag of unlimited length (a single GestureDescription is
 * capped at 60 s) whose direction can change every frame.
 *
 * Callbacks arrive on [handler] — the same thread that calls [pump] — so the planner needs no locks.
 */
class GestureStreamer(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val planner: TouchPlanner,
    private val stats: InjectionStats,
) {
    private val strokes = HashMap<Int, GestureDescription.StrokeDescription>()
    private var strokesGeneration = -1
    private var callback: Callback? = null

    /**
     * Dispatches at most one gesture. Returns true if one was sent.
     * [frameAgeMs] = age of the motion frame that set the targets (diagnostics only).
     */
    fun pump(now: Long, frameAgeMs: Long? = null): Boolean {
        val skipsBefore = planner.backpressureSkips
        val plan = planner.nextPlan(now)
        if (plan == null) {
            if (planner.backpressureSkips != skipsBefore) stats.onBackpressure()
            return false
        }
        if (plan.generation != strokesGeneration) {
            strokes.clear() // strokes from a cancelled generation can never be continued
            strokesGeneration = plan.generation
        }
        val built = build(plan)
        if (built == null) {
            stats.onRejected()
            planner.onDispatchFailed(plan.generation, now)
            return false
        }
        val cb = callback?.takeIf { it.generation == plan.generation } ?: Callback(plan.generation).also { callback = it }
        val accepted = try {
            service.dispatchGesture(built.first, cb, handler)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "dispatchGesture rejected", e)
            false
        }
        if (!accepted) {
            stats.onRejected()
            planner.onDispatchFailed(plan.generation, now)
            return false
        }
        if (stats.enabled) stats.onDispatch(now, frameAgeMs, plan.segments.map { Triple(it.key, it.toX, it.toY) })
        plan.segments.forEachIndexed { i, segment ->
            if (segment.willContinue) strokes[segment.key] = built.second[i] else strokes.remove(segment.key)
        }
        return true
    }

    private fun build(plan: GesturePlan): Pair<GestureDescription, List<GestureDescription.StrokeDescription>>? {
        val builder = GestureDescription.Builder()
        val built = ArrayList<GestureDescription.StrokeDescription>(plan.segments.size)
        try {
            for (s in plan.segments) {
                val path = Path().apply {
                    moveTo(s.fromX.toFloat(), s.fromY.toFloat())
                    // A lone moveTo is a stationary touch (the framework treats zero length as a tap location).
                    if (s.toX != s.fromX || s.toY != s.fromY) lineTo(s.toX.toFloat(), s.toY.toFloat())
                }
                val stroke = if (s.isNewStroke) {
                    GestureDescription.StrokeDescription(path, s.startDelayMs, plan.durationMs, s.willContinue)
                } else {
                    val previous = strokes[s.key] ?: return null
                    previous.continueStroke(path, 0, plan.durationMs, s.willContinue)
                }
                builder.addStroke(stroke)
                built += stroke
            }
            return builder.build() to built
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid gesture segment", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Stroke could not be continued", e)
        }
        return null
    }

    private inner class Callback(val generation: Int) : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription) {
            stats.onCompleted()
            planner.onCompleted(generation)
        }

        override fun onCancelled(gestureDescription: GestureDescription) {
            stats.onCancelled()
            planner.onCancelled(generation, SystemClock.uptimeMillis())
        }
    }

    private companion object {
        const val TAG = "GestureStreamer"
    }
}
