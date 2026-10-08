package com.clu.motion.core

import com.clu.motion.core.math.Quaternion
import com.clu.motion.core.math.RAD_TO_DEG
import com.clu.motion.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class QuaternionTest {

    @Test
    fun rotatesVectorsRightHanded() {
        assertVec(Vec3.Y, rot(Vec3.Z, 90.0).rotate(Vec3.X))
        assertVec(Vec3.Z, rot(Vec3.X, 90.0).rotate(Vec3.Y))
    }

    @Test
    fun sensorVectorWithoutScalarIsReconstructed() {
        val q = rot(Vec3(1.0, 2.0, 3.0).normalized(), 40.0)
        val withW = Quaternion.fromSensorVector(floatArrayOf(q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), q.w.toFloat()))
        val withoutW = Quaternion.fromSensorVector(floatArrayOf(q.x.toFloat(), q.y.toFloat(), q.z.toFloat()))
        assertEquals(1.0, kotlin.math.abs(withW dot withoutW), 1e-6)
    }

    @Test
    fun rotationVectorIsAxisTimesAngleOnShortestArc() {
        val axis = Vec3(0.0, 0.6, 0.8)
        assertVec(axis * Math.toRadians(30.0), rot(axis, 30.0).toRotationVector(), 1e-9)
        // q and -q are the same rotation.
        assertVec(axis * Math.toRadians(30.0), (-rot(axis, 30.0)).toRotationVector(), 1e-9)
        // 350° is -10° the short way round.
        assertVec(axis * Math.toRadians(-10.0), rot(axis, 350.0).toRotationVector(), 1e-9)
    }

    @Test
    fun relativeRotationRecoversTilt() {
        val neutral = rot(Vec3(0.2, 0.9, 0.4).normalized(), 70.0)
        val tilt = rot(Vec3.Y, 12.0)
        val current = neutral * tilt
        val omega = (neutral.conjugate() * current).toRotationVector() * RAD_TO_DEG
        assertVec(Vec3(0.0, 12.0, 0.0), omega, 1e-6)
    }

    @Test
    fun shortestArcMapsFromOntoTo() {
        val from = Vec3(0.3, -0.5, 0.8).normalized()
        assertVec(Vec3.Z, Quaternion.shortestArc(from, Vec3.Z).rotate(from), 1e-9)
        assertVec(Vec3.Z, Quaternion.shortestArc(-Vec3.Z, Vec3.Z).rotate(-Vec3.Z), 1e-9)
    }

    @Test
    fun gravityQuaternionLevelsTheMeasuredUpVector() {
        val q = Quaternion.fromGravity(0.0, 6.9, 6.9)
        assertVec(Vec3.Z, q.rotate(Vec3(0.0, 6.9, 6.9).normalized()), 1e-9)
    }

    @Test
    fun averageOfJitteredSamplesIsTheCenter() {
        val center = rot(Vec3.X, 25.0)
        val samples = (0 until 200).map { i ->
            val wobble = 2.0 * sin(2 * PI * 6 * i / 100.0)
            val q = center * rot(Vec3.Y, wobble)
            if (i % 2 == 0) q else -q // sign flips must not matter
        }
        val avg = Quaternion.average(samples)
        assertEquals(1.0, kotlin.math.abs(avg dot center), 1e-5)
    }
}
