package com.clu.motion.core

import com.clu.motion.core.math.Quaternion
import com.clu.motion.core.math.Vec3
import org.junit.Assert.assertEquals

fun rot(axis: Vec3, deg: Double): Quaternion = Quaternion.fromAxisAngle(axis, Math.toRadians(deg))

fun assertVec(expected: Vec3, actual: Vec3, tol: Double = 1e-6) {
    assertEquals("x of $actual", expected.x, actual.x, tol)
    assertEquals("y of $actual", expected.y, actual.y, tol)
    assertEquals("z of $actual", expected.z, actual.z, tol)
}
