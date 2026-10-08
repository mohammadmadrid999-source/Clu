package com.clu.motion.core.filter

import com.clu.motion.profile.FilterConfig
import com.clu.motion.profile.FilterType
import kotlin.math.PI
import kotlin.math.hypot

/** A 2-D smoother over the projected tilt angles (degrees). Allocation-free per sample. */
interface TremorFilter2D {
    val x: Double
    val y: Double
    fun update(x: Double, y: Double, tSec: Double)
    fun reset(x: Double = 0.0, y: Double = 0.0)

    companion object {
        fun from(config: FilterConfig): TremorFilter2D = when (config.type) {
            FilterType.ONE_EURO -> OneEuroFilter2D(config.minCutoffHz, config.beta, config.derivativeCutoffHz)
            FilterType.KALMAN -> KalmanFilter2D(config.kalmanProcessNoise, config.kalmanMeasurementNoise)
            FilterType.NONE -> PassThroughFilter2D()
        }
    }
}

/**
 * One Euro filter (Casiez, Roussel & Vogel, CHI 2012): an exponential moving average whose
 * cutoff rises with movement speed — heavy smoothing at rest (kills tremor and drift), little
 * lag during deliberate motion.
 *
 * Two deliberate choices for tremor:
 *  - The speed estimate is itself low-passed at [derivativeCutoffHz] (≤ 1 Hz). A 4–12 Hz tremor
 *    is mostly removed from that estimate, so the oscillation cannot open the filter; a sustained
 *    intentional movement can.
 *  - Both axes share one cutoff driven by 2-D speed, so diagonals don't bend (one axis lagging).
 */
class OneEuroFilter2D(
    var minCutoffHz: Double,
    var beta: Double,
    var derivativeCutoffHz: Double,
) : TremorFilter2D {
    override var x = 0.0
        private set
    override var y = 0.0
        private set
    private var dx = 0.0
    private var dy = 0.0
    private var lastT = 0.0
    private var initialized = false

    override fun update(x: Double, y: Double, tSec: Double) {
        if (!initialized) {
            reset(x, y)
            lastT = tSec
            initialized = true
            return
        }
        val dt = tSec - lastT
        if (dt <= 1e-6) return // duplicate or out-of-order timestamp
        lastT = tSec

        val ad = alpha(dt, derivativeCutoffHz)
        dx += ad * ((x - this.x) / dt - dx)
        dy += ad * ((y - this.y) / dt - dy)

        val cutoff = minCutoffHz + beta * hypot(dx, dy)
        val a = alpha(dt, cutoff)
        this.x += a * (x - this.x)
        this.y += a * (y - this.y)
    }

    override fun reset(x: Double, y: Double) {
        this.x = x
        this.y = y
        dx = 0.0
        dy = 0.0
        initialized = false
    }

    private fun alpha(dt: Double, cutoffHz: Double): Double {
        val tau = 1.0 / (2 * PI * cutoffHz)
        return 1.0 / (1.0 + tau / dt)
    }
}

/**
 * Constant-velocity Kalman filter per axis. Its motion model predicts ahead, so on smooth
 * ramps it lags less than an EMA of equal steadiness; it does not adapt to speed like One Euro.
 */
class KalmanFilter2D(processNoise: Double, measurementNoise: Double) : TremorFilter2D {
    private val fx = ConstantVelocityKalman(processNoise, measurementNoise)
    private val fy = ConstantVelocityKalman(processNoise, measurementNoise)
    override val x get() = fx.position
    override val y get() = fy.position

    override fun update(x: Double, y: Double, tSec: Double) {
        fx.update(x, tSec)
        fy.update(y, tSec)
    }

    override fun reset(x: Double, y: Double) {
        fx.reset(x)
        fy.reset(y)
    }
}

class ConstantVelocityKalman(var q: Double, var r: Double) {
    var position = 0.0
        private set
    var velocity = 0.0
        private set
    private var p00 = 0.0
    private var p01 = 0.0
    private var p11 = 0.0
    private var lastT = 0.0
    private var initialized = false

