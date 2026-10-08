package com.clu.motion.core

import com.clu.motion.core.aim.AimProcessor
import com.clu.motion.core.calibration.AxisLearner
import com.clu.motion.core.calibration.CalibrationCapture
import com.clu.motion.core.calibration.CalibrationResult
import com.clu.motion.core.calibration.TremorProfile
import com.clu.motion.core.filter.SpasmGate
import com.clu.motion.core.filter.TremorFilter2D
import com.clu.motion.core.gesture.Direction4
import com.clu.motion.core.gesture.DwellDetector
import com.clu.motion.core.gesture.DwellEvent
import com.clu.motion.core.gesture.FlickDetector
import com.clu.motion.core.math.Quaternion
import com.clu.motion.core.math.RAD_TO_DEG
import com.clu.motion.core.math.Vec3
import com.clu.motion.core.response.AxisRanges
import com.clu.motion.core.response.ResponseMapper
import com.clu.motion.core.response.StickOutput
import com.clu.motion.core.safety.SafetyEvent
import com.clu.motion.core.safety.SafetyMonitor
import com.clu.motion.profile.AxisConfig
import com.clu.motion.profile.AxisMode
import com.clu.motion.profile.ControlProfile
import com.clu.motion.profile.LearnedAxes
import com.clu.motion.profile.TriggerKind
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class PipelinePhase { IDLE, CALIBRATING, LEARNING, RUNNING }

enum class TriggerPhase {
    PRESS,
    RELEASE,

    /** Momentary trigger (flick, click): press + release in one. */
    PULSE,
}

sealed interface PipelineEvent {
    data class Trigger(val kind: TriggerKind, val phase: TriggerPhase) : PipelineEvent
    data class Safety(val event: SafetyEvent) : PipelineEvent
    data class Calibrated(val result: CalibrationResult) : PipelineEvent
    data class AxesLearned(val axes: LearnedAxes) : PipelineEvent

    /** Processing a sample threw; reported by the sensor front-end, never by the pipeline itself. */
    data class Fault(val error: Throwable) : PipelineEvent
}

/** One processed sample, published to the injector and the HUD. */
data class MotionFrame(
    val sensorTimeNanos: Long,
    /** SystemClock.uptimeMillis() when produced; consumers use it to detect stale frames. */
    val uptimeMs: Long,
    val phase: PipelinePhase,
    val phaseProgress: Float,
    val learnStep: AxisLearner.Step?,
    val learnReturning: Boolean,
    /** Final virtual-stick deflection, screen orientation (x right, y down), magnitude ≤ 1. */
    val stickX: Float,
    val stickY: Float,
    /** Filtered tilt along the control axes, degrees. */
    val tiltXDeg: Float,
    val tiltYDeg: Float,
    val twistDeg: Float,
    val deadzoneDeg: Float,
    val rangeDeg: Float,
    val dwellProgress: Float,
    val dwellDirection: Direction4?,
    val spasmHold: Boolean,
    /** Cumulative gyro aim in whole-range units (see AimProcessor); consumers use differences. */
    val aimX: Double = 0.0,
    val aimY: Double = 0.0,
    val precisionAim: Boolean = false,
) {
    val isNeutral get() = stickX == 0f && stickY == 0f

    companion object {
        val IDLE = MotionFrame(
            0, 0, PipelinePhase.IDLE, 0f, null, false, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, null, false,
        )
    }
}

/**
 * Orthonormal control axes in the neutral device frame:
 * [ex] = rotation that means "stick right", [ey] = "stick down", [ez] = ey × ex = twist
 * (positive = counter-clockwise as seen from the screen / from above).
 */
