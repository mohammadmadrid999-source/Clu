package com.clu.motion.core

import com.clu.motion.core.diag.InjectionStats
import com.clu.motion.core.diag.SelfTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectionStatsTest {

    private fun InjectionStats.touch(action: Int, t: Long, now: Long, vararg pts: Pair<Float, Float>) =
        onTouch(action, t, now, FloatArray(pts.size) { pts[it].first }, FloatArray(pts.size) { pts[it].second }, pts.size, "src=0x1002")

    @Test
    fun disabledRecordsNothing() {
        val s = InjectionStats()
        s.onDispatch(0, 5, listOf(Triple(0, 10, 10)))
        s.touch(InjectionStats.ACTION_DOWN, 0, 1, 10f to 10f)
        assertEquals(0, s.snapshot().dispatched)
        assertEquals(0, s.snapshot().downs)
    }

    @Test
    fun matchesDeliveredPointsToDispatchedSegmentEnds() {
        val s = InjectionStats().apply { enabled = true }
        s.onDispatch(100, 4, listOf(Triple(0, 400, 700)))
        s.onDispatch(116, 3, listOf(Triple(0, 420, 700)))
        s.touch(InjectionStats.ACTION_DOWN, 101, 102, 400f to 700f)
        s.touch(InjectionStats.ACTION_MOVE, 132, 134, 420f to 700f)
        val snap = s.snapshot()
        assertEquals(2, snap.dispatched)
        assertEquals(2, snap.dispatchToDelivery!!.count)
        assertEquals(18L, snap.dispatchToDelivery!!.max) // 134 - 116
        assertEquals(1, snap.downs)
        assertEquals(1, snap.moves)
        assertEquals(mapOf("src=0x1002" to 2), snap.signatures)
    }

    @Test
    fun moveGapsMeasureStreamContinuity() {
        val s = InjectionStats().apply { enabled = true }
        s.touch(InjectionStats.ACTION_DOWN, 0, 0, 1f to 1f)
        for (t in listOf(8L, 16L, 24L, 90L)) s.touch(InjectionStats.ACTION_MOVE, t, t, 1f to 1f)
        val gap = s.snapshot().moveGap!!
        assertEquals(66L, gap.max)
        assertEquals(8L, gap.p50)
    }

    @Test
    fun resetClearsEverything() {
        val s = InjectionStats().apply { enabled = true }
        s.onCompleted()
        s.reset()
        assertEquals(0, s.snapshot().completed)
        assertNull(s.snapshot().moveGap)
    }

    @Test
    fun selfTestRampsThenCirclesAndSchedulesTaps() {
        val t = SelfTest(startedAtMs = 1000)
        assertTrue(t.stick(1000).isNeutral)
        assertEquals(0.8, t.stick(5000).magnitude, 1e-9)
        assertEquals(0L, t.tapIndex(1500))
        assertEquals(1L, t.tapIndex(2300))
    }
}
