package com.clu.motion.profile

import com.clu.motion.core.math.Vec3
import kotlinx.serialization.Serializable

/**
 * Everything that shapes how one user's motion becomes touch input. Profiles are persisted as
 * JSON, so every field has a default: new fields can be added without breaking stored profiles.
 *
 * Pure Kotlin on purpose: the whole signal chain is unit-testable on the JVM.
 */
@Serializable
data class ControlProfile(
    val id: String,
    val name: String,
    val sensor: SensorConfig = SensorConfig(),
    val axes: AxisConfig = AxisConfig(),
    val filter: FilterConfig = FilterConfig(),
    val response: ResponseConfig = ResponseConfig(),
    val joystick: JoystickConfig = JoystickConfig(),
    val aim: AimConfig = AimConfig(),
    val dwell: DwellConfig = DwellConfig(),
    val flick: FlickConfig = FlickConfig(),
    val safety: SafetyConfig = SafetyConfig(),
    val sound: SoundTriggerConfig = SoundTriggerConfig(),
    val buttons: List<VirtualButton> = VirtualButton.defaults(),
    val bindings: List<TriggerBinding> = TriggerBinding.defaults(),
    /** Packages that auto-select this profile when they come to the foreground. */
    val linkedPackages: List<String> = emptyList(),
    val showTouchPoints: Boolean = false,
) {
    /** The aim pad is in use now or can be switched to. */
    val usesAim: Boolean
        get() = joystick.mode == JoystickMode.AIM || bindings.any { it.action == ActionType.SWITCH_MOVE_AIM }
}

@Serializable
data class SensorConfig(
    /**
     * false → TYPE_GAME_ROTATION_VECTOR (gyro + accel). Pitch/roll are gravity-referenced and
     * drift-free, and it is immune to magnetic disturbance from power-wheelchair motors, steel
     * frames and speakers. true → TYPE_ROTATION_VECTOR (adds magnetometer), which also pins yaw;
     * only worth it for yaw-driven (head-turn) control in a magnetically clean environment.
     */
    val useMagnetometer: Boolean = false,
    /** 10 ms = 100 Hz. Above 200 Hz Android 12+ requires HIGH_SAMPLING_RATE_SENSORS. */
    val samplingPeriodUs: Int = 10_000,
)

@Serializable
enum class AxisMode {
    /** Horizontal axes derived from gravity at calibration: "tray/steering-wheel" tilting. */
    GRAVITY_TILT,

    /** Turning about the vertical axis drives X, nodding drives Y (head-mounted pointers). */
    GRAVITY_TURN,

    /** Raw device axes relative to the screen, independent of gravity. */
    DEVICE,

    /** Axes learned from the user's own movements ("Learn my moves"). */
    LEARNED,
}

@Serializable
data class AxisConfig(
    val mode: AxisMode = AxisMode.GRAVITY_TILT,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    /** Tilt (degrees beyond neutral) that produces full stick deflection, for non-learned modes. */
    val fullTiltDeg: Double = 18.0,
    val learned: LearnedAxes? = null,
)

/**
 * Control axes learned from the user's comfortable range of motion. Vectors are unit rotation
 * axes in the neutral device frame, captured while the display had [referenceRotation].
 * Ranges are per direction because many users (e.g. hemiplegia) move asymmetrically.
 */
@Serializable
data class LearnedAxes(
    val ex: Vec3,
    val ey: Vec3,
    val referenceRotation: Int,
    val rangePosX: Double,
    val rangeNegX: Double,
    val rangePosY: Double,
    val rangeNegY: Double,
)

@Serializable
enum class FilterType { ONE_EURO, KALMAN, NONE }