data class ControlBasis(val ex: Vec3, val ey: Vec3, val ez: Vec3) {
    companion object {
        /** Screen right / screen up expressed in device coordinates for a Surface.ROTATION_* value. */
        fun screenAxes(rotation: Int): Pair<Vec3, Vec3> =
            Vec3.X.rotatedAboutZ(-rotation) to Vec3.Y.rotatedAboutZ(-rotation)

        /** Tilting about screen-up moves the right edge away from the viewer; about screen-right, the top edge. */
        fun device(rotation: Int): ControlBasis {
            val (sx, sy) = screenAxes(rotation)
            return ControlBasis(ex = sy, ey = sx, ez = sx cross sy)
        }

        /**
         * Horizontal axes from gravity: "right" drops the screen's right edge, "down" lifts its top
         * edge, whatever the posture. Degenerates only when screen-right points straight up.
         */
        fun gravityTilt(up: Vec3, rotation: Int): ControlBasis {
            val (sx, _) = screenAxes(rotation)
            val exRaw = up cross sx
            if (exRaw.length() < DEGENERATE) return device(rotation)
            val ex = exRaw.normalized()
            val ey = ex cross up
            return ControlBasis(ex, ey, ey cross ex)
        }

        fun compute(axes: AxisConfig, up: Vec3?, rotation: Int): ControlBasis {
            val gravity = up?.let { gravityTilt(it, rotation) } ?: device(rotation)
            return when (axes.mode) {
                AxisMode.DEVICE -> device(rotation)
                AxisMode.GRAVITY_TILT -> gravity
                AxisMode.GRAVITY_TURN -> {
                    if (up == null) return gravity
                    val ex = -up // turning clockwise seen from above = "right"
                    ControlBasis(ex, gravity.ey, gravity.ey cross ex)
                }
                AxisMode.LEARNED -> {
                    val learned = axes.learned ?: return gravity
                    // Learned in one display rotation; re-express if the game rotated since.
                    val turns = -(rotation - learned.referenceRotation)
                    val ex = learned.ex.rotatedAboutZ(turns)
                    val ey = learned.ey.rotatedAboutZ(turns)
                    ControlBasis(ex, ey, ey cross ex)
                }
            }
        }

        fun ranges(axes: AxisConfig): AxisRanges {
            val l = axes.learned
            val r = if (axes.mode == AxisMode.LEARNED && l != null) {
                AxisRanges(l.rangePosX, l.rangeNegX, l.rangePosY, l.rangeNegY)
            } else {
                AxisRanges.symmetric(axes.fullTiltDeg)
            }
            // Inversion flips which physical direction each range belongs to.
            return AxisRanges(
                posX = if (axes.invertX) r.negX else r.posX,
                negX = if (axes.invertX) r.posX else r.negX,
                posY = if (axes.invertY) r.negY else r.posY,
                negY = if (axes.invertY) r.posY else r.negY,
            )
        }

        private const val DEGENERATE = 0.3
    }
}

/**
 * The complete per-sample signal chain, free of Android types:
 *
 *   orientation q ─► relative to neutral ─► rotation vector ─► project on control axes
 *     ─► spasm gate ─► tremor filter ─► deadzone / range / curve ─► stick
 *     └► flick detectors (raw)            └► dwell detector (stick)      └► safety monitor
 *
 * Not thread-safe by design: confine to one thread (the sensor HandlerThread).
 */
class MotionPipeline(profile: ControlProfile, private val sink: (PipelineEvent) -> Unit) {

    var profile = profile
        private set
    var phase = PipelinePhase.IDLE
        private set

    /** True while play is live (not paused). Gates safety pauses and the rest timer. */
    var active = false

    var neutral: Quaternion? = null
        private set
    var basis = ControlBasis.device(0)
        private set
    var tremor = TremorProfile.NONE
        private set

    private var upInNeutral: Vec3? = null
    private var displayRotation = 0

    private var filter = TremorFilter2D.from(profile.filter)
    private val gate = SpasmGate(0.0, 0, 0)
    private lateinit var mapper: ResponseMapper
    private var rangeAverage = 0.0
    private var dwell = DwellDetector(profile.dwell)
    private var flickX = FlickDetector(profile.flick)
    private var flickY = FlickDetector(profile.flick)
    private var flickZ = FlickDetector(profile.flick)
    private var safety = SafetyMonitor(profile.safety)
    private val aim = AimProcessor(profile.aim)
    private var aimSpasm = false
    private var aimSpasmUntilMs = 0L
    private val capture = CalibrationCapture()
    private val learner = AxisLearner()
    private var learnAfterCalibration = false

    private var lastOmega: Vec3? = null
    private var lastOmegaT = 0L
    private var lastStick = StickOutput.ZERO
    private var lastFilteredX = 0.0
    private var lastFilteredY = 0.0
    private var lastTwist = 0.0
    private var phaseProgress = 0.0
    private var learnStep: AxisLearner.Step? = null
    private var learnReturning = false

    init {
        applyGateConfig()
        rebuildMapper()
        tuneAim()
    }

