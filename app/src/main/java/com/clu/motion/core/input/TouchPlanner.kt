package com.clu.motion.core.input

import com.clu.motion.core.response.StickOutput
import com.clu.motion.profile.AimConfig
import com.clu.motion.profile.JoystickConfig
import com.clu.motion.profile.JoystickMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** One pointer's stroke for one injection tick. Coordinates are whole display pixels. */
data class PointerSegment(
    val key: Int,
    val fromX: Int,
    val fromY: Int,
    val toX: Int,
    val toY: Int,
    /** true → a new StrokeDescription (touch down); false → continueStroke of this key's last stroke. */
    val isNewStroke: Boolean,
    /** false → this segment ends with the pointer lifting (ACTION_UP / POINTER_UP). */
    val willContinue: Boolean,
    /** Stroke start time within the gesture (see rule 3 on [TouchPlanner]). */
    val startDelayMs: Long = 0,
)

data class GesturePlan(val generation: Int, val durationMs: Long, val segments: List<PointerSegment>)

data class PointerSnapshot(val key: Int, val x: Int, val y: Int, val down: Boolean)

/**
 * Plans multi-touch injection as a stream of short continued strokes (Android
 * StrokeDescription.continueStroke, API 26+). Every live pointer (joystick, held buttons, taps)
 * advances by one segment per tick, so movement and button presses share one gesture and never
 * cancel each other.
 *
 * Rules derived from the platform's MotionEventInjector (checked against AOSP 9 → main):
 *  1. Whole-pixel endpoints. A continuation is accepted only if its first point equals the
 *     previous stroke's last point after Math.round. PathMeasure's end position can be off by a
 *     few ULPs; starting from an integer keeps both roundings identical.
 *  2. Every pointer still down must be continued by the next gesture, or the system rejects the
 *     gesture and cancels everything in flight. We always continue all live pointers.
 *  3. The first step of a continuing gesture must contain only continued strokes (the injector
 *     counts every touch point at t=0 against the pointers still down). New fingers therefore
 *     start 1 ms into the segment and arrive as ACTION_POINTER_DOWN.
 *  4. Continuations are appended after the last scheduled event instead of cancelling it, which
 *     is what makes streaming possible. At most [maxInFlight] segments are queued; extra ticks
 *     coalesce (the next segment just goes to the newest target). Queueing latency is bounded by
 *     ≈ maxInFlight × segmentMs and no motion is lost.
 *  5. Never start a fresh (non-continuing) gesture while anything is in flight: the injector
 *     cancels queued events first, turning a tap's ACTION_UP into ACTION_CANCEL (games ignore it).
 *  6. A cancelled gesture (real touch, rejected continuation) invalidates every stroke in its
 *     generation; pointers that still want to be down re-press after a short backoff.
 *  7. Never send a gesture in which nothing moves, starts or lifts. It produces no MotionEvents,
 *     and the injector reports such a gesture as *failed* (`events.isEmpty()` → failure, in every
 *     release from Android 9 to 16) even though it accepted the continuation. Treating that as a
 *     cancel caused a DOWN → MOVE → "failed" → CANCEL → DOWN loop whenever the stick held still
 *     (measured on a POCO X7 Pro: 104 CANCELs for 114 DOWNs). A finger held still simply sends
 *     nothing; the injector keeps the stroke open until the next continuation.
 *
 * Not thread-safe: confine to the injection thread.
 */
