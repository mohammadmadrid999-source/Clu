package com.clu.motion.core

import com.clu.motion.core.filter.KalmanFilter2D
import com.clu.motion.core.filter.OneEuroFilter2D
import com.clu.motion.core.filter.SpasmGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class FilterTest {
    private val rate = 100.0

    private fun defaultOneEuro() = OneEuroFilter2D(minCutoffHz = 1.0, beta = 0.04, derivativeCutoffHz = 1.0)

    @Test
    fun oneEuroAttenuatesTremorAtRest() {
        val f = defaultOneEuro()
        var inSq = 0.0
        var outSq = 0.0
        for (i in 0 until 600) {
            val t = i / rate
            val tremor = 1.0 * sin(2 * PI * 6.0 * t) // 1° at 6 Hz: typical rest/essential tremor
            f.update(tremor, 0.0, t)
            if (i >= 200) {
                inSq += tremor * tremor
                outSq += f.x * f.x
            }
        }
        val ratio = sqrt(outSq / inSq)
        assertTrue("tremor passed through at ratio $ratio", ratio < 0.3)
    }

    @Test
    fun oneEuroFollowsIntentionalStepQuickly() {
        val f = defaultOneEuro()
        f.update(0.0, 0.0, 0.0)
        var reached90At = -1.0
        for (i in 1..200) {
            val t = i / rate
            f.update(10.0, 0.0, t)
            if (reached90At < 0 && f.x >= 9.0) reached90At = t
        }
        assertTrue("90% rise took ${reached90At}s", reached90At in 0.0..0.4)
    }

    @Test
    fun oneEuroSharesCutoffSoDiagonalsStayStraight() {
        val f = defaultOneEuro()
        for (i in 0..50) f.update(i * 0.2, i * 0.2, i / rate)
        assertEquals(f.x, f.y, 1e-9)
    }

    @Test
    fun oneEuroIgnoresDuplicateTimestamps() {
        val f = defaultOneEuro()
        f.update(0.0, 0.0, 1.0)
        f.update(5.0, 0.0, 1.0)
        assertEquals(0.0, f.x, 0.0)
    }

    @Test
    fun kalmanConvergesAndSmoothsNoise() {
        val f = KalmanFilter2D(processNoise = 800.0, measurementNoise = 0.5)
        val rnd = java.util.Random(7)
        var errSq = 0.0
        for (i in 0 until 500) {
            f.update(5.0 + rnd.nextGaussian() * 0.7, 0.0, i / rate)
            if (i > 200) errSq += (f.x - 5.0) * (f.x - 5.0)
        }
        assertTrue(sqrt(errSq / 299) < 0.7)
    }

    @Test
    fun spasmGateFreezesThenGlidesInsteadOfJumping() {
        val g = SpasmGate(triggerSpeedDegPerSec = 350.0, maxHoldMs = 400, glideMs = 180)
        var t = 0.0
        repeat(20) { g.update(2.0, 0.0, t); t += 10.0 }
        // Spasm: 30° in 20 ms, then the arm stays there.
        g.update(17.0, 0.0, t); t += 10.0
        g.update(32.0, 0.0, t); t += 10.0
        assertEquals(2.0, g.x, 1e-9)
        assertTrue(g.holding)

        var previous = g.x
        var maxStep = 0.0
        repeat(80) {
            g.update(32.0, 0.0, t)
            t += 10.0
            maxStep = maxOf(maxStep, abs(g.x - previous))
            previous = g.x
        }
        assertEquals(32.0, g.x, 1e-6) // a sustained reposition is eventually followed
        assertTrue("output jumped $maxStep° in one sample", maxStep < 5.0)
    }

    @Test
    fun spasmGatePassesNormalMotion() {
        val g = SpasmGate(350.0, 400, 180)
        var t = 0.0
        for (i in 0..100) {
            val v = 10 * sin(2 * PI * 0.5 * t / 1000)
            g.update(v, 0.0, t)
            assertEquals(v, g.x, 1e-9)
            t += 10.0
        }
    }
}