    fun setProfile(p: ControlProfile) {
        val old = profile
        profile = p
        if (old.filter != p.filter) {
            filter = TremorFilter2D.from(p.filter)
            applyGateConfig()
            resetSignal()
        }
        if (old.dwell != p.dwell) {
            dwell.reset()?.let(::emitDwell)
            dwell = DwellDetector(p.dwell)
        }
        if (old.flick != p.flick || old.filter.autoTuneFromTremor != p.filter.autoTuneFromTremor) {
            flickX = FlickDetector(p.flick)
            flickY = FlickDetector(p.flick)
            flickZ = FlickDetector(p.flick)
            tuneFlicks()
        }
        if (old.aim != p.aim) aim.config = p.aim
        if (old.safety != p.safety) {
            safety = SafetyMonitor(p.safety).also { it.setTremorBaseline(tremor.rmsDeg) }
        }
        if (old.axes != p.axes) {
            updateBasis()
            resetSignal()
        }
        rebuildMapper()
        tuneAim()
    }

    fun setDisplayRotation(rotation: Int) {
        if (rotation == displayRotation) return
        displayRotation = rotation
        updateBasis()
        resetSignal()
    }

    /** Starts a "hold still" capture of the neutral posture; optionally follows with axis learning. */
    fun beginCalibration(thenLearn: Boolean = false) {
        dwell.reset()?.let(::emitDwell)
        capture.begin()
        learnAfterCalibration = thenLearn
        phase = PipelinePhase.CALIBRATING
        phaseProgress = 0.0
    }

    fun beginLearning() {
        if (neutral == null) {
            beginCalibration(thenLearn = true)
            return
        }
        dwell.reset()?.let(::emitDwell)
        learner.begin()
        phase = PipelinePhase.LEARNING
        phaseProgress = 0.0
    }

    fun stop() {
        dwell.reset()?.let(::emitDwell)
        phase = PipelinePhase.IDLE
        neutral = null
        upInNeutral = null
        resetSignal()
    }

    fun resetSafetySession() = safety.resetSession()

    /** Precision aim: scales gyro aim down (see AimConfig.precisionScale). */
    var precisionAim: Boolean
        get() = aim.precision
        set(value) {
            aim.precision = value
        }

    /** Releases a held dwell (e.g. when play pauses) so HOLD bindings don't stick. */
    fun releaseHeldTriggers() {
        dwell.reset()?.let(::emitDwell)
    }

    fun onAcceleration(ax: Double, ay: Double, az: Double, tNanos: Long) {
        if (!active || phase != PipelinePhase.RUNNING) return
        safety.onAcceleration(ax, ay, az, tNanos / 1_000_000)?.let { sink(PipelineEvent.Safety(it)) }
    }

    fun onOrientation(q: Quaternion, tNanos: Long, uptimeMs: Long): MotionFrame {
        val tMs = tNanos / 1_000_000
        when (phase) {
            PipelinePhase.IDLE -> Unit
            PipelinePhase.CALIBRATING -> calibrate(q, tMs)
            PipelinePhase.LEARNING -> learn(q, tMs)
            PipelinePhase.RUNNING -> process(q, tNanos, tMs)
        }
        return frame(tNanos, uptimeMs)
    }

    private fun calibrate(q: Quaternion, tMs: Long) {
        when (val s = capture.add(q, tMs)) {
            is CalibrationCapture.Status.Settling -> phaseProgress = 0.0
            is CalibrationCapture.Status.Capturing -> phaseProgress = s.progress
            is CalibrationCapture.Status.Done -> {
                applyCalibration(s.result)
                sink(PipelineEvent.Calibrated(s.result))
                if (learnAfterCalibration) {
                    learnAfterCalibration = false
                    learner.begin()
                    phase = PipelinePhase.LEARNING
                } else {
                    phase = PipelinePhase.RUNNING
                }
                phaseProgress = 0.0
            }
        }
    }

    private fun learn(q: Quaternion, tMs: Long) {
        val n = neutral ?: return beginCalibration(thenLearn = true)
        val omegaDeg = (n.conjugate() * q).toRotationVector() * RAD_TO_DEG
        when (val s = learner.add(omegaDeg, tMs)) {
            is AxisLearner.Status.Prompt -> {
                learnStep = s.step
                learnReturning = s.returning
                phaseProgress = s.progress
            }
            is AxisLearner.Status.Done -> {
                val fallback = ControlBasis.compute(profile.axes.copy(mode = AxisMode.GRAVITY_TILT), upInNeutral, displayRotation)
                val axes = learner.result(s.peaks, fallback.ex, fallback.ey, displayRotation, profile.axes.fullTiltDeg)
                learnStep = null
                learnReturning = false
                phase = PipelinePhase.RUNNING
                phaseProgress = 0.0
                resetSignal()
                sink(PipelineEvent.AxesLearned(axes))
            }
        }
    }

