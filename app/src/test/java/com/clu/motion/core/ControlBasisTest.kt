package com.clu.motion.core

import com.clu.motion.core.math.Vec3
import com.clu.motion.profile.AxisConfig
import com.clu.motion.profile.AxisMode
import com.clu.motion.profile.LearnedAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlBasisTest {

    @Test
    fun screenAxesFollowDisplayRotation() {
        // Surface.ROTATION_90: device turned counter-clockwise; screen-right is the device's bottom.
        val (sx, sy) = ControlBasis.screenAxes(1)
        assertVec(Vec3(0.0, -1.0, 0.0), sx)
        assertVec(Vec3(1.0, 0.0, 0.0), sy)
        val (sx3, sy3) = ControlBasis.screenAxes(3)
        assertVec(Vec3(0.0, 1.0, 0.0), sx3)
        assertVec(Vec3(-1.0, 0.0, 0.0), sy3)
    }

    @Test
    fun gravityBasisForFlatPhoneMatchesDeviceAxes() {
        val g = ControlBasis.gravityTilt(Vec3.Z, 0)
        assertVec(Vec3.Y, g.ex)
        assertVec(Vec3.X, g.ey)
        assertVec(Vec3.Z, g.ez)
    }

    @Test
    fun gravityBasisForUprightPhoneSteersLikeAWheel() {
        // Held upright facing the user: "right" is a clockwise turn about the screen normal.
        val g = ControlBasis.gravityTilt(Vec3.Y, 0)
        assertVec(Vec3(0.0, 0.0, -1.0), g.ex)
        assertVec(Vec3.X, g.ey)
    }

    @Test
    fun droppingTheScreensRightEdgeIsPositiveXInEveryRotation() {
        for (rotation in 0..3) {
            val (sx, _) = ControlBasis.screenAxes(rotation)
            val b = ControlBasis.gravityTilt(Vec3.Z, rotation)
            // A small rotation about ex must move screen-right downward (−Z) for a flat phone.
            val moved = rot(b.ex, 10.0).rotate(sx)
            assertTrue("rotation $rotation", moved.z < -0.1)
        }
    }

    @Test
    fun learnedAxesFollowLaterDisplayRotation() {
        val learned = LearnedAxes(Vec3.Y, Vec3.X, referenceRotation = 0, 10.0, 10.0, 10.0, 10.0)
        val axes = AxisConfig(mode = AxisMode.LEARNED, learned = learned)
        val atRef = ControlBasis.compute(axes, Vec3.Z, 0)
        val at90 = ControlBasis.compute(axes, Vec3.Z, 1)
        assertVec(ControlBasis.device(0).ex, atRef.ex)
        assertVec(ControlBasis.device(1).ex, at90.ex)
        assertVec(ControlBasis.device(1).ey, at90.ey)
    }

    @Test
    fun inversionSwapsDirectionalRanges() {
        val learned = LearnedAxes(Vec3.Y, Vec3.X, 0, rangePosX = 20.0, rangeNegX = 5.0, rangePosY = 8.0, rangeNegY = 4.0)
        val r = ControlBasis.ranges(AxisConfig(mode = AxisMode.LEARNED, learned = learned, invertX = true))
        assertEquals(5.0, r.posX, 0.0)
        assertEquals(20.0, r.negX, 0.0)
        assertEquals(8.0, r.posY, 0.0)
    }
}