    fun update(z: Double, tSec: Double) {
        if (!initialized) {
            reset(z)
            lastT = tSec
            initialized = true
            return
        }
        val dt = tSec - lastT
        if (dt <= 1e-6) return
        lastT = tSec

        // Predict: x = F x, P = F P Fᵀ + Q (continuous white-noise acceleration).
        position += velocity * dt
        val dt2 = dt * dt
        p00 += 2 * dt * p01 + dt2 * p11 + q * dt2 * dt / 3
        p01 += dt * p11 + q * dt2 / 2
        p11 += q * dt

        // Update with the position measurement.
        val s = p00 + r
        val k0 = p00 / s
        val k1 = p01 / s
        val innovation = z - position
        position += k0 * innovation
        velocity += k1 * innovation
        p11 -= k1 * p01
        p01 *= (1 - k0)
        p00 *= (1 - k0)
    }

    fun reset(z: Double) {
        position = z
        velocity = 0.0
        p00 = r
        p01 = 0.0
        p11 = 100.0
        initialized = false
    }
}

class PassThroughFilter2D : TremorFilter2D {
    override var x = 0.0
        private set
    override var y = 0.0
        private set

    override fun update(x: Double, y: Double, tSec: Double) {
        this.x = x
        this.y = y
    }

    override fun reset(x: Double, y: Double) {
        this.x = x
        this.y = y
    }
}

/**
 * Spasm / involuntary-jerk rejection, ahead of the smoother. When angular speed exceeds
 * [triggerSpeedDegPerSec] the output freezes at the last pre-jerk value; once the jerk settles
 * (or [maxHoldMs] passes) it glides to the live value over [glideMs] instead of snapping, so a
 * spasm never slams the virtual stick. If the user really did reposition, the glide follows.
 */
class SpasmGate(
    var triggerSpeedDegPerSec: Double,
    var maxHoldMs: Long,
    var glideMs: Long,
) {
    var x = 0.0
        private set
    var y = 0.0
        private set
    var holding = false
        private set

    /** Holding or gliding back: the output is not following the input. */
    val engaged get() = state != State.PASS

    private enum class State { PASS, HOLD, GLIDE }

    private var state = State.PASS
    private var lastInX = 0.0
    private var lastInY = 0.0
    private var lastT = -1.0
    private var speed = 0.0
    private var holdStart = 0.0
    private var glideStart = 0.0
    private var fromX = 0.0
    private var fromY = 0.0

    fun update(inX: Double, inY: Double, tMs: Double) {
        if (lastT >= 0 && tMs > lastT) {
            val inst = hypot(inX - lastInX, inY - lastInY) / ((tMs - lastT) / 1000.0)
            speed += 0.5 * (inst - speed)
        }
        lastInX = inX
        lastInY = inY
        lastT = tMs

        when (state) {
            State.PASS -> {
                if (speed > triggerSpeedDegPerSec) {
                    state = State.HOLD
                    holdStart = tMs
                    // x/y still hold the previous (pre-jerk) output.
                } else {
                    x = inX
                    y = inY
                }
            }

            State.HOLD -> {
                val held = tMs - holdStart
                val settled = speed < triggerSpeedDegPerSec * 0.5 && held >= MIN_HOLD_MS
                if (settled || held >= maxHoldMs) {
                    state = State.GLIDE
                    glideStart = tMs
                    fromX = x
                    fromY = y
                }
            }

            State.GLIDE -> {
                if (speed > triggerSpeedDegPerSec) {
                    state = State.HOLD
                    holdStart = tMs
                } else {
                    val k = if (glideMs <= 0) 1.0 else ((tMs - glideStart) / glideMs).coerceIn(0.0, 1.0)
                    val s = k * k * (3 - 2 * k) // smoothstep
                    x = fromX + (inX - fromX) * s
                    y = fromY + (inY - fromY) * s
                    if (k >= 1.0) state = State.PASS
                }
            }
        }
        holding = state == State.HOLD
    }

    fun reset(x: Double = 0.0, y: Double = 0.0) {
        state = State.PASS
        this.x = x
        this.y = y
        lastInX = x
        lastInY = y
        lastT = -1.0
        speed = 0.0
        holding = false
    }

    private companion object {
        const val MIN_HOLD_MS = 60.0
    }
}
