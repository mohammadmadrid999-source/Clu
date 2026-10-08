package com.clu.motion.core

import com.clu.motion.core.aim.AimProcessor
import com.clu.motion.core.response.AxisRanges
import com.clu.motion.profile.AimConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AimProcessorTest {
    private var t = 0.0

    private fun aim(config: AimConfig = AimConfig(steadyBelowDegPerSec = 0.0), range: Double = 18.0) =
        AimProcessor(config).apply { ranges = AxisRanges.symmetric(range) }

    /** Feeds [seconds] of 100 Hz samples from [x], [y] (degrees, as functions of elapsed seconds). */
    private fun AimProcessor.feed(seconds: Double, x: (Double) -> Double, y: (Double) -> Double = { 0.0 }) {
        val start = t
        val n = (seconds * 100).toInt()
        repeat(n) {
            update(x(t - start), y(t - start), t)
            t += 0.01
        }
    }

    @Test
    fun aimFollowsTheChangeInTiltAndHoldsWhenStill() {
        val a = aim()
        a.feed(0.5, { 0.0 })
        a.feed(1.0, { s -> 9.0 * s }) // half the 18° range, slowly
        a.feed(0.5, { 9.0 })
        assertEquals(0.5, a.x, 0.01)
        val held = a.x
        a.feed(2.0, { 9.0 })
        assertEquals("no drift while still", held, a.x, 1e-9)
        a.feed(1.0, { s -> 9.0 - 9.0 * s })
        a.feed(0.5, { 0.0 })
        assertEquals("coming back brings the aim back", 0.0, a.x, 0.01)
    }

    @Test
    fun slowMovementIsNotLost() {
        // 2°/s for 3 s: much slower than a rate-controlled camera could be steered precisely.
        val a = aim(AimConfig(steadyBelowDegPerSec = 1.0))
        a.feed(3.0, { s -> 2.0 * s }, { s -> -1.0 * s })
        a.feed(0.5, { 6.0 }, { -3.0 })
        assertEquals(6.0 / 18, a.x, 0.005)
        assertEquals(-3.0 / 18, a.y, 0.005)
    }

    @Test
    fun tighteningSuppressesDriftAndResidualTremorButPassesDeliberateMotion() {
        val a = aim(AimConfig(steadyBelowDegPerSec = 1.0))
        a.feed(10.0, { s -> 0.1 * s }) // 1° of slow drift
        assertTrue("drift moved the aim by ${a.x * 18}°", abs(a.x * 18) < 0.15)

        // Tremor that survived the tremor filter: ±0.01° at 5 Hz. The aim shakes far less.
        var lo = a.x
        var hi = a.x
        val s0 = t
        repeat(500) {
            a.update(1.0 + 0.01 * sin(2 * PI * 5 * (t - s0)), 0.0, t)
            lo = minOf(lo, a.x)
            hi = maxOf(hi, a.x)
            t += 0.01
        }
        assertTrue("aim shook ${(hi - lo) * 18}° for 0.02° of tremor", (hi - lo) * 18 < 0.004)

        val start = a.x
        a.feed(1.0, { s -> 1.0 + 10.0 * s })
        a.feed(0.5, { 11.0 })
        assertEquals(10.0 / 18, a.x - start, 0.01)
    }

    @Test
    fun smoothingNeverOvershootsAfterAQuickTurn() {
        val a = aim(AimConfig(steadyBelowDegPerSec = 0.0, smoothBelowDegPerSec = 10.0, smoothWindowMs = 250))
        a.feed(0.1, { 0.0 })
        // A quick 9° turn (60°/s) easing into a slow 0.9° correction (3°/s), then stop.
        a.feed(0.15, { s -> 60.0 * s })
        a.feed(0.3, { s -> 9.0 + 3.0 * s })
        var peak = a.x
        val s0 = t
        repeat(100) {
            a.update(9.9, 0.0, t)
            peak = maxOf(peak, a.x)
            t += 0.01
        }
        assertEquals(9.9 / 18, a.x, 0.002)
        assertTrue("overshoot ${(peak - a.x) * 18}°", peak <= a.x + 1e-9)
    }

    @Test
    fun rangesArePerSideSoSmallAndAsymmetricMovementsReachAsFar() {
        val a = AimProcessor(AimConfig(steadyBelowDegPerSec = 0.0)).apply { ranges = AxisRanges(10.0, 5.0, 4.0, 4.0) }
        a.feed(0.1, { 0.0 })
        a.feed(1.0, { s -> 10.0 * s })
        a.feed(0.5, { 10.0 })
        assertEquals(1.0, a.x, 0.01)
        a.feed(1.0, { s -> 10.0 - 15.0 * s }) // through neutral to the short side: -5°
        a.feed(0.5, { -5.0 })
        assertEquals(1.0 - 2.0, a.x, 0.02)
    }

    @Test
    fun precisionScalesTheAimDown() {
        val a = aim(AimConfig(steadyBelowDegPerSec = 0.0, precisionScale = 0.25))
        a.precision = true
        a.feed(1.0, { s -> 18.0 * s })
        a.feed(0.5, { 18.0 })
        assertEquals(0.25, a.x, 0.01)
    }

    @Test
    fun accelerationBoostsOnlyFastMotion() {
        val config = AimConfig(steadyBelowDegPerSec = 0.0, accelerationMax = 3.0, accelerationStartDegPerSec = 15.0, accelerationFullDegPerSec = 60.0)
        val slow = aim(config)
        slow.feed(1.0, { s -> 9.0 * s }) // 9°/s: below the start, unchanged
        slow.feed(0.5, { 9.0 })
        assertEquals(0.5, slow.x, 0.01)
        val fast = aim(config)
        fast.feed(0.1, { s -> 90.0 * s }) // 90°/s: fully boosted
        fast.feed(0.5, { 9.0 })
        assertEquals(3 * 9.0 / 18, fast.x, 0.05)
    }

    @Test
    fun resetAndSuppressionNeverJumpTheAim() {
        val a = aim()
        a.feed(0.5, { 0.0 })
        a.reset()
        a.feed(0.5, { 12.0 }) // e.g. a recalibration moved the reference
        assertEquals(0.0, a.x, 1e-9)
        val s0 = t
        repeat(30) {
            a.update(12.0 + (t - s0) * 400, 0.0, t, suppressed = true) // a spasm being gated
            t += 0.01
        }
        assertEquals(0.0, a.x, 1e-9)
    }
}
