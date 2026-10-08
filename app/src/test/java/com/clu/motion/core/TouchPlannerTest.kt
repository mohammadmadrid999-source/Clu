package com.clu.motion.core

import com.clu.motion.core.input.JoystickDriver
import com.clu.motion.core.input.PointerSegment
import com.clu.motion.core.input.TouchPlanner
import com.clu.motion.core.response.StickOutput
import com.clu.motion.profile.JoystickConfig
import com.clu.motion.profile.JoystickMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPlannerTest {
    private fun planner() = TouchPlanner(segmentMs = 16, maxInFlight = 2).apply {
        width = 2400
        height = 1080
    }

    @Test
    fun pressStartsANewStrokeThenContinuesTowardTarget() {
        val p = planner()
        p.press(0, 400, 800, targetX = 500, targetY = 800, now = 0)
        val first = p.nextPlan(0)!!.segments.single()
        assertEquals(PointerSegment(0, 400, 800, 400, 800, isNewStroke = true, willContinue = true), first)
        p.onCompleted(0)
        val second = p.nextPlan(16)!!.segments.single()
        assertEquals(PointerSegment(0, 400, 800, 500, 800, isNewStroke = false, willContinue = true), second)
    }

    @Test
    fun continuationsStartExactlyWhereThePreviousSegmentEnded() {
        val p = planner()
        p.press(0, 100, 100, now = 0)
        var last: PointerSegment? = null
        for (i in 0 until 50) {
            p.moveTo(0, 100 + i * 7, 100 + i * 3)
            val seg = p.nextPlan(i * 16L)!!.segments.single()
            last?.let { assertEquals(it.toX to it.toY, seg.fromX to seg.fromY) }
            last = seg
            p.onCompleted(p.generation)
        }
    }

    @Test
    fun backpressureCoalescesToTheNewestTarget() {
        val p = planner()
        p.press(0, 100, 100, now = 0)
        assertNotNull(p.nextPlan(0))
        p.moveTo(0, 150, 100)
        assertNotNull(p.nextPlan(16))
        p.moveTo(0, 300, 100)
        assertNull("two segments already in flight", p.nextPlan(32))
        p.moveTo(0, 400, 100)
        p.onCompleted(0)
        val seg = p.nextPlan(48)!!.segments.single()
        assertEquals(150 to 100, seg.fromX to seg.fromY)
        assertEquals(400 to 100, seg.toX to seg.toY) // skipped 300, no motion lost
    }

    @Test
    fun tapHoldsForItsDurationThenLifts() {
        val p = planner()
        p.press(101, 2000, 900, holdMs = 70, now = 0)
        val down = p.nextPlan(100)!!.segments.single()
        assertTrue(down.isNewStroke && down.willContinue)
        p.onCompleted(0)
        var t = 116L
        var upAt = -1L
        while (upAt < 0 && t < 1000) {
            // While the finger is held still there is nothing to send (rule 7).
            val plan = p.nextPlan(t)
            if (plan != null) {
                val seg = plan.segments.single()
                assertFalse("only the lift may be sent", seg.willContinue)
                upAt = t
                p.onCompleted(0)
            }
            t += 16
        }
        assertTrue("lifted after ${upAt - 100} ms", upAt >= 170)
        assertFalse(p.has(101))
        assertTrue(p.isIdle)
    }

    @Test
    fun aFingerHeldStillSendsNothingBecauseTheInjectorWouldReportFailure() {
        val p = planner()
        p.press(0, 400, 800, targetX = 450, targetY = 800, now = 0)
        p.nextPlan(0)
        p.onCompleted(0)
        assertEquals(450, p.nextPlan(16)!!.segments.single().toX) // moves to the deflected target
        p.onCompleted(0)
        // Stick held at the same deflection (or at full tilt): no gesture at all, pointer stays down.
        for (t in 32L..400L step 16) assertNull(p.nextPlan(t))
        assertTrue(p.has(0))
        assertEquals(0, p.generation)
        // Moving again continues the same stroke, from exactly where it stopped.
        p.moveTo(0, 470, 800)
        val seg = p.nextPlan(416)!!.segments.single()
        assertFalse(seg.isNewStroke)
        assertEquals(450 to 470, seg.fromX to seg.toX)
    }

    @Test
    fun aStillFingerRidesAlongWhenAnotherPointerMoves() {
        val p = planner()
        p.press(0, 400, 800, now = 0)
        p.press(101, 2000, 900, now = 0)
        p.nextPlan(0)
        p.onCompleted(0)
        p.moveTo(0, 420, 800)
        val plan = p.nextPlan(16)!!
        // Rule 2: the still button must still be continued so the injector accepts the gesture.
        assertEquals(setOf(0, 101), plan.segments.map { it.key }.toSet())
    }

    @Test
    fun aQuickPressAndReleaseStillTaps() {
        val p = planner()
        p.press(101, 10, 10, now = 0)
        p.release(101)
        assertTrue(p.nextPlan(0)!!.segments.single().isNewStroke)
        p.onCompleted(0)
        assertFalse(p.nextPlan(16)!!.segments.single().willContinue)
    }

    @Test
    fun neverStartsAFreshGestureWhileAnUpIsInFlight() {
        val p = planner()
        p.press(101, 10, 10, now = 0)
        p.nextPlan(0)
        p.onCompleted(0)
        p.release(101)
        assertFalse(p.nextPlan(16)!!.segments.single().willContinue) // the UP is now in flight
        p.press(102, 50, 50, now = 20)
        assertNull("a fresh gesture would cancel the pending UP", p.nextPlan(32))
        p.onCompleted(0)
        assertTrue(p.nextPlan(48)!!.segments.single().isNewStroke)
    }

    @Test
    fun buttonsJoinTheMovingJoystickGesture() {
        val p = planner()
        p.press(0, 300, 800, now = 0)
        p.nextPlan(0)
        p.press(101, 2000, 900, holdMs = 70, now = 10)
        val plan = p.nextPlan(16)!!
        assertEquals(2, plan.segments.size)
        val stick = plan.segments.first { it.key == 0 }
        val button = plan.segments.first { it.key == 101 }
        assertFalse(stick.isNewStroke)
        assertEquals(0L, stick.startDelayMs)
        // The injector rejects a continuing gesture whose t=0 step contains a new finger.
        assertTrue(button.isNewStroke)
        assertEquals(TouchPlanner.NEW_STROKE_DELAY_MS, button.startDelayMs)
        // Continued strokes come first, so POINTER_DOWN indices line up with the injector's list.
        assertEquals(listOf(0, 101), plan.segments.map { it.key })
    }

    @Test
    fun freshGesturesStartAtTimeZero() {
        val p = planner()
        p.press(101, 10, 10, now = 0)
        p.press(102, 20, 20, now = 0)
        assertTrue(p.nextPlan(0)!!.segments.all { it.isNewStroke && it.startDelayMs == 0L })
    }

    @Test
    fun cancellationRepressesAfterBackoffInANewGeneration() {
        val p = planner()
        p.press(0, 300, 800, targetX = 350, targetY = 800, now = 0)
        p.nextPlan(0)
        p.onCompleted(0)
        p.nextPlan(16)
        p.onCancelled(0, now = 20) // e.g. the user touched the screen
        assertEquals(1, p.generation)
        assertNull("backoff", p.nextPlan(40))
        val seg = p.nextPlan(200)!!.segments.single()
        assertTrue(seg.isNewStroke)
        assertEquals(300 to 800, seg.fromX to seg.fromY) // re-press at the anchor, not mid-drag
        p.onCancelled(0, now = 210) // stale callback from the old generation: ignored
        assertEquals(1, p.generation)
    }

    @Test
    fun aRealTouchElsewhereRepressesHeldPointersInANewGeneration() {
        val p = planner()
        assertFalse("nothing held, nothing to do", p.onExternalTouch(0))
        p.press(0, 300, 800, targetX = 380, targetY = 800, now = 0)
        p.nextPlan(0)
        p.onCompleted(0)
        p.nextPlan(16) // a continuation is now in flight
        assertTrue(p.onExternalTouch(20))
        assertEquals(1, p.generation)
        assertNull("waits for the real touch to finish", p.nextPlan(200))
        val seg = p.nextPlan(330)!!.segments.single()
        assertTrue(seg.isNewStroke)
        assertEquals(300 to 800, seg.fromX to seg.fromY)
    }

    @Test
    fun coordinatesAreClampedToTheDisplay() {
        val p = planner()
        p.press(0, -50, 5000, now = 0)
        val seg = p.nextPlan(0)!!.segments.single()
        assertEquals(0 to 1079, seg.fromX to seg.fromY)
    }

    @Test
    fun releaseAllDropsPointersThatNeverWentDown() {
        val p = planner()
        p.press(101, 10, 10, now = 0)
        p.releaseAll()
        assertNull(p.nextPlan(0))
        assertTrue(p.isIdle)
    }
}

