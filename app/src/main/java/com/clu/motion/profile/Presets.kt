package com.clu.motion.profile

/**
 * Starting points, not prescriptions: every user's motion is different, so each preset is meant
 * to be tuned (and ideally followed by "Learn my moves").
 */
object Presets {
    const val HANDHELD = "preset.handheld"
    const val TREMOR = "preset.tremor"
    const val LIMITED_RANGE = "preset.limited_range"
    const val HEAD = "preset.head"
    const val WHEELCHAIR = "preset.wheelchair"

    fun all(): List<ControlProfile> = listOf(handheld(), tremor(), limitedRange(), head(), wheelchair())

    /** Phone held in the hands; tray/steering-wheel tilting. */
    fun handheld() = ControlProfile(id = HANDHELD, name = "Handheld tilt")

    /** Strong or essential tremor: heavy rest smoothing, spasm gate, wide deadzone, direction snapping. */
    fun tremor() = ControlProfile(
        id = TREMOR,
        name = "Strong tremor",
        axes = AxisConfig(fullTiltDeg = 22.0),
        filter = FilterConfig(minCutoffHz = 0.5, beta = 0.025, derivativeCutoffHz = 0.7, spasmSpeedDegPerSec = 250.0),
        response = ResponseConfig(deadzoneDeg = 3.0, curve = CurveType.LINEAR, angleSnapDeg = 12.0),
        dwell = DwellConfig(dwellMs = 1500, graceMs = 250),
        flick = FlickConfig(minAmplitudeDeg = 10.0, minSpeedDegPerSec = 160.0),
    )

    /** Muscle weakness / small range of motion: tiny tilts reach full deflection. */
    fun limitedRange() = ControlProfile(
        id = LIMITED_RANGE,
        name = "Small movements",
        axes = AxisConfig(fullTiltDeg = 5.0),
        filter = FilterConfig(minCutoffHz = 1.2, beta = 0.08),
        response = ResponseConfig(deadzoneDeg = 0.8, curve = CurveType.POWER, exponent = 0.6, antiDeadzone = 0.15),
        dwell = DwellConfig(dwellMs = 1000, enterThreshold = 0.8),
        flick = FlickConfig(enabled = false),
        safety = SafetyConfig(restReminderMinutes = 15),
    )

    /** Head-mounted or lying down: turn = X, nod = Y. Run "Learn my moves" for best results. */
    fun head() = ControlProfile(
        id = HEAD,
        name = "Head / lying down",
        axes = AxisConfig(mode = AxisMode.GRAVITY_TURN, fullTiltDeg = 15.0),
        filter = FilterConfig(minCutoffHz = 0.8, beta = 0.03),
        response = ResponseConfig(deadzoneDeg = 2.0, curve = CurveType.SIGMOID, sigmoidSteepness = 7.0, sigmoidMidpoint = 0.4),
        flick = FlickConfig(minAmplitudeDeg = 8.0),
    )

    /** Chair-mounted use: vibration from driving, bumps that must not register as drops. */
    fun wheelchair() = ControlProfile(
        id = WHEELCHAIR,
        name = "Wheelchair mount",
        filter = FilterConfig(minCutoffHz = 0.7, beta = 0.03, spasmSpeedDegPerSec = 300.0),
        response = ResponseConfig(deadzoneDeg = 2.5),
        safety = SafetyConfig(impactG = 5.0, erraticRmsDegPerSec = 260.0),
    )
}