@Serializable
data class FilterConfig(
    val type: FilterType = FilterType.ONE_EURO,
    /** One Euro: cutoff at rest. Lower = steadier but laggier. Tremor is typically 4–12 Hz. */
    val minCutoffHz: Double = 1.0,
    /** One Euro: how fast the cutoff opens with intentional speed (Hz per deg/s). */
    val beta: Double = 0.04,
    /** One Euro: speed-estimate cutoff. Keep ≤ 1 Hz so tremor itself cannot open the filter. */
    val derivativeCutoffHz: Double = 1.0,
    /** Kalman: white-noise acceleration spectral density (deg²/s³). */
    val kalmanProcessNoise: Double = 800.0,
    /** Kalman: measurement noise variance (deg²). */
    val kalmanMeasurementNoise: Double = 0.5,
    val spasmGateEnabled: Boolean = true,
    /** Angular speed that freezes the stick as an involuntary jerk/spasm. */
    val spasmSpeedDegPerSec: Double = 350.0,
    val spasmMaxHoldMs: Long = 400,
    val spasmGlideMs: Long = 180,
    /** Widen deadzone / flick thresholds from the tremor measured during calibration. */
    val autoTuneFromTremor: Boolean = true,
)

@Serializable
enum class CurveType { LINEAR, POWER, SIGMOID }

@Serializable
enum class OutputMode { ANALOG, DIGITAL_8, DIGITAL_4 }

@Serializable
data class ResponseConfig(
    /** Radial deadzone in degrees; output is rescaled so there is no jump at its edge. */
    val deadzoneDeg: Double = 1.5,
    val curve: CurveType = CurveType.POWER,
    /** POWER: out = in^exponent. < 1 boosts micro-movements, > 1 adds fine control near center. */
    val exponent: Double = 0.8,
    val sigmoidSteepness: Double = 8.0,
    val sigmoidMidpoint: Double = 0.35,
    /** Minimum deflection once outside the deadzone, to jump over the game's own stick deadzone. */
    val antiDeadzone: Double = 0.0,
    /** Snap to the nearest 45° direction when within this many degrees (0 = off). */
    val angleSnapDeg: Double = 0.0,
    val outputMode: OutputMode = OutputMode.ANALOG,
    val digitalThreshold: Double = 0.3,
)

@Serializable
enum class JoystickMode {
    /** Absolute virtual thumbstick: touch down at the anchor, drag proportionally. */
    STICK,

    /** Rate control for camera/look pads: tilt sets finger velocity; lifts and re-centres at the pad edge. */
    CAMERA_DRAG,

    /**
     * Gyro-style aiming on a look pad: the finger moves as far as the device turned and stops
     * when it stops (see [AimConfig]). Far more precise than [CAMERA_DRAG] for slow or
     * imprecise movement, because there is no speed to hold steady and nothing to overshoot.
     */
    AIM,

    /** Motion drives triggers only. */
    OFF,
}

@Serializable
data class JoystickConfig(
    val mode: JoystickMode = JoystickMode.STICK,
    /** Anchor as a fraction of the display (current rotation). */
    val centerX: Double = 0.18,
    val centerY: Double = 0.72,
    /** Stick radius (or half-size of the camera/aim pad) as a fraction of min(display width, height). */
    val radiusFraction: Double = 0.11,
    /** Lift the virtual finger after resting in the deadzone this long. */
    val releaseAfterNeutralMs: Long = 250,
    /** Keep the finger down at the anchor while neutral (for games that reset the stick on lift). */
    val holdAtCenter: Boolean = false,
    val cameraSpeedPxPerSec: Double = 1400.0,
    /** Duration of each injected stroke segment; ~one display frame. */
    val segmentMs: Long = 16,
    /** Injected segments allowed in flight; bounds queueing latency to ≈ maxInFlight × segmentMs. */
    val maxInFlight: Int = 2,
)

/**
 * Tuning for [JoystickMode.AIM]. The aim follows the *change* in the filtered tilt (after the
 * spasm gate and tremor filter), not the tilt itself, so the finger moves by as much as the device
 * turned and holds still when it does.
 */