class JoystickDriverTest {
    private val cfg = JoystickConfig(centerX = 0.2, centerY = 0.7, radiusFraction = 0.1, releaseAfterNeutralMs = 200)
    private val w = 2000
    private val h = 1000

    @Test
    fun touchesDownAtAnchorThenDragsProportionally() {
        val p = TouchPlanner().apply { width = w; height = h }
        val d = JoystickDriver(cfg)
        d.update(StickOutput.ZERO, live = true, now = 0, width = w, height = h, planner = p)
        assertFalse(p.has(TouchPlanner.JOYSTICK_KEY))

        d.update(StickOutput(0.5, 0.0), true, 10, w, h, p)
        val down = p.nextPlan(10)!!.segments.single()
        assertEquals(400 to 700, down.fromX to down.fromY)
        p.onCompleted(0)
        d.update(StickOutput(0.5, 0.0), true, 26, w, h, p)
        val drag = p.nextPlan(26)!!.segments.single()
        assertEquals(450 to 700, drag.toX to drag.toY) // anchor + 0.5 × (0.1 × 1000)
    }

    @Test
    fun liftsAfterRestingAtNeutral() {
        val p = TouchPlanner().apply { width = w; height = h }
        val d = JoystickDriver(cfg)
        d.update(StickOutput(1.0, 0.0), true, 0, w, h, p)
        p.nextPlan(0)
        p.onCompleted(0)
        d.update(StickOutput.ZERO, true, 100, w, h, p)
        assertFalse(p.isLifting(TouchPlanner.JOYSTICK_KEY)) // returns to centre, still held
        d.update(StickOutput.ZERO, true, 260, w, h, p)
        assertTrue(p.isLifting(TouchPlanner.JOYSTICK_KEY))
    }