class TouchPlanner(
    var segmentMs: Long = 16,
    var maxInFlight: Int = 2,
    private val cancelBackoffMs: Long = 150,
    private val externalTouchBackoffMs: Long = 300,
) {
    private class Pointer(val key: Int) {
        var down = false
        var x = 0
        var y = 0
        var pressX = 0
        var pressY = 0
        var targetX = 0
        var targetY = 0
        /** Timed tap duration, counted from the actual touch down. */
        var holdMs: Long? = null
        var releaseAt = Long.MAX_VALUE
        var lifting = false
    }

    private class PendingPress(val pressX: Int, val pressY: Int, val holdMs: Long?)

    private val pointers = LinkedHashMap<Int, Pointer>()
    private val pending = HashMap<Int, PendingPress>()
    private var inFlight = 0
    private var resumeAt = 0L

    var generation = 0
        private set

    /** Ticks that had work but waited because [maxInFlight] segments were already queued. */
    var backpressureSkips = 0L
        private set

    /** Display size used to clamp coordinates (paths must not be negative or off-screen). */
    var width = 1
    var height = 1

    val isIdle get() = pointers.isEmpty() && pending.isEmpty() && inFlight == 0

    fun has(key: Int) = pointers.containsKey(key)

    fun isLifting(key: Int) = pointers[key]?.lifting == true

    /**
     * Puts a pointer down at ([pressX],[pressY]) on the next tick and then moves it toward the
     * target. [holdMs] = timed tap; null = held until [release].
     */
    fun press(
        key: Int,
        pressX: Int,
        pressY: Int,
        targetX: Int = pressX,
        targetY: Int = pressY,
        holdMs: Long? = null,
        now: Long,
    ) {
        val existing = pointers[key]
        if (existing != null) {
            if (existing.lifting) {
                pending[key] = PendingPress(pressX, pressY, holdMs) // re-press after the lift lands
            } else {
                existing.targetX = targetX
                existing.targetY = targetY
                existing.holdMs = holdMs
                if (existing.down) existing.releaseAt = holdMs?.let { now + it } ?: Long.MAX_VALUE
            }
            return
        }
        pointers[key] = Pointer(key).apply {
            this.pressX = pressX
            this.pressY = pressY
            this.targetX = targetX
            this.targetY = targetY
            this.holdMs = holdMs
        }
    }

    fun moveTo(key: Int, x: Int, y: Int) {
        val p = pointers[key] ?: return
        if (p.lifting) return
        p.targetX = x
        p.targetY = y
    }

    /**
     * Lifts the pointer on its next segment. A button released before it ever went down still
     * taps (a very quick switch press must not be lost); a joystick that never went down is dropped.
     */
    fun release(key: Int) {
        pending.remove(key)
        val p = pointers[key] ?: return
        if (!p.down && key == JOYSTICK_KEY) pointers.remove(key) else p.lifting = true
    }

    /** Pause/stop: lift everything that is down and forget anything not yet injected. */
    fun releaseAll() {
        pending.clear()
        pointers.values.removeAll { !it.down }
        for (p in pointers.values) p.lifting = true
    }

    /** Builds the next segment batch, or null if there is nothing to send or we must wait. */
    fun nextPlan(now: Long): GesturePlan? {
        if (now < resumeAt) return null
        if (inFlight >= maxInFlight) {
            if (pointers.isNotEmpty()) backpressureSkips++
            return null
        }
        for (p in pointers.values) {
            if (p.down && !p.lifting && now >= p.releaseAt) p.lifting = true
        }

        val continuing = pointers.values.filter { it.down }
        val starting = pointers.values.filter { !it.down }
        if (continuing.isEmpty() && (starting.isEmpty() || inFlight > 0)) return null

        val segments = ArrayList<PointerSegment>(min(continuing.size + starting.size, MAX_STROKES))
        for (p in continuing) {
            segments += PointerSegment(p.key, p.x, p.y, cx(p.targetX), cy(p.targetY), isNewStroke = false, willContinue = !p.lifting)
        }
        val newStrokeDelay = if (continuing.isEmpty()) 0L else NEW_STROKE_DELAY_MS
        for (p in starting) {
            if (segments.size >= MAX_STROKES) break
            val x = cx(p.pressX)
            val y = cy(p.pressY)
            segments += PointerSegment(p.key, x, y, x, y, isNewStroke = true, willContinue = true, startDelayMs = newStrokeDelay)
        }
        // Rule 7: an all-stationary continuation generates no events and is reported as failed.
        if (segments.none { it.isNewStroke || !it.willContinue || it.fromX != it.toX || it.fromY != it.toY }) return null

        for (s in segments) {
            val p = pointers.getValue(s.key)
            p.x = s.toX
            p.y = s.toY
            if (s.isNewStroke) p.releaseAt = p.holdMs?.let { now + it } ?: Long.MAX_VALUE
            p.down = true
            if (!s.willContinue) {
                pointers.remove(s.key)
                pending.remove(s.key)?.let { press(s.key, it.pressX, it.pressY, holdMs = it.holdMs, now = now) }
            }
        }
        inFlight++
        return GesturePlan(generation, segmentMs, segments)
    }

    fun onCompleted(generation: Int) {
        if (generation == this.generation && inFlight > 0) inFlight--
    }

    /** The system cancelled a gesture of [generation] (real touch, window change, failed continuation). */
    fun onCancelled(generation: Int, now: Long) {
        if (generation != this.generation) return
        restartGeneration(now + cancelBackoffMs)
    }

    fun onDispatchFailed(generation: Int, now: Long) = onCancelled(generation, now)

    /**
     * A real finger touched another window. From Android 16 (motion_event_injector_cancel_fix)
     * the platform no longer cancels our injection for this: InputDispatcher instead cancels the
     * injected stream inside the touched window and silently drops our continued MOVEs, while the
     * gesture callbacks still report success. So start a new generation ourselves: queued
     * continuations are abandoned and every held pointer re-presses with a fresh DOWN once the
     * real touch has had time to finish (a re-press during it would cancel the person's touch).
     *
     * @return true if anything was held and will be re-pressed.
     */
    fun onExternalTouch(now: Long): Boolean {
        if (pointers.isEmpty()) return false
        restartGeneration(now + externalTouchBackoffMs)
        return true
    }

    private fun restartGeneration(resumeAt: Long) {
        generation++
        inFlight = 0
        this.resumeAt = resumeAt
        val it = pointers.values.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.lifting) it.remove() else p.down = false // re-press at its press point
        }
    }

    fun snapshot(): List<PointerSnapshot> = pointers.values.map { PointerSnapshot(it.key, it.x, it.y, it.down) }

    private fun cx(x: Int) = x.coerceIn(0, (width - 1).coerceAtLeast(0))
    private fun cy(y: Int) = y.coerceIn(0, (height - 1).coerceAtLeast(0))

    companion object {
        /** GestureDescription.getMaxStrokeCount() is ≥ 10 on every supported release. */
        const val MAX_STROKES = 10
        const val JOYSTICK_KEY = 0
        const val NEW_STROKE_DELAY_MS = 1L
    }
}