@Serializable
data class AimConfig(
    /**
     * The look pad, separate from the movement stick so a trigger can switch between them. Its
     * centre is a fraction of the display (current rotation). Its half-size is a fraction of
     * min(width, height). At the pad edge the finger lifts and re-grips at the centre.
     */
    val padX: Double = 0.70,
    val padY: Double = 0.45,
    val padRadiusFraction: Double = 0.22,
    /**
     * Finger travel, as a fraction of the screen's short side, for a movement across the whole
     * tilt range (neutral to full tilt). Ranges are per direction, so a small or one-sided range
     * still reaches as far.
     */
    val sensitivity: Double = 0.5,
    /** Sensitivity multiplier while precision aim is on (like a scope); toggled by a trigger. */
    val precisionScale: Double = 0.35,
    /**
     * Motion slower than this is scaled down smoothly, so leftover tremor and slow drift don't
     * creep the aim while deliberate motion passes in full ("tightening"). Raised automatically
     * from the tremor measured at calibration. 0 = off.
     */
    val steadyBelowDegPerSec: Double = 1.0,
    /**
     * Below this speed the aim follows a moving average of the motion instead of each sample
     * ("soft tiered smoothing"): small corrections lose the tremor riding on them, at the cost of
     * half a window of lag; fast turns (above twice this) pass unsmoothed. 0 = off.
     */
    val smoothBelowDegPerSec: Double = 10.0,
    /** Averaging window. Auto-tuned to one period of the tremor measured at calibration. */
    val smoothWindowMs: Long = 200,
    /** Up to this multiplier for fast motion (1 = off): slow moves stay fine, quick ones reach far. */
    val accelerationMax: Double = 1.0,
    val accelerationStartDegPerSec: Double = 15.0,
    val accelerationFullDegPerSec: Double = 60.0,
    /**
     * Holding a tilt beyond this fraction of the range keeps turning, for turns larger than the
     * range of motion allows (0 = off).
     */
    val edgeTurnFrom: Double = 0.8,
    /** Edge-turn speed at full tilt, in whole-range movements per second. */
    val edgeTurnSpeed: Double = 1.5,
    /** Lift the finger after this long without aim motion; it re-grips at the pad centre on the next move. */
    val releaseAfterIdleMs: Long = 1500,
)

@Serializable
data class DwellConfig(
    val enabled: Boolean = true,
    val dwellMs: Long = 1200,
    val enterThreshold: Double = 0.85,
    val exitThreshold: Double = 0.7,
    /** Max angular distance from a cardinal direction; diagonals never dwell-trigger. */
    val toleranceDeg: Double = 30.0,
    /** Brief tremor dips out of the zone shorter than this do not reset the timer. */
    val graceMs: Long = 150,
    val repeat: Boolean = false,
    val repeatMs: Long = 800,
    /** While paused (not after a drop), dwelling in any direction resumes play hands-free. */
    val resumeWhilePaused: Boolean = true,
)

@Serializable
data class FlickConfig(
    val enabled: Boolean = true,
    val minSpeedDegPerSec: Double = 120.0,
    val minAmplitudeDeg: Double = 6.0,
    /** Larger excursions are treated as gross movement or a spasm, not a flick. */
    val maxAmplitudeDeg: Double = 35.0,
    val windowMs: Long = 450,
    /** Out-and-back: the motion must come back to this fraction of its peak within [windowMs]. */
    val returnFraction: Double = 0.45,
    val requireReturn: Boolean = true,
    val refractoryMs: Long = 450,
)

@Serializable
data class SafetyConfig(
    val dropDetection: Boolean = true,
    val freeFallG: Double = 0.35,
    val freeFallMs: Long = 70,
    val impactG: Double = 3.5,
    val erraticPause: Boolean = true,
    /** Sustained (≈1 s) RMS angular speed that counts as erratic motion. */
    val erraticRmsDegPerSec: Double = 220.0,
    val severeSpeedDegPerSec: Double = 600.0,
    val severeCount: Int = 3,
    val severeWindowMs: Long = 5_000,
    /** 0 = off. */
    val restReminderMinutes: Int = 20,
    val enforceBreak: Boolean = false,
    val fatigueWarnings: Boolean = true,
    /** Resting tremor this many times the calibration baseline suggests fatigue. */
    val fatigueRatio: Double = 1.6,
)

