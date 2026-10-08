package com.clu.motion.core

import com.clu.motion.core.math.Quaternion
import com.clu.motion.core.math.Vec3
import com.clu.motion.profile.AxisMode
import com.clu.motion.profile.ControlProfile
import com.clu.motion.profile.Presets
import com.clu.motion.profile.TriggerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** End-to-end behaviour of the signal chain with synthetic sensor streams (flat phone, ROTATION_0). */
class MotionPipelineTest {
    private val events = mutableListOf<PipelineEvent>()
    private var t = 0L

    private fun pipeline(profile: ControlProfile = Presets.handheld()) = MotionPipeline(profile) { events += it }.also { it.active = true }

    private fun MotionPipeline.feed(ms: Long, orientation: (Long) -> Quaternion): MotionFrame {
        var last = MotionFrame.IDLE
        val end = t + ms
        while (t < end) {
            last = onOrientation(orientation(t), t * 1_000_000, t)
            t += 10
        }
        return last
    }

    private fun MotionPipeline.calibrated(neutral: Quaternion = Quaternion.IDENTITY): MotionPipeline {
        beginCalibration()
        feed(2100) { neutral }
        assertEquals(PipelinePhase.RUNNING, phase)
        assertTrue(events.any { it is PipelineEvent.Calibrated })
        events.clear()
        return this
    }

    @Test
    fun tiltingRightAndForwardDrivesTheStick() {
        val p = pipeline().calibrated()
        // Right edge down: rotation about device +Y for a flat phone.
        val right = p.feed(1000) { rot(Vec3.Y, 15.0) }
        assertTrue("x=${right.stickX}", right.stickX > 0.6)
        assertEquals(0f, right.stickY, 0.02f)
        // Top edge down (forward): rotation about device −X → stick up (negative y).
        val forward = p.feed(1000) { rot(Vec3.X, -15.0) }
        assertTrue("y=${forward.stickY}", forward.stickY < -0.6)
        assertEquals(0f, forward.stickX, 0.05f)
    }

    @Test
    fun anyPostureCanBeTheNeutral() {
        // Lying down with the phone above the face, screen facing down.
        val lyingDown = rot(Vec3.X, 180.0) * rot(Vec3.Y, 8.0)
        val p = pipeline().calibrated(lyingDown)
        val still = p.feed(1000) { lyingDown }
        assertTrue(still.isNeutral)
        val tilted = p.feed(1000) { lyingDown * rot(p.basis.ex, 15.0) }
        assertTrue(tilted.stickX > 0.6)
    }

    @Test
    fun restingTremorNeverMovesTheStick() {
        val tremor: (Long) -> Quaternion = { ms -> rot(Vec3(1.0, 1.0, 0.0).normalized(), 0.8 * sin(2 * PI * 6 * ms / 1000.0)) }
        val p = pipeline()
        p.beginCalibration()
        p.feed(2100, tremor)
        val frames = (0 until 300).map { p.feed(10, tremor) }
        assertTrue(frames.all { it.isNeutral })
        assertTrue(p.tremor.rmsDeg > 0.3)
    }

    @Test
    fun holdingForwardFiresTheDwellTrigger() {
        val p = pipeline().calibrated()
        p.feed(2000) { rot(Vec3.X, -20.0) }
        val dwell = events.filterIsInstance<PipelineEvent.Trigger>().firstOrNull { it.kind == TriggerKind.DWELL_UP }
        assertNotNull(dwell)
        assertEquals(TriggerPhase.PRESS, dwell!!.phase)
        events.clear()
        p.feed(1000) { Quaternion.IDENTITY }
        assertTrue(events.contains(PipelineEvent.Trigger(TriggerKind.DWELL_UP, TriggerPhase.RELEASE)))
    }

    @Test
    fun aQuickTwistIsAFlickAndBarelyMovesTheStick() {
        val p = pipeline().calibrated()
        val twist: (Long) -> Quaternion = { ms ->
            val x = (ms - t0).toDouble()
            val deg = when {
                x < 0 -> 0.0
                x < 60 -> 12 * x / 60
                x < 120 -> 12 * (120 - x) / 60
                else -> 0.0
            }
            rot(Vec3.Z, deg) // about "up" for a flat phone: counter-clockwise from above
        }
        t0 = t + 200
        var maxStick = 0f
        repeat(100) { maxStick = maxOf(maxStick, abs(p.feed(10, twist).stickX)) }
        val flicks = events.filterIsInstance<PipelineEvent.Trigger>().filter { it.phase == TriggerPhase.PULSE }
        assertEquals(listOf(TriggerKind.TWIST_LEFT), flicks.map { it.kind })
        assertTrue(maxStick < 0.05f)
    }

    private var t0 = 0L