/** Cumulative gyro aim from the pipeline (MotionFrame.aimX/aimY), in whole-range units. */
data class AimSample(val x: Double, val y: Double, val precision: Boolean = false)

/**
 * Turns the virtual-stick output (and, for [JoystickMode.AIM], the gyro aim) into the joystick
 * pointer's touch path.
 *
 * STICK: touch down at the anchor first (floating joysticks take their centre from the down
 * point), then drag to anchor + deflection × radius; lift after resting at neutral.
 * CAMERA_DRAG: rate control. Deflection sets the finger's velocity.
 * AIM: the finger moves by as much as the device turned (sensitivity × short screen side per
 * whole-range movement), and keeps turning while tilted beyond the edge-turn threshold.
 *
 * Both look-pad modes accumulate motion in sub-pixel precision and only move the finger by whole
 * pixels (slow aim is never rounded away). At the pad edge the finger lifts and re-grips at the
 * centre, like a thumb on a look pad, and motion during the re-grip is carried over, not lost.
 */
class JoystickDriver(var config: JoystickConfig, var aim: AimConfig = AimConfig()) {
    private var lastActiveAt = Long.MIN_VALUE / 2
    private var lastUpdate = -1L
    private var mode: JoystickMode? = null

    // Look pad (CAMERA_DRAG, AIM): finger position and motion not yet applied, in pixels.
    private var fingerX = 0.0
    private var fingerY = 0.0
    private var pendingX = 0.0
    private var pendingY = 0.0
    private var gripMoved = false
    private var lastAim: AimSample? = null
    private var lastAimMotionAt = Long.MIN_VALUE / 2

