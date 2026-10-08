package com.clu.motion.core.diag

import com.clu.motion.core.response.StickOutput
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Synthetic input for the on-device injection self-test: no sensors, no calibration, no game.
 *
 * The virtual stick eases out from centre and then circles, while a button is tapped
 * periodically. That exercises exactly the risky platform paths: an unbounded continued drag,
 * a new finger joining a continuing gesture, and that finger lifting while the drag continues.
 */
data class SelfTest(
    val startedAtMs: Long,
    val circlePeriodMs: Long = 2_000,
    val amplitude: Double = 0.8,
    val rampMs: Long = 600,
    val tapEveryMs: Long = 1_200,
    val tapButtonId: Int = 1,
) {
    fun stick(nowMs: Long): StickOutput {
        val t = (nowMs - startedAtMs).coerceAtLeast(0)
        val ramp = min(1.0, t.toDouble() / rampMs)
        val angle = 2 * PI * t / circlePeriodMs
        return StickOutput(amplitude * ramp * cos(angle), amplitude * ramp * sin(angle))
    }

    /** Index of the periodic tap due at [nowMs]; a new index means "tap now". 0 = none yet. */
    fun tapIndex(nowMs: Long): Long = ((nowMs - startedAtMs) / tapEveryMs).coerceAtLeast(0)
}