    private fun process(q: Quaternion, tNanos: Long, tMs: Long) {
        val n = neutral ?: return
        val omega = (n.conjugate() * q).toRotationVector() * RAD_TO_DEG
        var ax = omega dot basis.ex
        var ay = omega dot basis.ey
        val az = omega dot basis.ez
        if (profile.axes.invertX) ax = -ax
        if (profile.axes.invertY) ay = -ay

        val prev = lastOmega
        val speed = if (prev != null && tMs > lastOmegaT) {
            (omega - prev).length() / ((tMs - lastOmegaT) / 1000.0)
        } else {
            0.0
        }
        lastOmega = omega
        lastOmegaT = tMs

        var gx = ax
        var gy = ay
        if (profile.filter.spasmGateEnabled) {
            gate.update(ax, ay, tMs.toDouble())
            gx = gate.x
            gy = gate.y
        }
        filter.update(gx, gy, tNanos / 1e9)
        lastFilteredX = filter.x
        lastFilteredY = filter.y
        lastTwist = az

        // A spasm must not swing the aim. The stick may glide to a new pose after one, but the aim
        // ignores the jerk, the glide and the tremor filter catching up after it.
        if (profile.filter.spasmGateEnabled && gate.engaged) {
            aimSpasm = true
            aimSpasmUntilMs = tMs + AIM_MAX_SPASM_SETTLE_MS
        } else if (aimSpasm) {
            val lag = hypot(gx - lastFilteredX, gy - lastFilteredY)
            if (lag < AIM_SPASM_SETTLED_DEG || tMs >= aimSpasmUntilMs) aimSpasm = false
        }
        aim.update(lastFilteredX, lastFilteredY, tNanos / 1e9, suppressed = aimSpasm)

        val stick = mapper.map(lastFilteredX, lastFilteredY)
        lastStick = stick

        if (profile.dwell.enabled) dwell.update(stick, tMs)?.let(::emitDwell)

        if (profile.flick.enabled) {
            val t = tMs.toDouble()
            emitFlick(flickX.update(ax, t), TriggerKind.FLICK_RIGHT, TriggerKind.FLICK_LEFT)
            emitFlick(flickY.update(ay, t), TriggerKind.FLICK_DOWN, TriggerKind.FLICK_UP)
            emitFlick(flickZ.update(az, t), TriggerKind.TWIST_LEFT, TriggerKind.TWIST_RIGHT)
        }

        val residual = if (stick.isNeutral) hypot(ax - lastFilteredX, ay - lastFilteredY) else null
        safety.onMotion(speed, residual, active, tMs)?.let { sink(PipelineEvent.Safety(it)) }
    }

    private fun applyCalibration(result: CalibrationResult) {
        neutral = result.neutral
        upInNeutral = result.neutral.conjugate().rotate(Vec3.Z)
        tremor = result.tremor
        safety.setTremorBaseline(tremor.rmsDeg)
        updateBasis()
        tuneFlicks()
        tuneAim()
        rebuildMapper()
        resetSignal()
    }

    private fun updateBasis() {
        basis = ControlBasis.compute(profile.axes, upInNeutral, displayRotation)
    }

    private fun rebuildMapper() {
        val auto = if (profile.filter.autoTuneFromTremor) {
            min(tremor.rmsDeg * DEADZONE_PER_TREMOR_RMS, MAX_AUTO_DEADZONE_DEG)
        } else {
            0.0
        }
        val ranges = ControlBasis.ranges(profile.axes)
        rangeAverage = ranges.average
        mapper = ResponseMapper(profile.response, ranges, max(profile.response.deadzoneDeg, auto))
        aim.ranges = ranges
    }

    /**
     * Raises the aim's tightening threshold above the tremor left after filtering. Resting tremor
     * speed is measured raw at calibration; the tremor filter passes roughly a tenth of it.
     */
    private fun tuneAim() {
        val auto = profile.filter.autoTuneFromTremor
        val configured = profile.aim.steadyBelowDegPerSec
        aim.steadyBelowDegPerSec = if (auto && configured > 0) {
            max(configured, min(tremor.speedRmsDegPerSec * AIM_STEADY_PER_TREMOR_SPEED, MAX_AUTO_AIM_STEADY_DEG_PER_SEC))
        } else {
            configured
        }
        // A moving average one tremor period long cancels that tremor's fundamental exactly.
        aim.smoothWindowSec = if (auto && tremor.dominantHz > 0 && tremor.rmsDeg >= MIN_TREMOR_FOR_AIM_WINDOW_DEG) {
            (1.0 / tremor.dominantHz).coerceIn(MIN_AIM_WINDOW_SEC, MAX_AIM_WINDOW_SEC)
        } else {
            profile.aim.smoothWindowMs / 1000.0
        }
    }

