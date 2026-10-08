package com.clu.motion.core.gesture

import com.clu.motion.core.response.StickOutput
import com.clu.motion.profile.DwellConfig
import com.clu.motion.profile.FlickConfig
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sign

/** Screen-space cardinal directions (y grows downward). */
enum class Direction4 { RIGHT, DOWN, LEFT, UP }

sealed interface DwellEvent {
    val direction: Direction4

    /** The dwell completed: fire the bound action (and keep it pressed for HOLD bindings). */
    data class Fired(override val direction: Direction4) : DwellEvent

    /** The stick left a zone whose dwell had fired: release HOLD bindings. */
    data class Ended(override val direction: Direction4) : DwellEvent
}

/**
 * Dwell-time trigger: holding the stick beyond [DwellConfig.enterThreshold] toward a cardinal
 * direction for [DwellConfig.dwellMs] fires that direction. Hysteresis (enter/exit thresholds)
 * and a short grace period stop tremor at the zone boundary from restarting the timer.
 */
class DwellDetector(private val config: DwellConfig) {
    var direction: Direction4? = null
        private set

    /** 0..1 progress of the current dwell, for the HUD ring. */
    var progress = 0.0
        private set

    private var enteredAt = 0L
    private var fired = false
    private var lastFiredAt = 0L
    private var graceStart = -1L

    fun update(stick: StickOutput, tMs: Long): DwellEvent? {
        val threshold = if (direction != null) config.exitThreshold else config.enterThreshold
        val candidate = if (stick.magnitude >= threshold) classify(stick) else null
        var event: DwellEvent? = null

        if (candidate != direction) {
            val current = direction
            if (current != null) {
                if (graceStart < 0) graceStart = tMs
                if (tMs - graceStart < config.graceMs) {
                    updateProgress(tMs)
                    return null
                }
                if (fired) event = DwellEvent.Ended(current)
            }
            direction = candidate
            enteredAt = tMs
            fired = false
        }
        graceStart = -1

        val dir = direction
        if (dir != null && event == null) {
            if (!fired && tMs - enteredAt >= config.dwellMs) {
                fired = true
                lastFiredAt = tMs
                event = DwellEvent.Fired(dir)
            } else if (fired && config.repeat && tMs - lastFiredAt >= config.repeatMs) {
                lastFiredAt = tMs
                event = DwellEvent.Fired(dir)
            }
        }
        updateProgress(tMs)
        return event
    }

    /** Ends any active dwell (e.g. on pause) and returns the matching release, if one is owed. */
    fun reset(): DwellEvent? {
        val ended = direction?.takeIf { fired }?.let { DwellEvent.Ended(it) }
        direction = null
        fired = false
        progress = 0.0
        graceStart = -1
        return ended
    }

    private fun updateProgress(tMs: Long) {
        progress = when {
            direction == null -> 0.0
            fired -> 1.0
            else -> ((tMs - enteredAt).toDouble() / config.dwellMs).coerceIn(0.0, 1.0)
        }
    }

    private fun classify(stick: StickOutput): Direction4? {
        val deg = Math.toDegrees(atan2(stick.y, stick.x)) // 0 = right, 90 = down
        val index = Math.floorMod(Math.round(deg / 90.0).toInt(), 4)
        val center = index * 90.0
        var delta = abs(deg - center) % 360.0
        if (delta > 180) delta = 360 - delta
        return if (delta <= config.toleranceDeg) Direction4.entries[index] else null
    }
}

/**
 * Detects a quick intentional flick (nod, wrist snap, twist) on one axis and reports its sign.
 *
 * Gates that separate a flick from everything else:
 *  - speed ≥ minSpeed (tremor peaks are slow at its small amplitude),
 *  - minAmplitude ≤ excursion ≤ maxAmplitude (tremor too small; spasms/gross moves too large),
 *  - out-and-back within windowMs (a deliberate repositioning stays put and times out),
 *  - refractory period so the return swing cannot fire the opposite direction.
 *
 * Excursion is measured from a slow baseline, so flicks work wherever the user is currently
 * holding the stick, not only at neutral.
 */
class FlickDetector(private val config: FlickConfig) {
    private enum class State { IDLE, TRACKING, REFRACTORY }

    /** Thresholds can be raised at runtime from the calibration tremor profile. */
    var minSpeed = config.minSpeedDegPerSec
    var minAmplitude = config.minAmplitudeDeg

    private var state = State.IDLE
    private var baseline = 0.0
    private var velocity = 0.0
    private var lastS = 0.0
    private var lastT = -1.0
    private var dir = 0.0
    private var peak = 0.0
    private var startT = 0.0
    private var refractoryUntil = 0.0

    /** @return +1 / -1 when a flick completes on this sample, else 0. */
    fun update(s: Double, tMs: Double): Int {
        if (lastT < 0) {
            baseline = s
            lastS = s
            lastT = tMs
            return 0
        }
        val dt = tMs - lastT
        if (dt <= 0) return 0
        velocity += 0.5 * ((s - lastS) / (dt / 1000.0) - velocity)
        lastS = s
        lastT = tMs

        when (state) {
            State.IDLE -> {
                if (abs(velocity) >= minSpeed) {
                    state = State.TRACKING
                    dir = sign(velocity)
                    startT = tMs
                    peak = dir * (s - baseline)
                } else {
                    trackBaseline(s, dt)
                }
            }

            State.TRACKING -> {
                val excursion = dir * (s - baseline)
                if (excursion > peak) peak = excursion
                when {
                    peak > config.maxAmplitudeDeg -> enterRefractory(tMs, s)
                    config.requireReturn && excursion <= peak * config.returnFraction -> {
                        if (peak >= minAmplitude) return fire(tMs, s)
                        state = State.IDLE // small oscillation: not a flick, re-arm immediately
                    }
                    !config.requireReturn && peak >= minAmplitude -> return fire(tMs, s)
                    tMs - startT > config.windowMs -> {
                        state = State.IDLE
                        baseline = s // deliberate move to a new position
                    }
                }
            }

            State.REFRACTORY -> {
                trackBaseline(s, dt)
                if (tMs >= refractoryUntil) {
                    state = State.IDLE
                    baseline = s
                }
            }
        }
        return 0
    }

    fun reset() {
        state = State.IDLE
        lastT = -1.0
        velocity = 0.0
    }

    private fun fire(tMs: Double, s: Double): Int {
        val result = dir.toInt()
        enterRefractory(tMs, s)
        return result
    }

    private fun enterRefractory(tMs: Double, s: Double) {
        state = State.REFRACTORY
        refractoryUntil = tMs + config.refractoryMs
        baseline = s
    }

    private fun trackBaseline(s: Double, dtMs: Double) {
        val a = 1 - exp(-dtMs / BASELINE_TAU_MS)
        baseline += a * (s - baseline)
    }

    private companion object {
        const val BASELINE_TAU_MS = 250.0
    }
}
