package com.clu.motion.core.aim

import com.clu.motion.core.response.AxisRanges
import com.clu.motion.profile.AimConfig
import kotlin.math.hypot

/**
 * Gyro-style aim: turns the change in filtered tilt into an aim displacement.
 *
 * Rate control (tilt sets camera speed) is hard to aim with when movement is slow or imprecise:
 * the aim keeps moving until the tilt is brought back exactly to neutral, so slow returns
 * overshoot, and an unsteady hold gives an unsteady speed. Here the aim moves only while the
 * device moves, by as much as it moved, and stops when it stops, like gyro aiming in console
 * games. Per sample (input already through the spasm gate and the tremor filter):
 *
 *  1. Each axis is normalised by its own range on the side of neutral it is on, so 1 unit is
 *     always one whole-range movement, also for small or asymmetric ranges.
 *  2. Soft tiered smoothing (as in JoyShockMapper): each sample's motion is split by its speed.
 *     Below [AimConfig.smoothBelowDegPerSec] it all goes through a moving average over
 *     [smoothWindowSec]; above twice that it all passes directly; in between it is shared. Every
 *     bit of motion comes out exactly once, so smoothing adds lag to small corrections but never
 *     overshoot. A window of one tremor period cancels that tremor's leftover oscillation.
 *  3. Tightening: below [steadyBelowDegPerSec] (speed of the smoothed result) the motion is
 *     scaled by speed / threshold, so slow sensor drift and what is left of tremor don't creep
 *     the aim.
 *  4. Optional acceleration: faster motion is multiplied by up to [AimConfig.accelerationMax].
 *  5. Precision aim multiplies everything by [AimConfig.precisionScale].
 *
 * The output [x], [y] is cumulative (aim units). Consumers difference it, so a sample lost to
 * conflation loses no motion. Not thread-safe: confine to the sensor thread.
 */
class AimProcessor(config: AimConfig) {

    var config = config
        set(value) {
            field = value
            steadyBelowDegPerSec = value.steadyBelowDegPerSec
            smoothWindowSec = value.smoothWindowMs / 1000.0
        }

    /** Cumulative aim, in whole-range units (screen-right / screen-down positive). */
    var x = 0.0
        private set
    var y = 0.0
        private set

    var precision = false

    /** Effective tightening threshold: the configured one, possibly raised from measured tremor. */
    var steadyBelowDegPerSec = config.steadyBelowDegPerSec

    /** Effective smoothing window: the configured one, possibly matched to the measured tremor. */
    var smoothWindowSec = config.smoothWindowMs / 1000.0

    var ranges = AxisRanges.symmetric(18.0)

    private var anchored = false
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastT = 0.0

    // Moving-average window of recent motion, in aim units (ring buffer).
    private val windowDt = DoubleArray(WINDOW_CAPACITY)
    private val windowDx = DoubleArray(WINDOW_CAPACITY)
    private val windowDy = DoubleArray(WINDOW_CAPACITY)
    private val windowDegX = DoubleArray(WINDOW_CAPACITY)
    private val windowDegY = DoubleArray(WINDOW_CAPACITY)
    private var windowStart = 0
    private var windowSize = 0
    private var sumDt = 0.0
    private var sumDx = 0.0
    private var sumDy = 0.0
    private var sumDegX = 0.0
    private var sumDegY = 0.0

    /** Forget the last sample: the next one re-anchors without moving the aim. */
    fun reset() {
        anchored = false
        clearWindow()
    }

