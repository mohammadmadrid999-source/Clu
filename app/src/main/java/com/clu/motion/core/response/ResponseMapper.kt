package com.clu.motion.core.response

import com.clu.motion.profile.CurveType
import com.clu.motion.profile.OutputMode
import com.clu.motion.profile.ResponseConfig
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/** Virtual stick deflection in screen orientation: x right, y down, magnitude ≤ 1. */
data class StickOutput(val x: Double, val y: Double) {
    val magnitude get() = hypot(x, y)
    val isNeutral get() = x == 0.0 && y == 0.0

    companion object {
        val ZERO = StickOutput(0.0, 0.0)
    }
}

/** Full-deflection tilt per direction, in degrees from neutral. */
data class AxisRanges(val posX: Double, val negX: Double, val posY: Double, val negY: Double) {
    val average get() = (posX + negX + posY + negY) / 4

    companion object {
        fun symmetric(deg: Double) = AxisRanges(deg, deg, deg, deg)
    }
}

/**
 * Tilt angles (degrees) → stick deflection:
 *  1. Scaled radial deadzone — no drift at rest, and no jump at the deadzone edge.
 *  2. Per-direction range normalisation — supports asymmetric range of motion.
 *  3. Response curve on the radial magnitude (direction preserved for precise diagonals).
 *  4. Anti-deadzone — leaps over the game's own joystick deadzone so micro-tilts still register.
 *  5. Optional direction snapping / digital 4- or 8-way output.
 */
class ResponseMapper(
    private val config: ResponseConfig,
    private val ranges: AxisRanges,
    /** May exceed config.deadzoneDeg when auto-tuned from measured tremor. */
    val deadzoneDeg: Double,
) {
    fun map(xDeg: Double, yDeg: Double): StickOutput {
        val r = hypot(xDeg, yDeg)
        if (r <= deadzoneDeg || r == 0.0) return StickOutput.ZERO

        val scale = (r - deadzoneDeg) / r
        val ax = xDeg * scale
        val ay = yDeg * scale
        val nx = ax / span(if (ax >= 0) ranges.posX else ranges.negX)
        val ny = ay / span(if (ay >= 0) ranges.posY else ranges.negY)

        val m = hypot(nx, ny)
        if (m == 0.0) return StickOutput.ZERO
        var angle = atan2(ny, nx)
        val magnitude = m.coerceAtMost(1.0)

        val out: Double
        when (config.outputMode) {
            OutputMode.ANALOG -> {
                if (config.angleSnapDeg > 0) angle = snap(angle, 45.0, config.angleSnapDeg)
                var o = ResponseCurves.apply(config, magnitude)
                if (config.antiDeadzone > 0) o = config.antiDeadzone + (1 - config.antiDeadzone) * o
                out = o
            }

            OutputMode.DIGITAL_8, OutputMode.DIGITAL_4 -> {
                if (magnitude < config.digitalThreshold) return StickOutput.ZERO
                val step = if (config.outputMode == OutputMode.DIGITAL_8) 45.0 else 90.0
                angle = snap(angle, step, 180.0)
                out = 1.0
            }
        }
        return StickOutput(cos(angle) * out, sin(angle) * out)
    }

    /** Degrees available between the deadzone edge and full deflection (never degenerate). */
    private fun span(rangeDeg: Double) = (rangeDeg - deadzoneDeg).coerceAtLeast(MIN_SPAN_DEG)

    private fun snap(angleRad: Double, stepDeg: Double, windowDeg: Double): Double {
        val deg = Math.toDegrees(angleRad)
        val target = round(deg / stepDeg) * stepDeg
        return if (abs(deg - target) <= windowDeg) Math.toRadians(target) else angleRad
    }

    private companion object {
        const val MIN_SPAN_DEG = 0.5
    }
}

object ResponseCurves {
    /** Maps magnitude in [0,1] → [0,1]; monotonic with f(0)=0, f(1)=1 for every curve type. */
    fun apply(config: ResponseConfig, m: Double): Double {
        val v = m.coerceIn(0.0, 1.0)
        return when (config.curve) {
            CurveType.LINEAR -> v
            CurveType.POWER -> v.pow(config.exponent.coerceIn(0.2, 4.0))
            CurveType.SIGMOID -> normalizedSigmoid(v, config.sigmoidSteepness, config.sigmoidMidpoint)
        }
    }

    /**
     * Logistic curve rescaled to pass through (0,0) and (1,1). A low midpoint with high steepness
     * reaches full output from small tilts (micro-movement boost) while staying soft at rest.
     */
    fun normalizedSigmoid(m: Double, steepness: Double, midpoint: Double): Double {
        val k = steepness.coerceIn(0.5, 30.0)
        val c = midpoint.coerceIn(0.0, 1.0)
        val l0 = logistic(-k * c)
        val l1 = logistic(k * (1 - c))
        return ((logistic(k * (m - c)) - l0) / (l1 - l0)).coerceIn(0.0, 1.0)
    }

    private fun logistic(t: Double) = 1.0 / (1.0 + exp(-t))
}
