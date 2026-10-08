package com.clu.motion.core.calibration

import com.clu.motion.core.math.Quaternion
import com.clu.motion.core.math.RAD_TO_DEG
import com.clu.motion.core.math.Vec3
import com.clu.motion.profile.LearnedAxes
import kotlin.math.sqrt

/** Involuntary-motion statistics measured while the user held still. */
data class TremorProfile(
    /** RMS angular deviation from the mean pose, degrees. */
    val rmsDeg: Double,
    val peakDeg: Double,
    /** Dominant oscillation frequency estimate (zero crossings), Hz. 0 if not measurable. */
    val dominantHz: Double,
    /** RMS angular speed while holding still, deg/s. */
    val speedRmsDegPerSec: Double,
) {
    companion object {
        val NONE = TremorProfile(0.0, 0.0, 0.0, 0.0)
    }
}

data class CalibrationResult(
    val neutral: Quaternion,
    val tremor: TremorProfile,
    /** True if every attempt saw more motion than allowed; the best effort was accepted. */
    val unstable: Boolean,
)

/**
 * "Hold still" capture that defines the ergonomic zero point (any posture: lying down,
 * reclined wheelchair, head-mounted). Averaging the whole window — rather than taking one
 * sample — keeps a tremor peak from becoming a permanently off-center neutral.
 *
 * A settle period first lets the device stop moving after the user triggered calibration
 * (tapping the HUD with the same hand that holds the phone moves it).
 */
class CalibrationCapture(
    private val settleMs: Long = 500,
    private val captureMs: Long = 1500,
    private val maxDeviationDeg: Double = 8.0,
    private val maxAttempts: Int = 3,
) {
    sealed interface Status {
        data class Settling(val progress: Double) : Status
        data class Capturing(val progress: Double) : Status
        data class Done(val result: CalibrationResult) : Status
    }

    private val quats = ArrayList<Quaternion>(256)
    private val times = ArrayList<Long>(256)
    private var startedAt = -1L
    private var attempt = 1

    fun begin() {
        startedAt = -1L
        attempt = 1
        quats.clear()
        times.clear()
    }

    /** Feed every orientation sample; returns progress until [Status.Done]. */
    fun add(q: Quaternion, tMs: Long): Status {
        if (startedAt < 0) startedAt = tMs
        val elapsed = tMs - startedAt
        if (elapsed < settleMs) return Status.Settling(elapsed.toDouble() / settleMs)

        quats += q
        times += tMs
        val captured = elapsed - settleMs
        if (captured < captureMs) return Status.Capturing(captured.toDouble() / captureMs)

        val neutral = Quaternion.average(quats)
        val tremor = analyze(neutral)
        if (tremor.peakDeg > maxDeviationDeg && attempt < maxAttempts) {
            attempt++
            quats.clear()
            times.clear()
            startedAt = tMs - settleMs // retry immediately, no second settle
            return Status.Capturing(0.0)
        }
        return Status.Done(CalibrationResult(neutral, tremor, unstable = tremor.peakDeg > maxDeviationDeg))
    }

    private fun analyze(neutral: Quaternion): TremorProfile {
        val inv = neutral.conjugate()
        val n = quats.size
        if (n < 3) return TremorProfile.NONE
        val dev = Array(n) { (inv * quats[it]).toRotationVector() * RAD_TO_DEG }

        var sumSq = 0.0
        var peak = 0.0
        val mean = dev.fold(Vec3.ZERO) { acc, v -> acc + v } * (1.0 / n)
        var vx = 0.0
        var vy = 0.0
        var vz = 0.0
        for (d in dev) {
            val l2 = d dot d
            sumSq += l2
            if (l2 > peak * peak) peak = sqrt(l2)
            val c = d - mean
            vx += c.x * c.x
            vy += c.y * c.y
            vz += c.z * c.z
        }

        var speedSq = 0.0
        for (i in 1 until n) {
            val dt = (times[i] - times[i - 1]) / 1000.0
            if (dt > 0) {
                val s = (dev[i] - dev[i - 1]).length() / dt
                speedSq += s * s
            }
        }

        // Dominant frequency from zero crossings on the axis with the most variance.
        val axis: (Vec3) -> Double = when {
            vx >= vy && vx >= vz -> { v -> v.x - mean.x }
            vy >= vz -> { v -> v.y - mean.y }
            else -> { v -> v.z - mean.z }
        }
        var crossings = 0
        for (i in 1 until n) {
            if ((axis(dev[i - 1]) < 0) != (axis(dev[i]) < 0)) crossings++
        }
        val duration = (times[n - 1] - times[0]) / 1000.0
        val hz = if (duration > 0) crossings / (2 * duration) else 0.0

        return TremorProfile(
            rmsDeg = sqrt(sumSq / n),
            peakDeg = peak,
            dominantHz = hz,
            speedRmsDegPerSec = sqrt(speedSq / (n - 1)),
        )
    }
}

