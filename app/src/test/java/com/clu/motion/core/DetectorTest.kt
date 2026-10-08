package com.clu.motion.core

import com.clu.motion.core.gesture.Direction4
import com.clu.motion.core.gesture.DwellDetector
import com.clu.motion.core.gesture.DwellEvent
import com.clu.motion.core.gesture.FlickDetector
import com.clu.motion.core.response.StickOutput
import com.clu.motion.profile.DwellConfig
import com.clu.motion.profile.FlickConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DwellDetectorTest {
    private val up = StickOutput(0.0, -0.95)

    private fun run(d: DwellDetector, stick: (Long) -> StickOutput, fromMs: Long, toMs: Long): List<Pair<Long, DwellEvent>> {
        val events = mutableListOf<Pair<Long, DwellEvent>>()
        var t = fromMs
        while (t <= toMs) {
            d.update(stick(t), t)?.let { events += t to it }
            t += 10
        }
        return events
    }

    @Test
    fun firesOnceAfterDwellTimeAndEndsOnLeave() {
        val d = DwellDetector(DwellConfig(dwellMs = 1200))
        val events = run(d, { if (it < 2000) up else StickOutput.ZERO }, 0, 2500)
        assertEquals(2, events.size)
        val (firedAt, fired) = events[0]
        assertEquals(DwellEvent.Fired(Direction4.UP), fired)
        assertTrue(firedAt in 1200..1210)
        assertEquals(DwellEvent.Ended(Direction4.UP), events[1].second)
    }

    @Test
    fun briefTremorDipsDoNotRestartTheTimer() {
        val d = DwellDetector(DwellConfig(dwellMs = 1000, graceMs = 150))
        // Dips below the exit threshold for 50 ms every 300 ms.
        val events = run(d, { if (it % 300 < 50) StickOutput(0.0, -0.5) else up }, 0, 1300)
        assertTrue(events.any { it.second == DwellEvent.Fired(Direction4.UP) })
    }

    @Test
    fun diagonalsAndSmallDeflectionsNeverFire() {
        val d = DwellDetector(DwellConfig(dwellMs = 500))
        assertTrue(run(d, { StickOutput(0.7, -0.7) }, 0, 2000).isEmpty())
        assertTrue(run(DwellDetector(DwellConfig(dwellMs = 500)), { StickOutput(0.0, -0.6) }, 0, 2000).isEmpty())
    }

    @Test
    fun progressIsReportedForTheHud() {
        val d = DwellDetector(DwellConfig(dwellMs = 1000))
        run(d, { up }, 0, 500)
        assertEquals(0.5, d.progress, 0.02)
        assertEquals(Direction4.UP, d.direction)
    }

    @Test
    fun resetReleasesAHeldDwell() {
        val d = DwellDetector(DwellConfig(dwellMs = 100))
        run(d, { up }, 0, 300)
        assertEquals(DwellEvent.Ended(Direction4.UP), d.reset())
        assertNull(d.reset())
    }
}

class FlickDetectorTest {
    private fun feed(f: FlickDetector, signal: (Double) -> Double, durationMs: Int): List<Int> {
        val out = mutableListOf<Int>()
        var t = 0.0
        while (t <= durationMs) {
            val r = f.update(signal(t), t)
            if (r != 0) out += r
            t += 10.0
        }
        return out
    }

    /** Out-and-back triangle pulse starting at 500 ms. */
    private fun pulse(amp: Double, halfMs: Double): (Double) -> Double = { t ->
        val x = t - 500
        when {
            x < 0 -> 0.0
            x < halfMs -> amp * x / halfMs
            x < 2 * halfMs -> amp * (2 * halfMs - x) / halfMs
            else -> 0.0
        }
    }

    @Test
    fun detectsQuickOutAndBackFlickInBothDirections() {
        assertEquals(listOf(1), feed(FlickDetector(FlickConfig()), pulse(12.0, 70.0), 2000))
        assertEquals(listOf(-1), feed(FlickDetector(FlickConfig()), pulse(-12.0, 70.0), 2000))
    }

    @Test
    fun ignoresSlowDeliberateTilt() {
        assertTrue(feed(FlickDetector(FlickConfig()), pulse(15.0, 800.0), 3000).isEmpty())
    }

    @Test
    fun ignoresTremor() {
        val tremor: (Double) -> Double = { t -> 2.0 * sin(2 * PI * 8 * t / 1000) }
        assertTrue(feed(FlickDetector(FlickConfig()), tremor, 5000).isEmpty())
    }

    @Test
    fun ignoresFastMoveThatStays() {
        val moveAndStay: (Double) -> Double = { t -> if (t < 500) 0.0 else minOf(15.0, (t - 500) * 15 / 80) }
        assertTrue(feed(FlickDetector(FlickConfig()), moveAndStay, 3000).isEmpty())
    }

    @Test
    fun ignoresHugeSpasmExcursions() {
        assertTrue(feed(FlickDetector(FlickConfig(maxAmplitudeDeg = 35.0)), pulse(60.0, 90.0), 2000).isEmpty())
    }

    @Test
    fun overshootOnTheWayBackDoesNotFireTheOppositeDirection() {
        val withOvershoot: (Double) -> Double = { t ->
            val x = t - 500
            when {
                x < 0 -> 0.0
                x < 70 -> 12 * x / 70
                x < 160 -> 12 - 20 * (x - 70) / 90 // to −8°
                x < 260 -> -8 + 8 * (x - 160) / 100
                else -> 0.0
            }
        }
        assertEquals(listOf(1), feed(FlickDetector(FlickConfig()), withOvershoot, 2000))
    }

    @Test
    fun snapModeFiresWithoutReturn() {
        val moveAndStay: (Double) -> Double = { t -> if (t < 500) 0.0 else minOf(15.0, (t - 500) * 15 / 80) }
        assertEquals(listOf(1), feed(FlickDetector(FlickConfig(requireReturn = false)), moveAndStay, 2000))
    }
}
