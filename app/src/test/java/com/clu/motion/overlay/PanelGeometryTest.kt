package com.clu.motion.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PanelGeometryTest {
    // POCO X7 Pro in landscape.
    private val sw = 2712
    private val sh = 1220
    private val m = 26

    @Test
    fun placementsHugTheirEdgesWhateverThePanelSize() {
        val right = PanelPlacement(1f, 1f)
        for ((w, h) in listOf(220 to 220, 1150 to 290, 1400 to 480)) {
            val p = PanelGeometry.topLeft(right, w, h, sw, sh, m)
            assertEquals("right edge stays at the margin for $w", sw - m, p.x + w)
            assertEquals("bottom edge stays at the margin for $h", sh - m, p.y + h)
        }
        val left = PanelGeometry.topLeft(PanelPlacement(0f, 0f), 1150, 290, sw, sh, m)
        assertEquals(IntPoint(m, m), left)
    }

    @Test
    fun aPanelLargerThanTheScreenIsPinnedToTheCornerNotPushedOff() {
        val p = PanelGeometry.topLeft(PanelPlacement(1f, 1f), 3000, 1500, sw, sh, m)
        assertEquals(IntPoint(0, 0), p)
    }

    @Test
    fun draggingFollowsTheFingerAndStopsAtTheScreenEdge() {
        val start = PanelPlacement(0.5f, 0.5f)
        val origin = PanelGeometry.topLeft(start, 300, 200, sw, sh, m)
        val moved = PanelGeometry.dragged(start, 100f, -50f, 300, 200, sw, sh, m)
        val now = PanelGeometry.topLeft(moved, 300, 200, sw, sh, m)
        assertTrue(abs(origin.x + 100 - now.x) <= 1)
        assertTrue(abs(origin.y - 50 - now.y) <= 1)
        val far = PanelGeometry.dragged(start, 5000f, 5000f, 300, 200, sw, sh, m)
        assertEquals(PanelPlacement(1f, 1f), far)
    }

    @Test
    fun placementSurvivesRotation() {
        val p = PanelPlacement(1f, 0f) // top-right in landscape…
        val portrait = PanelGeometry.topLeft(p, 220, 220, sh, sw, m)
        assertEquals(sh - m, portrait.x + 220) // …stays top-right in portrait
        assertEquals(m, portrait.y)
    }

    @Test
    fun theMoveButtonCyclesTheEightEdges() {
        var p = PanelPlacement.ANCHORS[0]
        val seen = mutableSetOf<PanelPlacement>()
        repeat(8) {
            seen += p
            p = PanelGeometry.nextAnchor(p)
        }
        assertEquals(8, seen.size)
        assertEquals(PanelPlacement.ANCHORS[0], p)
        // From a dragged position: the anchor after the nearest one.
        assertEquals(PanelPlacement(0.5f, 0f), PanelGeometry.nextAnchor(PanelPlacement(0.1f, 0.05f)))
    }

    @Test
    fun accessibilityStepsStayInRange() {
        assertEquals(PanelPlacement(0.25f, 0f), PanelGeometry.stepped(PanelPlacement(0f, 0f), 1, -1))
        assertEquals(PanelPlacement(1f, 1f), PanelGeometry.stepped(PanelPlacement(0.9f, 0.95f), 1, 1))
    }

    @Test
    fun theEditorBarMovesOffASelectedTargetItCovers() {
        val bar = PanelPlacement(0.5f, 1f)
        val barRect = IntRect(800, 950, 1900, 1194)
        val underBar = IntRect(1100, 1000, 1200, 1100)
        assertEquals(PanelPlacement(0.5f, 0f), PanelGeometry.awayFrom(bar, barRect, underBar, sh))
        val elsewhere = IntRect(100, 100, 200, 200)
        assertNull(PanelGeometry.awayFrom(bar, barRect, elsewhere, sh))
        // A target near the top, under a bar at the top: the bar goes to the bottom.
        val topBar = IntRect(800, 26, 1900, 270)
        assertEquals(PanelPlacement(0.5f, 1f), PanelGeometry.awayFrom(PanelPlacement(0.5f, 0f), topBar, IntRect(1000, 100, 1100, 200), sh))
        assertTrue(PanelGeometry.intersects(barRect, underBar))
    }

    @Test
    fun legacyAnchorsKeepTheirOrder() {
        // Index-compatible with the Gravity anchors saved by earlier versions.
        assertEquals(PanelPlacement(0f, 0f), PanelPlacement.ANCHORS[0])
        assertEquals(PanelPlacement(1f, 1f), PanelPlacement.ANCHORS[4])
        assertEquals(PanelPlacement(0f, 0.5f), PanelPlacement.ANCHORS[7])
    }
}
