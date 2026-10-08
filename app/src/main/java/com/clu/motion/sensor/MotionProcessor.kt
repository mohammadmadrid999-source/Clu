package com.clu.motion.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.clu.motion.core.MotionFrame
import com.clu.motion.core.MotionPipeline
import com.clu.motion.core.PipelineEvent
import com.clu.motion.core.math.Quaternion
import com.clu.motion.profile.ControlProfile
import com.clu.motion.profile.SensorConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which physical sensor currently provides orientation. */
enum class OrientationSource(val sensorType: Int, val tiltOnly: Boolean) {
    GAME_ROTATION_VECTOR(Sensor.TYPE_GAME_ROTATION_VECTOR, false),
    ROTATION_VECTOR(Sensor.TYPE_ROTATION_VECTOR, false),
    GRAVITY(Sensor.TYPE_GRAVITY, true),
    ACCELEROMETER(Sensor.TYPE_ACCELEROMETER, true),
}

data class SensorStatus(
    val source: OrientationSource?,
    val reliable: Boolean,
    /** Measured orientation sample rate over the last second (0 until known). */
    val sampleRateHz: Double = 0.0,
)

/**
 * Sensor front-end for [MotionPipeline].
 *
 * - Fused orientation only (GAME_ROTATION_VECTOR by default, see [SensorConfig.useMagnetometer]),
 *   never integrated raw gyro, so there is no accumulating drift. Falls back to gravity /
 *   accelerometer tilt on devices without a gyroscope.
 * - All sensor callbacks and DSP run on one elevated-priority HandlerThread: no locks in the hot
 *   path, deterministic ordering, and the UI thread never touches sensor data.
 * - maxReportLatencyUs = 0 disables FIFO batching, which would otherwise add up to seconds of lag.
 * - Results are published through a conflated [StateFlow]: consumers always read the newest
 *   frame and a slow consumer can never back up the sensor thread.
 *
 * Pipeline events (triggers, safety, calibration) are delivered on the sensor thread via [onEvent].
 */
