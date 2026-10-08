package com.clu.motion.core

import com.clu.motion.core.calibration.AxisLearner
import com.clu.motion.core.calibration.CalibrationCapture
import com.clu.motion.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class CalibrationTest {

    @Test
    fun captureAveragesNeutralAndProfilesTremor() {
        val center = rot(Vec3(1.0, 0.0, 0.3).normalized(), 50.0)
        val capture = CalibrationCapture(settleMs = 500, captureMs = 1500)
        capture.begin()
        var t = 0L
        var done: CalibrationCapture.Status.Done? = null
        while (done == null && t < 5000) {
            val wobble = 1.0 * sin(2 * PI * 5.0 * t / 1000.0) // 1° amplitude, 5 Hz
            val s = capture.add(center * rot(Vec3.X, wobble), t)
            if (s is CalibrationCapture.Status.Done) done = s
            t += 10
        }
        val result = done!!.result
        assertEquals(1.0, abs(result.neutral dot center), 1e-5)
        assertEquals(1.0 / kotlin.math.sqrt(2.0), result.tremor.rmsDeg, 0.05) // RMS of a sine
        assertEquals(5.0, result.tremor.dominantHz, 0.5)
        assertFalse(result.unstable)
    }

    @Test
    fun excessiveMotionRetriesThenFlagsUnstable() {
        val capture = CalibrationCapture(settleMs = 100, captureMs = 300, maxDeviationDeg = 8.0, maxAttempts = 2)
        capture.begin()
        var t = 0L
        var result: CalibrationCapture.Status.Done? = null
        var captureRestarts = 0
        var lastProgress = 0.0
        while (result == null && t < 10_000) {
            val s = capture.add(rot(Vec3.Y, 20 * sin(2 * PI * 2.0 * t / 1000.0)), t)
            if (s is CalibrationCapture.Status.Capturing) {
                if (s.progress < lastProgress) captureRestarts++
                lastProgress = s.progress
            }
            if (s is CalibrationCapture.Status.Done) result = s
            t += 10
        }
        assertEquals(1, captureRestarts)
        assertTrue(result!!.result.unstable)
    }

    @Test
    fun axisLearnerBuildsOrthonormalAxesAndRangesFromComfortableMoves() {
        val rightAxis = Vec3(0.2, 0.95, 0.1).normalized() // wrist roll with a little compound motion
        val downAxis = Vec3(0.97, -0.1, 0.2).normalized()
        val learner = AxisLearner(moveMs = 3500, returnMs = 1500, rangeFraction = 0.7)
        learner.begin()
        val moves = mapOf(
            AxisLearner.Step.RIGHT to rightAxis * 20.0,
            AxisLearner.Step.LEFT to rightAxis * -10.0, // asymmetric range (e.g. hemiplegia)
            AxisLearner.Step.UP to downAxis * -12.0,
            AxisLearner.Step.DOWN to downAxis * 12.0,
        )
        var t = 0L
        var done: AxisLearner.Status.Done? = null
        var current = AxisLearner.Step.RIGHT
        var returning = false
        while (done == null && t < 60_000) {
            val omega = if (returning) Vec3.ZERO else moves.getValue(current)
            when (val s = learner.add(omega, t)) {
                is AxisLearner.Status.Prompt -> {
                    current = s.step
                    returning = s.returning
                }
                is AxisLearner.Status.Done -> done = s
            }
            t += 10
        }
        val axes = learner.result(done!!.peaks, Vec3.Y, Vec3.X, referenceRotation = 0, defaultRangeDeg = 18.0)
        assertVec(rightAxis, axes.ex, 1e-3)
        assertEquals(0.0, axes.ex dot axes.ey, 1e-9)
        assertEquals(1.0, axes.ey.length(), 1e-9)
        assertTrue((axes.ey dot downAxis) > 0.97)
        assertEquals(14.0, axes.rangePosX, 0.2)
        assertEquals(7.0, axes.rangeNegX, 0.2)
    }

    @Test
    fun axisLearnerFallsBackForDirectionsTheUserCannotMove() {
        val learner = AxisLearner()
        val axes = learner.result(
            mapOf(AxisLearner.Step.RIGHT to Vec3(0.0, 15.0, 0.0)),
            fallbackEx = Vec3.Y,
            fallbackEy = Vec3.X,
            referenceRotation = 0,
            defaultRangeDeg = 18.0,
        )
        assertVec(Vec3.Y, axes.ex)
        assertVec(Vec3.X, axes.ey)
        assertEquals(10.5, axes.rangePosX, 1e-9)
        assertEquals(18.0, axes.rangeNegX, 1e-9)
    }
}