    /**
     * @param aimSample the newest cumulative aim; null when there is none (no session, stick
     *   locked), which re-anchors so the next sample causes no jump.
     */
    fun update(
        stick: StickOutput,
        live: Boolean,
        now: Long,
        width: Int,
        height: Int,
        planner: TouchPlanner,
        aimSample: AimSample? = null,
    ) {
        val dtSec = if (lastUpdate < 0) 0.0 else (now - lastUpdate).coerceIn(0, 50) / 1000.0
        lastUpdate = now
        val key = TouchPlanner.JOYSTICK_KEY
        if (mode != config.mode) {
            // Stick and aim pad are different places: never drag a held finger across.
            if (planner.has(key)) planner.release(key)
            mode = config.mode
            resetPad()
        }
        if (!live || config.mode == JoystickMode.OFF) {
            if (planner.has(key)) planner.release(key)
            lastActiveAt = Long.MIN_VALUE / 2
            resetPad()
            return
        }
        val minDim = min(width, height).toDouble()
        if (!stick.isNeutral) lastActiveAt = now
        val withinRelease = now - lastActiveAt < config.releaseAfterNeutralMs

        when (config.mode) {
            JoystickMode.STICK -> {
                val radius = config.radiusFraction * minDim
                val ax = config.centerX * width
                val ay = config.centerY * height
                if (stick.isNeutral && !withinRelease && !config.holdAtCenter) {
                    if (planner.has(key)) planner.release(key)
                    return
                }
                val tx = (ax + stick.x * radius).roundToInt()
                val ty = (ay + stick.y * radius).roundToInt()
                if (!planner.has(key)) {
                    planner.press(key, ax.roundToInt(), ay.roundToInt(), tx, ty, now = now)
                } else {
                    planner.moveTo(key, tx, ty)
                }
            }

            JoystickMode.CAMERA_DRAG -> {
                if (stick.isNeutral && !withinRelease) {
                    if (planner.has(key)) planner.release(key)
                    pendingX = 0.0
                    pendingY = 0.0
                    return
                }
                val scale = if (aimSample?.precision == true) aim.precisionScale else 1.0
                pendingX += stick.x * config.cameraSpeedPxPerSec * scale * dtSec
                pendingY += stick.y * config.cameraSpeedPxPerSec * scale * dtSec
                drivePad(key, planner, now, config.centerX * width, config.centerY * height, config.radiusFraction * minDim, width, height)
            }

            JoystickMode.AIM -> {
                val unitPx = aim.sensitivity * minDim
                var dx = 0.0
                var dy = 0.0
                val previous = lastAim
                if (aimSample != null && previous != null) {
                    dx = (aimSample.x - previous.x) * unitPx
                    dy = (aimSample.y - previous.y) * unitPx
                }
                lastAim = aimSample
                // Edge turn: a tilt held beyond the threshold keeps turning the view.
                val mag = stick.magnitude
                val from = aim.edgeTurnFrom
                if (from > 0 && from < 1 && mag > from) {
                    val excess = ((mag - from) / (1 - from)).coerceIn(0.0, 1.0)
                    val scale = if (aimSample?.precision == true) aim.precisionScale else 1.0
                    val speed = aim.edgeTurnSpeed * unitPx * excess * scale
                    dx += stick.x / mag * speed * dtSec
                    dy += stick.y / mag * speed * dtSec
                }
                pendingX += dx
                pendingY += dy
                if (planner.has(key) && !planner.isLifting(key) && now - lastAimMotionAt >= aim.releaseAfterIdleMs) {
                    planner.release(key) // resting: free the pad; the next move re-grips at the centre
                    return
                }
                // Resting means the finger hasn't moved a pixel: very slow aim still counts as aiming.
                if (drivePad(key, planner, now, aim.padX * width, aim.padY * height, aim.padRadiusFraction * minDim, width, height)) {
                    lastAimMotionAt = now
                }
            }

            JoystickMode.OFF -> Unit
        }
    }