    @Test
    fun learningProducesAxesFromTheUsersMoves() {
        val p = pipeline().calibrated()
        p.beginLearning()
        // The user can only roll their wrist about a skewed axis.
        val rightAxis = Vec3(0.3, 0.95, 0.0).normalized()
        val downAxis = Vec3(0.95, -0.3, 0.0).normalized()
        val moves = mapOf(
            com.clu.motion.core.calibration.AxisLearner.Step.RIGHT to rot(rightAxis, 16.0),
            com.clu.motion.core.calibration.AxisLearner.Step.LEFT to rot(rightAxis, -16.0),
            com.clu.motion.core.calibration.AxisLearner.Step.UP to rot(downAxis, -10.0),
            com.clu.motion.core.calibration.AxisLearner.Step.DOWN to rot(downAxis, 10.0),
        )
        var frame = MotionFrame.IDLE
        while (p.phase == PipelinePhase.LEARNING && t < 60_000) {
            val step = frame.learnStep
            val q = if (step == null || frame.learnReturning) Quaternion.IDENTITY else moves.getValue(step)
            frame = p.onOrientation(q, t * 1_000_000, t)
            t += 10
        }
        val learned = events.filterIsInstance<PipelineEvent.AxesLearned>().single().axes
        assertVec(rightAxis, learned.ex, 2e-3)
        assertTrue((learned.ey dot downAxis) > 0.99)
        assertEquals(16 * 0.7, learned.rangePosX, 0.3)
        assertEquals(10 * 0.7, learned.rangeNegY, 0.3)

        // Apply it as the engine would and check the skewed roll now drives X alone.
        p.setProfile(p.profile.copy(axes = p.profile.axes.copy(mode = AxisMode.LEARNED, learned = learned)))
        val out = p.feed(1000) { rot(rightAxis, 8.0) }
        assertTrue(out.stickX > 0.5)
        assertEquals(0f, out.stickY, 0.05f)
    }

    @Test
    fun displayRotationKeepsScreenRightAsRight() {
        val p = pipeline().calibrated()
        p.setDisplayRotation(1) // landscape: screen-right is the device's −Y edge
        val out = p.feed(1000) { rot(Vec3.X, 15.0) } // drops the device's −Y edge for a flat phone
        assertTrue("x=${out.stickX}", out.stickX > 0.6)
    }

    @Test
    fun dropPausesOnlyWhileActive() {
        val p = pipeline().calibrated()
        p.onAcceleration(0.0, 0.0, 40.0, t * 1_000_000)
        assertTrue(events.contains(PipelineEvent.Safety(com.clu.motion.core.safety.SafetyEvent.PauseRequest(com.clu.motion.core.safety.PauseReason.DROP_DETECTED))))
        events.clear()
        p.active = false
        p.onAcceleration(0.0, 0.0, 40.0, t * 1_000_000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun gyroAimTracksSlowTiltAndIgnoresRestingTremorAndSpasms() {
        val p = pipeline(Presets.shooter()).calibrated()
        val start = p.feed(500) { Quaternion.IDENTITY }

        // Slow tilt to the right, 3°/s for 2 s: 6° of a 15° range → 0.4 aim units.
        val t0 = t
        p.feed(2000) { ms -> rot(Vec3.Y, 3.0 * (ms - t0) / 1000.0) }
        val tilted = p.feed(1500) { rot(Vec3.Y, 6.0) }
        assertEquals(0.4, tilted.aimX - start.aimX, 0.03)
        assertEquals(0.0, tilted.aimY - start.aimY, 0.01)

        // Resting tremor (0.8° at 6 Hz) around that pose shakes the aim by a small fraction of a degree.
        val axis = Vec3(1.0, 1.0, 0.0).normalized()
        val shaking = (0 until 300).map { p.feed(10) { ms -> rot(Vec3.Y, 6.0) * rot(axis, 0.8 * sin(2 * PI * 6 * ms / 1000.0)) } }
        val spread = (shaking.maxOf { it.aimX } - shaking.minOf { it.aimX }) * 15
        assertTrue("aim shook by $spread° for 1.6° of tremor", spread < 0.1)

        // A spasm throws the phone 30° further in 50 ms and it stays there: the aim must not swing.
        val before = p.feed(200) { rot(Vec3.Y, 6.0) }
        val t1 = t
        p.feed(50) { ms -> rot(Vec3.Y, 6.0 + 30.0 * (ms - t1) / 50.0) }
        val after = p.feed(1500) { rot(Vec3.Y, 36.0) }
        assertTrue("spasm moved the aim by ${(after.aimX - before.aimX) * 15}°", abs(after.aimX - before.aimX) * 15 < 1.0)

        // Deliberate motion afterwards is followed again.
        val t2 = t
        p.feed(1000) { ms -> rot(Vec3.Y, 36.0 - 3.0 * (ms - t2) / 1000.0) }
        val back = p.feed(1500) { rot(Vec3.Y, 33.0) }
        assertEquals(-3.0 / 15, back.aimX - after.aimX, 0.03)
    }

    @Test
    fun precisionAimScalesTheAimDown() {
        val p = pipeline(Presets.shooter()).calibrated()
        p.precisionAim = true
        val start = p.feed(500) { Quaternion.IDENTITY }
        val t0 = t
        p.feed(2000) { ms -> rot(Vec3.Y, 3.0 * (ms - t0) / 1000.0) }
        val end = p.feed(1500) { rot(Vec3.Y, 6.0) }
        assertTrue(end.precisionAim)
        assertEquals(0.4 * Presets.shooter().aim.precisionScale, end.aimX - start.aimX, 0.02)
    }
}