    @Test
    fun notLiveReleasesImmediately() {
        val p = TouchPlanner().apply { width = w; height = h }
        val d = JoystickDriver(cfg)
        d.update(StickOutput(1.0, 0.0), true, 0, w, h, p)
        p.nextPlan(0)
        d.update(StickOutput(1.0, 0.0), false, 10, w, h, p)
        assertTrue(p.isLifting(TouchPlanner.JOYSTICK_KEY))
    }

    @Test
    fun cameraDragMovesAtRateAndRegripsAtThePadEdge() {
        val p = TouchPlanner(maxInFlight = 100).apply { width = w; height = h }
        val d = JoystickDriver(cfg.copy(mode = JoystickMode.CAMERA_DRAG, cameraSpeedPxPerSec = 1000.0))
        var t = 0L
        d.update(StickOutput(1.0, 0.0), true, t, w, h, p)
        p.nextPlan(t)
        p.onCompleted(p.generation)
        var lifted = false
        while (t < 300 && !lifted) {
            t += 16
            d.update(StickOutput(1.0, 0.0), true, t, w, h, p)
            lifted = p.isLifting(TouchPlanner.JOYSTICK_KEY)
            p.nextPlan(t)
            p.onCompleted(p.generation)
        }
        assertTrue("finger should re-grip after crossing the 100 px pad", lifted)
        assertTrue(t in 90L..140L) // 100 px at 1000 px/s
        t += 16
        d.update(StickOutput(1.0, 0.0), true, t, w, h, p)
        val regrip = p.nextPlan(t)!!.segments.single()
        assertTrue(regrip.isNewStroke)
        assertEquals(400 to 700, regrip.fromX to regrip.fromY)
    }
}