class MotionProcessor(
    context: Context,
    private val onEvent: (PipelineEvent) -> Unit,
) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    private val _frames = MutableStateFlow(MotionFrame.IDLE)
    val frames: StateFlow<MotionFrame> = _frames.asStateFlow()

    private val _status = MutableStateFlow(SensorStatus(null, true))
    val status: StateFlow<SensorStatus> = _status.asStateFlow()

    /** Each start() gets its own thread, listener and pipeline, so a fast stop→start can't interfere. */
    @Volatile
    private var session: Session? = null

    val isRunning get() = session != null

    /** Main thread. */
    fun start(profile: ControlProfile) {
        if (session != null) return
        session = Session(profile)
    }

    /** Main thread. */
    fun stop() {
        val s = session ?: return
        session = null
        s.shutdown()
        _frames.value = MotionFrame.IDLE
        _status.value = SensorStatus(null, true)
    }

    fun setProfile(profile: ControlProfile) = session?.post { applyProfile(profile) }

    fun recalibrate() = session?.post { pipeline.beginCalibration() }

    fun learnAxes() = session?.post { pipeline.beginLearning() }

    fun resetSafetySession() = session?.post { pipeline.resetSafetySession() }

    fun setPrecisionAim(on: Boolean) = session?.post { pipeline.precisionAim = on }

    /** Live play vs paused: paused still tracks motion (HUD, hands-free resume) but not safety. */
    fun setActive(active: Boolean) = session?.post {
        pipeline.active = active
        if (!active) pipeline.releaseHeldTriggers()
    }

    /** Screen off: stop sampling entirely to save power. */
    fun suspendSensors() = session?.post { unregisterSensors() }

    fun resumeSensors() = session?.post { if (!registered) registerSensors() }

    private inner class Session(profile: ControlProfile) : SensorEventListener, DisplayManager.DisplayListener {
        private val thread = HandlerThread("clu-motion", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        private val handler = Handler(thread.looper)

        // Everything below is confined to [thread].
        val pipeline = MotionPipeline(profile, ::deliver)
        private var sensorConfig = profile.sensor
        private var source: OrientationSource? = null
        var registered = false
            private set
        private val gravity = DoubleArray(3)
        private var gravityInitialized = false
        private var rateWindowStartNs = 0L
        private var rateWindowCount = 0

        init {
            handler.post {
                pipeline.setDisplayRotation(currentRotation())
                registerSensors()
            }
            // Delivered on the sensor thread, so rotation changes need no synchronisation.
            displayManager.registerDisplayListener(this, handler)
        }

        fun post(block: Session.() -> Unit) {
            handler.post { if (session === this) block() }
        }

        fun shutdown() {
            displayManager.unregisterDisplayListener(this)
            handler.post {
                unregisterSensors()
                pipeline.stop()
            }
            thread.quitSafely()
        }

        fun applyProfile(profile: ControlProfile) {
            pipeline.setProfile(profile)
            if (profile.sensor != sensorConfig) {
                sensorConfig = profile.sensor
                if (registered) {
                    unregisterSensors()
                    registerSensors()
                }
            }
        }

        private var faultReported = false

        override fun onSensorChanged(event: SensorEvent) {
            // An exception escaping here would crash the process, which on Android 16 leaves the
            // accessibility service "enabled but not running". Report once and keep the thread alive.
            try {
                handle(event)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Sensor sample failed", e)
                if (!faultReported) {
                    faultReported = true
                    deliver(PipelineEvent.Fault(e))
                }
            }
        }

        private fun handle(event: SensorEvent) {
            val v = event.values
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR ->
                    publish(pipeline.onOrientation(Quaternion.fromSensorVector(v), event.timestamp, SystemClock.uptimeMillis()))

                Sensor.TYPE_GRAVITY -> if (source == OrientationSource.GRAVITY) {
                    val q = Quaternion.fromGravity(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                    publish(pipeline.onOrientation(q, event.timestamp, SystemClock.uptimeMillis()))
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    pipeline.onAcceleration(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), event.timestamp)
                    if (source == OrientationSource.ACCELEROMETER) {
                        lowPassGravity(v)
                        val q = Quaternion.fromGravity(gravity[0], gravity[1], gravity[2])
                        publish(pipeline.onOrientation(q, event.timestamp, SystemClock.uptimeMillis()))
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            if (session !== this || sensor.type != source?.sensorType) return
            val reliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
            _status.value = SensorStatus(source, reliable)
            if (!reliable) Log.w(TAG, "Orientation sensor ${sensor.name} reports unreliable accuracy")
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) pipeline.setDisplayRotation(currentRotation())
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        fun registerSensors() {
            val preferred = if (sensorConfig.useMagnetometer) {
                listOf(OrientationSource.ROTATION_VECTOR, OrientationSource.GAME_ROTATION_VECTOR)
            } else {
                listOf(OrientationSource.GAME_ROTATION_VECTOR, OrientationSource.ROTATION_VECTOR)
            } + listOf(OrientationSource.GRAVITY, OrientationSource.ACCELEROMETER)

            val chosen = preferred.firstNotNullOfOrNull { s ->
                sensorManager.getDefaultSensor(s.sensorType)?.let { s to it }
            }
            if (chosen == null) {
                Log.e(TAG, "No orientation sensor available")
                _status.value = SensorStatus(null, false)
                return
            }
            source = chosen.first
            gravityInitialized = false
            sensorManager.registerListener(this, chosen.second, sensorConfig.samplingPeriodUs, NO_BATCHING, handler)
            // The accelerometer feeds drop detection (and is the orientation source on the last fallback).
            if (chosen.first != OrientationSource.ACCELEROMETER) {
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sensorManager.registerListener(this, it, ACCEL_PERIOD_US, NO_BATCHING, handler)
                }
            }
            registered = true
            _status.value = SensorStatus(chosen.first, true)
            Log.i(TAG, "Orientation source: ${chosen.first} (${chosen.second.name})")
        }

        fun unregisterSensors() {
            if (!registered) return
            sensorManager.unregisterListener(this)
            registered = false
        }

        private fun publish(frame: MotionFrame) {
            if (session !== this) return
            _frames.value = frame
            measureRate(frame.sensorTimeNanos)
        }

        private fun measureRate(tNanos: Long) {
            if (rateWindowCount == 0) rateWindowStartNs = tNanos
            rateWindowCount++
            val elapsed = tNanos - rateWindowStartNs
            if (elapsed >= 1_000_000_000L) {
                val hz = (rateWindowCount - 1) * 1e9 / elapsed
                _status.value = _status.value.copy(sampleRateHz = hz)
                rateWindowCount = 0
            }
        }

        private fun deliver(event: PipelineEvent) {
            if (session === this) onEvent(event)
        }

        private fun lowPassGravity(v: FloatArray) {
            if (!gravityInitialized) {
                for (i in 0..2) gravity[i] = v[i].toDouble()
                gravityInitialized = true
                return
            }
            for (i in 0..2) gravity[i] += ACCEL_GRAVITY_ALPHA * (v[i] - gravity[i])
        }
    }

    private fun currentRotation(): Int = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0

    private companion object {
        const val TAG = "MotionProcessor"
        const val NO_BATCHING = 0
        const val ACCEL_PERIOD_US = 20_000
        const val ACCEL_GRAVITY_ALPHA = 0.1
    }
}