@Serializable
data class SoundTriggerConfig(
    val enabled: Boolean = false,
    /** A click must rise this far above the adaptive noise floor. */
    val riseDb: Double = 15.0,
    val minLevelDb: Double = -45.0,
    /** Longer sounds (speech, game audio) are rejected. */
    val maxClickMs: Long = 150,
    val refractoryMs: Long = 350,
)

/** A screen location the user maps to a game button (placed in the overlay layout editor). */
@Serializable
data class VirtualButton(
    val id: Int,
    val label: String,
    val x: Double,
    val y: Double,
    val enabled: Boolean = true,
    val tapMs: Long = 70,
) {
    companion object {
        fun defaults() = listOf(
            VirtualButton(1, "A", 0.88, 0.80),
            VirtualButton(2, "B", 0.78, 0.88),
            VirtualButton(3, "C", 0.94, 0.62),
            VirtualButton(4, "D", 0.80, 0.66),
        )
    }
}

@Serializable
enum class TriggerKind {
    DWELL_UP, DWELL_DOWN, DWELL_LEFT, DWELL_RIGHT,
    FLICK_UP, FLICK_DOWN, FLICK_LEFT, FLICK_RIGHT,
    TWIST_LEFT, TWIST_RIGHT,
    KEY,
    SOUND_CLICK,
}

@Serializable
enum class ActionType {
    NONE,
    TAP_BUTTON,
    /** Press while the trigger is held (dwell zone, key down); a flick or click becomes a tap. */
    HOLD_BUTTON,
    /** Latch: first trigger presses, next one releases. */
    TOGGLE_BUTTON,
    RECALIBRATE,
    TOGGLE_PAUSE,
    NEXT_PROFILE,
    BACK,
    HOME,
    RECENTS,

    /** Switches tilt between the movement stick and gyro aim ([JoystickMode.AIM]). */
    SWITCH_MOVE_AIM,

    /** Turns precision aim (reduced aim sensitivity) on or off. */
    TOGGLE_PRECISION,
}

@Serializable
data class TriggerBinding(
    val trigger: TriggerKind,
    val action: ActionType,
    val buttonId: Int = 0,
    /** Only for [TriggerKind.KEY]: Android KeyEvent key code. */
    val keyCode: Int = 0,
) {
    fun matches(kind: TriggerKind, code: Int) = trigger == kind && (kind != TriggerKind.KEY || keyCode == code)

    companion object {
        // android.view.KeyEvent codes (kept literal so this model stays free of Android types).
        const val KEYCODE_ENTER = 66
        const val KEYCODE_SPACE = 62
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_HEADSETHOOK = 79

        fun defaults() = listOf(
            TriggerBinding(TriggerKind.DWELL_UP, ActionType.HOLD_BUTTON, buttonId = 3),
            TriggerBinding(TriggerKind.TWIST_RIGHT, ActionType.TAP_BUTTON, buttonId = 1),
            TriggerBinding(TriggerKind.TWIST_LEFT, ActionType.TAP_BUTTON, buttonId = 2),
            TriggerBinding(TriggerKind.SOUND_CLICK, ActionType.TAP_BUTTON, buttonId = 1),
            // Switch interfaces (USB/Bluetooth) usually emit Space/Enter.
            TriggerBinding(TriggerKind.KEY, ActionType.HOLD_BUTTON, buttonId = 1, keyCode = KEYCODE_SPACE),
            TriggerBinding(TriggerKind.KEY, ActionType.HOLD_BUTTON, buttonId = 2, keyCode = KEYCODE_ENTER),
            TriggerBinding(TriggerKind.KEY, ActionType.HOLD_BUTTON, buttonId = 1, keyCode = KEYCODE_VOLUME_UP),
            TriggerBinding(TriggerKind.KEY, ActionType.HOLD_BUTTON, buttonId = 2, keyCode = KEYCODE_VOLUME_DOWN),
            TriggerBinding(TriggerKind.KEY, ActionType.TAP_BUTTON, buttonId = 1, keyCode = KEYCODE_HEADSETHOOK),
        )
    }
}