    /**
     * @param fx filtered tilt along the control X axis, degrees from neutral.
     * @param suppressed true while the signal must not move the aim (e.g. a spasm being gated).
     */
    fun update(fx: Double, fy: Double, tSec: Double, suppressed: Boolean = false) {
        val dt = tSec - lastT
        if (!anchored || suppressed || dt <= 0 || dt > MAX_GAP_SEC) {
            anchor(fx, fy, tSec)
            clearWindow()
            return
        }
        val dx = unit(fx, ranges.posX, ranges.negX) - unit(lastX, ranges.posX, ranges.negX)
        val dy = unit(fy, ranges.posY, ranges.negY) - unit(lastY, ranges.posY, ranges.negY)
        val degX = fx - lastX
        val degY = fy - lastY
        anchor(fx, fy, tSec)

        val c = config
        val smoothBelow = c.smoothBelowDegPerSec
        val direct = if (smoothBelow > 0) ((hypot(degX, degY) / dt - smoothBelow) / smoothBelow).coerceIn(0.0, 1.0) else 1.0
        val smoothed = 1.0 - direct
        push(dt, dx * smoothed, dy * smoothed, degX * smoothed, degY * smoothed)
        // Each buffered portion is paid out over the window (zero history before it), so it is
        // emitted exactly once in total.
        val share = dt / maxOf(sumDt, smoothWindowSec)
        val outX = dx * direct + sumDx * share
        val outY = dy * direct + sumDy * share
        val speed = hypot(degX * direct + sumDegX * share, degY * direct + sumDegY * share) / dt

        var gain = 1.0
        val steady = steadyBelowDegPerSec
        if (steady > 0 && speed < steady) gain *= speed / steady
        if (c.accelerationMax > 1.0 && c.accelerationFullDegPerSec > c.accelerationStartDegPerSec) {
            val k = ((speed - c.accelerationStartDegPerSec) / (c.accelerationFullDegPerSec - c.accelerationStartDegPerSec)).coerceIn(0.0, 1.0)
            gain *= 1.0 + (c.accelerationMax - 1.0) * k
        }
        if (precision) gain *= c.precisionScale

        x += outX * gain
        y += outY * gain
    }

    private fun anchor(fx: Double, fy: Double, tSec: Double) {
        lastX = fx
        lastY = fy
        lastT = tSec
        anchored = true
    }

    private fun push(dt: Double, dx: Double, dy: Double, degX: Double, degY: Double) {
        if (windowSize == WINDOW_CAPACITY) dropOldest()
        val i = (windowStart + windowSize) % WINDOW_CAPACITY
        windowDt[i] = dt
        windowDx[i] = dx
        windowDy[i] = dy
        windowDegX[i] = degX
        windowDegY[i] = degY
        windowSize++
        sumDt += dt
        sumDx += dx
        sumDy += dy
        sumDegX += degX
        sumDegY += degY
        // Keep exactly the samples of the last window (each stays for window / dt samples).
        while (windowSize > 1 && sumDt - windowDt[windowStart] >= smoothWindowSec - WINDOW_EPS_SEC) dropOldest()
    }

    private fun dropOldest() {
        val i = windowStart
        sumDt -= windowDt[i]
        sumDx -= windowDx[i]
        sumDy -= windowDy[i]
        sumDegX -= windowDegX[i]
        sumDegY -= windowDegY[i]
        windowStart = (windowStart + 1) % WINDOW_CAPACITY
        windowSize--
    }

    private fun clearWindow() {
        windowStart = 0
        windowSize = 0
        sumDt = 0.0
        sumDx = 0.0
        sumDy = 0.0
        sumDegX = 0.0
        sumDegY = 0.0
    }

    /** Degrees → whole-range units, using the range on this side of neutral (continuous at 0). */
    private fun unit(deg: Double, pos: Double, neg: Double) =
        if (deg >= 0) deg / pos.coerceAtLeast(MIN_RANGE_DEG) else deg / neg.coerceAtLeast(MIN_RANGE_DEG)

    private companion object {
        /** A longer gap (sensor stall, suspend) re-anchors instead of jumping. */
        const val MAX_GAP_SEC = 0.25
        const val MIN_RANGE_DEG = 1.0
        /** Enough samples for a 0.3 s window at over 800 Hz. */
        const val WINDOW_CAPACITY = 256
        const val WINDOW_EPS_SEC = 1e-6
    }
}