    private fun tuneFlicks() {
        val auto = profile.filter.autoTuneFromTremor
        for (f in arrayOf(flickX, flickY, flickZ)) {
            f.minAmplitude = if (auto) max(profile.flick.minAmplitudeDeg, tremor.rmsDeg * FLICK_AMP_PER_TREMOR_RMS) else profile.flick.minAmplitudeDeg
            f.minSpeed = if (auto) max(profile.flick.minSpeedDegPerSec, tremor.speedRmsDegPerSec * FLICK_SPEED_PER_TREMOR_RMS) else profile.flick.minSpeedDegPerSec
        }
    }

    private fun applyGateConfig() {
        gate.triggerSpeedDegPerSec = profile.filter.spasmSpeedDegPerSec
        gate.maxHoldMs = profile.filter.spasmMaxHoldMs
        gate.glideMs = profile.filter.spasmGlideMs
    }

    private fun resetSignal() {
        filter.reset()
        gate.reset()
        flickX.reset()
        flickY.reset()
        flickZ.reset()
        aim.reset()
        aimSpasm = false
        lastOmega = null
        lastStick = StickOutput.ZERO
        lastFilteredX = 0.0
        lastFilteredY = 0.0
        lastTwist = 0.0
    }

    private fun emitDwell(e: DwellEvent) {
        val kind = when (e.direction) {
            Direction4.UP -> TriggerKind.DWELL_UP
            Direction4.DOWN -> TriggerKind.DWELL_DOWN
            Direction4.LEFT -> TriggerKind.DWELL_LEFT
            Direction4.RIGHT -> TriggerKind.DWELL_RIGHT
        }
        val phase = if (e is DwellEvent.Fired) TriggerPhase.PRESS else TriggerPhase.RELEASE
        sink(PipelineEvent.Trigger(kind, phase))
    }

    private fun emitFlick(sign: Int, positive: TriggerKind, negative: TriggerKind) {
        if (sign == 0) return
        sink(PipelineEvent.Trigger(if (sign > 0) positive else negative, TriggerPhase.PULSE))
    }

    private fun frame(tNanos: Long, uptimeMs: Long): MotionFrame {
        val running = phase == PipelinePhase.RUNNING
        val stick = if (running) lastStick else StickOutput.ZERO
        return MotionFrame(
            sensorTimeNanos = tNanos,
            uptimeMs = uptimeMs,
            phase = phase,
            phaseProgress = phaseProgress.toFloat(),
            learnStep = learnStep,
            learnReturning = learnReturning,
            stickX = stick.x.toFloat(),
            stickY = stick.y.toFloat(),
            tiltXDeg = if (running) lastFilteredX.toFloat() else 0f,
            tiltYDeg = if (running) lastFilteredY.toFloat() else 0f,
            twistDeg = if (running) lastTwist.toFloat() else 0f,
            deadzoneDeg = mapper.deadzoneDeg.toFloat(),
            rangeDeg = rangeAverage.toFloat(),
            dwellProgress = if (running && profile.dwell.enabled) dwell.progress.toFloat() else 0f,
            dwellDirection = if (running) dwell.direction else null,
            spasmHold = running && gate.holding,
            aimX = aim.x,
            aimY = aim.y,
            precisionAim = aim.precision,
        )
    }

    private companion object {
        /** Auto deadzone = this × measured resting tremor RMS (raw), capped. */
        const val DEADZONE_PER_TREMOR_RMS = 1.5
        const val MAX_AUTO_DEADZONE_DEG = 6.0
        const val FLICK_AMP_PER_TREMOR_RMS = 4.0
        const val FLICK_SPEED_PER_TREMOR_RMS = 3.0
        const val AIM_STEADY_PER_TREMOR_SPEED = 0.1
        const val MAX_AUTO_AIM_STEADY_DEG_PER_SEC = 4.0
        const val MIN_TREMOR_FOR_AIM_WINDOW_DEG = 0.15
        const val MIN_AIM_WINDOW_SEC = 0.1
        const val MAX_AIM_WINDOW_SEC = 0.3
        /** After a spasm, the aim resumes once the tremor filter is this close to the signal… */
        const val AIM_SPASM_SETTLED_DEG = 0.3
        /** …or after this long, whichever comes first. */
        const val AIM_MAX_SPASM_SETTLE_MS = 1000L
    }
}
