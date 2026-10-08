package com.clu.motion.core

import com.clu.motion.core.response.AxisRanges
import com.clu.motion.core.response.ResponseCurves
import com.clu.motion.core.response.ResponseMapper
import com.clu.motion.profile.CurveType
import com.clu.motion.profile.OutputMode
import com.clu.motion.profile.ResponseConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

class ResponseMapperTest {
    private fun mapper(cfg: ResponseConfig = ResponseConfig(curve = CurveType.LINEAR), ranges: AxisRanges = AxisRanges.symmetric(20.0)) =
        ResponseMapper(cfg, ranges, cfg.deadzoneDeg)

    @Test
    fun restingInsideDeadzoneIsExactlyNeutral() {
        val m = mapper()
        assertTrue(m.map(1.0, -0.8).isNeutral)
    }

    @Test
    fun noJumpAtDeadzoneEdge() {
        val m = mapper()
        assertTrue(m.map(1.51, 0.0).magnitude < 0.01)
    }

    @Test
    fun fullTiltIsFullDeflectionAndClamps() {
        val m = mapper()
        assertEquals(1.0, m.map(20.0, 0.0).x, 1e-9)
        assertEquals(1.0, m.map(45.0, 0.0).magnitude, 1e-9)
    }

    @Test
    fun directionIsPreserved() {
        val out = mapper().map(8.0, 8.0)
        assertEquals(45.0, Math.toDegrees(atan2(out.y, out.x)), 1e-9)
    }

    @Test
    fun asymmetricRangesGiveFullDeflectionBothWays() {
        val m = mapper(ranges = AxisRanges(posX = 10.0, negX = 5.0, posY = 20.0, negY = 20.0))
        assertEquals(1.0, m.map(10.0, 0.0).x, 1e-9)
        assertEquals(-1.0, m.map(-5.0, 0.0).x, 1e-9)
    }

    @Test
    fun everyCurveIsMonotonicFromZeroToOne() {
        for (curve in CurveType.entries) {
            val cfg = ResponseConfig(curve = curve, exponent = 0.6, sigmoidSteepness = 9.0, sigmoidMidpoint = 0.3)
            assertEquals(0.0, ResponseCurves.apply(cfg, 0.0), 1e-9)
            assertEquals(1.0, ResponseCurves.apply(cfg, 1.0), 1e-9)
            var last = -1.0
            for (i in 0..100) {
                val v = ResponseCurves.apply(cfg, i / 100.0)
                assertTrue("$curve not monotonic at $i", v >= last)
                last = v
            }
        }
    }

    @Test
    fun powerBelowOneBoostsMicroMovements() {
        val cfg = ResponseConfig(curve = CurveType.POWER, exponent = 0.6)
        assertTrue(ResponseCurves.apply(cfg, 0.1) > 0.2)
    }

    @Test
    fun antiDeadzoneJumpsOverTheGamesOwnDeadzone() {
        val m = mapper(ResponseConfig(curve = CurveType.LINEAR, antiDeadzone = 0.2))
        assertTrue(m.map(1.6, 0.0).x >= 0.2)
    }

    @Test
    fun digitalFourWaySnapsToCardinals() {
        val m = mapper(ResponseConfig(outputMode = OutputMode.DIGITAL_4))
        val out = m.map(10.0, 4.0)
        assertEquals(1.0, out.x, 1e-9)
        assertEquals(0.0, out.y, 1e-9)
        assertTrue(m.map(2.5, 0.0).isNeutral) // below the digital threshold
    }

    @Test
    fun angleSnapStraightensNearCardinals() {
        val m = mapper(ResponseConfig(curve = CurveType.LINEAR, angleSnapDeg = 10.0))
        assertEquals(0.0, m.map(15.0, 2.0).y, 1e-9)
        assertTrue(m.map(15.0, 8.0).y > 0.1) // well outside the window: untouched
    }
}