/**
 * Range-of-motion learning: the user performs RIGHT, LEFT, UP (forward) and DOWN (back) in
 * whatever way their body allows — wrist, head, shoulder, compound diagonals — and each move's
 * peak rotation becomes that direction's control axis and range. Full stick deflection is then
 * set to [rangeFraction] of the comfortable range, so users never have to strain to the limit.
 *
 * Driven purely by sample timestamps, so it runs on the sensor thread without coordination.
 */
class AxisLearner(
    private val moveMs: Long = 3500,
    private val returnMs: Long = 1500,
    private val minMotionDeg: Double = 3.0,
    private val rangeFraction: Double = 0.7,
) {
    enum class Step { RIGHT, LEFT, UP, DOWN }

    sealed interface Status {
        /** [returning] = the "come back to center" pause after a move. */
        data class Prompt(val step: Step, val returning: Boolean, val progress: Double) : Status
        data class Done(val peaks: Map<Step, Vec3>) : Status
    }

    private val peaks = HashMap<Step, Vec3>()
    private var stepIndex = 0
    private var phaseStart = -1L
    private var returning = false
    private var smoothed = Vec3.ZERO

    fun begin() {
        peaks.clear()
        stepIndex = 0
        phaseStart = -1L
        returning = false
        smoothed = Vec3.ZERO
    }

    /** [omegaDeg]: rotation vector from neutral, degrees, in the neutral device frame. */
    fun add(omegaDeg: Vec3, tMs: Long): Status {
        if (phaseStart < 0) phaseStart = tMs
        val step = Step.entries[stepIndex]
        val elapsed = tMs - phaseStart
        smoothed = smoothed + (omegaDeg - smoothed) * SMOOTHING

        if (!returning) {
            val best = peaks[step]
            if (smoothed.length() >= minMotionDeg && (best == null || smoothed.length() > best.length())) {
                peaks[step] = smoothed
            }
            if (elapsed >= moveMs) {
                returning = true
                phaseStart = tMs
            }
            return Status.Prompt(step, returning = false, progress = elapsed.toDouble() / moveMs)
        }

        if (elapsed >= returnMs) {
            returning = false
            phaseStart = tMs
            stepIndex++
            if (stepIndex >= Step.entries.size) return Status.Done(peaks.toMap())
        }
        return Status.Prompt(step, returning = true, progress = elapsed.toDouble() / returnMs)
    }

    /**
     * Builds orthonormal control axes from the recorded peaks. Missing directions (a user who
     * cannot move that way) fall back to [fallbackEx]/[fallbackEy] and [defaultRangeDeg].
     */
    fun result(
        peaks: Map<Step, Vec3>,
        fallbackEx: Vec3,
        fallbackEy: Vec3,
        referenceRotation: Int,
        defaultRangeDeg: Double,
    ): LearnedAxes {
        val right = peaks[Step.RIGHT]
        val left = peaks[Step.LEFT]
        val down = peaks[Step.DOWN]
        val up = peaks[Step.UP]

        val ex = when {
            right != null && left != null -> (right - left).normalized()
            right != null -> right.normalized()
            left != null -> (-left).normalized()
            else -> fallbackEx
        }
        val eyRaw = when {
            down != null && up != null -> (down - up).normalized()
            down != null -> down.normalized()
            up != null -> (-up).normalized()
            else -> fallbackEy
        }
        // Gram–Schmidt: make Y orthogonal to X so the two controls don't bleed into each other.
        var ey = (eyRaw - ex * (eyRaw dot ex)).normalized()
        if (ey == Vec3.ZERO) ey = (fallbackEy - ex * (fallbackEy dot ex)).normalized()

        fun range(peak: Vec3?, axis: Vec3, sign: Double): Double {
            val extent = peak?.let { sign * (it dot axis) } ?: return defaultRangeDeg
            return if (extent < minMotionDeg) defaultRangeDeg else (extent * rangeFraction).coerceAtLeast(MIN_RANGE_DEG)
        }

        return LearnedAxes(
            ex = ex,
            ey = ey,
            referenceRotation = referenceRotation,
            rangePosX = range(right, ex, 1.0),
            rangeNegX = range(left, ex, -1.0),
            rangePosY = range(down, ey, 1.0),
            rangeNegY = range(up, ey, -1.0),
        )
    }

    private companion object {
        const val SMOOTHING = 0.2
        const val MIN_RANGE_DEG = 2.0
    }
}
