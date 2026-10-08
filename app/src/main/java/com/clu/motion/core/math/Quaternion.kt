package com.clu.motion.core.math

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable double-precision 3-vector. Rotation axes are expressed in the device frame. */
@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(this dot this)

    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-12) ZERO else this * (1.0 / l)
    }

    /** Rotates about +Z by [quarterTurns] × 90° (right-hand rule). */
    fun rotatedAboutZ(quarterTurns: Int): Vec3 = when (Math.floorMod(quarterTurns, 4)) {
        0 -> this
        1 -> Vec3(-y, x, z)
        2 -> Vec3(-x, -y, z)
        else -> Vec3(y, -x, z)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
    }
}

/**
 * Unit quaternion (w, x, y, z). Orientation quaternions follow the Android sensor convention:
 * they rotate vectors from the device frame into the world frame (world = q · v · q*).
 */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {

    operator fun times(o: Quaternion) = Quaternion(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    operator fun unaryMinus() = Quaternion(-w, -x, -y, -z)

    fun conjugate() = Quaternion(w, -x, -y, -z)

    infix fun dot(o: Quaternion) = w * o.w + x * o.x + y * o.y + z * o.z

    fun norm() = sqrt(this dot this)

    fun normalized(): Quaternion {
        val n = norm()
        return if (n < 1e-12) IDENTITY else Quaternion(w / n, x / n, y / n, z / n)
    }

    /** Rotates [v] by this quaternion (q · v · q*), assuming unit length. */
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2.0
        return v + t * w + (u cross t)
    }

    /**
     * Converts to a rotation vector (axis × angle, radians) along the shortest arc.
     * Unlike Euler angles this has no gimbal lock and no axis-order dependence, which keeps
     * the projection onto arbitrary (learned) control axes well defined.
     */
    fun toRotationVector(): Vec3 {
        var q = normalized()
        if (q.w < 0) q = -q
        val s = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
        if (s < 1e-9) return Vec3(2 * q.x, 2 * q.y, 2 * q.z)
        val k = 2 * atan2(s, q.w) / s
        return Vec3(q.x * k, q.y * k, q.z * k)
    }

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        /**
         * Builds a quaternion from Android rotation-vector sensor values
         * (TYPE_ROTATION_VECTOR / TYPE_GAME_ROTATION_VECTOR). Mirrors
         * SensorManager.getQuaternionFromVector, including devices that omit values[3].
         */
        fun fromSensorVector(values: FloatArray): Quaternion {
            val x = values[0].toDouble()
            val y = values[1].toDouble()
            val z = values[2].toDouble()
            val w = if (values.size >= 4) {
                values[3].toDouble()
            } else {
                sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
            }
            return Quaternion(w, x, y, z).normalized()
        }

        fun fromAxisAngle(axis: Vec3, angleRad: Double): Quaternion {
            val a = axis.normalized()
            val h = angleRad / 2
            val s = sin(h)
            return Quaternion(cos(h), a.x * s, a.y * s, a.z * s)
        }

        /** Shortest-arc rotation taking unit vector [from] onto unit vector [to]. */
        fun shortestArc(from: Vec3, to: Vec3): Quaternion {
            val d = from dot to
            if (d < -0.999999) {
                var axis = Vec3.X cross from
                if (axis.length() < 1e-6) axis = Vec3.Y cross from
                return fromAxisAngle(axis, PI)
            }
            val c = from cross to
            return Quaternion(1 + d, c.x, c.y, c.z).normalized()
        }

        /**
         * Tilt-only orientation from a gravity/accelerometer reading (device frame).
         * Used as a fallback on devices without a fused rotation-vector sensor; yaw is undefined.
         */
        fun fromGravity(ax: Double, ay: Double, az: Double): Quaternion {
            val g = Vec3(ax, ay, az).normalized()
            if (g == Vec3.ZERO) return IDENTITY
            return shortestArc(g, Vec3.Z)
        }

        /** Sign-aligned normalized mean. Accurate for the small dispersion of a "hold still" capture. */
        fun average(samples: List<Quaternion>): Quaternion {
            require(samples.isNotEmpty()) { "No samples" }
            val ref = samples[0]
            var w = 0.0
            var x = 0.0
            var y = 0.0
            var z = 0.0
            for (q in samples) {
                val s = if ((q dot ref) < 0) -1.0 else 1.0
                w += s * q.w
                x += s * q.x
                y += s * q.y
                z += s * q.z
            }
            return Quaternion(w, x, y, z).normalized()
        }
    }
}

const val RAD_TO_DEG = 180.0 / PI