    /**
     * Applies pending look-pad motion: press at the centre, drag, re-grip at the pad edge.
     * @return true if the finger was pressed, moved, or is being pushed against the screen edge.
     */
    private fun drivePad(key: Int, planner: TouchPlanner, now: Long, ax: Double, ay: Double, radius: Double, width: Int, height: Int): Boolean {
        if (!planner.has(key)) {
            if (hypot(pendingX, pendingY) < PAD_START_PX) return false // too little to be worth a touch yet
            fingerX = ax.roundToInt().toDouble().coerceIn(EDGE_MARGIN_PX, width - 1 - EDGE_MARGIN_PX)
            fingerY = ay.roundToInt().toDouble().coerceIn(EDGE_MARGIN_PX, height - 1 - EDGE_MARGIN_PX)
            gripMoved = false
            planner.press(key, fingerX.toInt(), fingerY.toInt(), now = now)
            return true // the motion is applied once the finger is down
        }
        if (planner.isLifting(key)) return false // re-gripping: keep accumulating
        // Apply at most half a pad per tick, so a fresh grip always moves before it can re-grip.
        val maxStep = (radius * 0.5).coerceAtLeast(1.0)
        val stepX = pendingX.coerceIn(-maxStep, maxStep)
        val stepY = pendingY.coerceIn(-maxStep, maxStep)
        var nx = fingerX + stepX
        var ny = fingerY + stepY
        var pushing = false
        val minX = maxOf(ax - radius, EDGE_MARGIN_PX)
        val maxX = minOf(ax + radius, width - 1 - EDGE_MARGIN_PX)
        val minY = maxOf(ay - radius, EDGE_MARGIN_PX)
        val maxY = minOf(ay + radius, height - 1 - EDGE_MARGIN_PX)
        if (nx < minX || nx > maxX || ny < minY || ny > maxY) {
            if (gripMoved) {
                planner.release(key) // lift here; the motion carries over to the new grip
                return true
            }
            // A fresh grip with no room this way (a pad at the screen edge): re-gripping would
            // only tap. Drop the motion that can't be applied instead.
            pushing = true // still aiming: don't let go as if resting
            nx = nx.coerceIn(minOf(minX, fingerX), maxOf(maxX, fingerX))
            ny = ny.coerceIn(minOf(minY, fingerY), maxOf(maxY, fingerY))
            pendingX = nx - fingerX
            pendingY = ny - fingerY
        }
        // Whole pixels only: the fraction stays pending, so slow motion adds up instead of vanishing.
        val tx = nx.roundToInt()
        val ty = ny.roundToInt()
        val moved = tx.toDouble() != fingerX || ty.toDouble() != fingerY
        pendingX -= tx - fingerX
        pendingY -= ty - fingerY
        fingerX = tx.toDouble()
        fingerY = ty.toDouble()
        if (moved) gripMoved = true
        planner.moveTo(key, tx, ty)
        return moved || pushing
    }

    private fun resetPad() {
        pendingX = 0.0
        pendingY = 0.0
        lastAim = null
        lastAimMotionAt = Long.MIN_VALUE / 2
    }

    private companion object {
        /** Pending motion needed before a new touch goes down. */
        const val PAD_START_PX = 2.0
        const val EDGE_MARGIN_PX = 2.0
    }
}
