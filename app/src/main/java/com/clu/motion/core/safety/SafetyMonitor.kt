package com.clu.motion.core.safety

import com.clu.motion.profile.SafetyConfig
import kotlin.math.exp
import kotlin.math.sqrt

enum class PauseReason {
    USER,
    ERRATIC_MOTION,
    DROP_DETECTED,
    REST_BREAK,
    SCREEN_OFF,
    LAYOUT_EDIT,

    /** An internal error was caught; play pauses instead of the service crashing. */
    ERROR,
    ;

    /** A dropped device must be picked up and resumed explicitly, never by motion alone. */
    val allowsMotionResume get() = this == USER || this == ERRATIC_MOTION || this == REST_BREAK
}

sealed interface SafetyEvent {
    data class PauseRequest(val reason: PauseReason) : SafetyEvent
    data class RestReminder(val activeMinutes: Int) : SafetyEvent
    data object FatigueWarning : SafetyEvent
}

/**
 * Watches for situations where injecting touches would be unsafe or unwanted, and for fatigue:
 *  - drop: free fall (|a| ≪ 1 g, sustained) or a hard impact (|a| ≫ 1 g);
 *  - erratic motion: sustained high RMS angular speed, or a cluster of severe jerks;
 *  - rest reminders after a configurable amount of active play;
 *  - fatigue: resting tremor drifting well above the calibration baseline.
 *
 * Timestamps are sensor milliseconds; all methods are called on the sensor thread.
 */
class SafetyMonitor(private val config: SafetyConfig) {
    private var tremorBaselineDeg = 0.0

    // Erratic motion
    private var meanSquareSpeed = 0.0
    private var lastMotionT = -1L
    private var severeArmed = true
    private val severeTimes = ArrayDeque<Long>()

    // Drop
    private var freeFallStart = -1L

    // Rest
    private var activeMs = 0L
    private var nextReminderMs = reminderIntervalMs()
    private var lastActiveT = -1L

    // Fatigue
    private var windowStart = -1L
    private var windowSumSq = 0.0
    private var windowCount = 0
    private var elevatedWindows = 0
    private var lastFatigueWarning = Long.MIN_VALUE / 2

    fun setTremorBaseline(rmsDeg: Double) {
        tremorBaselineDeg = rmsDeg
        elevatedWindows = 0
    }

    /**
     * @param angularSpeed deg/s of the raw (unfiltered) rotation.
     * @param restingResidualDeg |raw − filtered| when the stick is at rest, else null.
     */
    fun onMotion(angularSpeed: Double, restingResidualDeg: Double?, active: Boolean, tMs: Long): SafetyEvent? {
        val dt = if (lastMotionT < 0) 0L else (tMs - lastMotionT).coerceIn(0, 100)
        lastMotionT = tMs
        if (dt > 0) {
            val a = 1 - exp(-dt / ERRATIC_TAU_MS)
            meanSquareSpeed += a * (angularSpeed * angularSpeed - meanSquareSpeed)
        }
        if (!active) {
            lastActiveT = -1
            return null
        }

        if (config.erraticPause) {
            if (sqrt(meanSquareSpeed) > config.erraticRmsDegPerSec) {
                clearErratic()
                return SafetyEvent.PauseRequest(PauseReason.ERRATIC_MOTION)
            }
            if (angularSpeed > config.severeSpeedDegPerSec) {
                if (severeArmed) {
                    severeArmed = false
                    severeTimes.addLast(tMs)
                }
            } else if (angularSpeed < config.severeSpeedDegPerSec * 0.5) {
                severeArmed = true
            }
            while (severeTimes.isNotEmpty() && tMs - severeTimes.first() > config.severeWindowMs) {
                severeTimes.removeFirst()
            }
            if (severeTimes.size >= config.severeCount) {
                clearErratic()
                return SafetyEvent.PauseRequest(PauseReason.ERRATIC_MOTION)
            }
        }

        restTick(tMs)?.let { return it }
        return restingResidualDeg?.let { fatigueTick(it, tMs) }
    }

    /** Accelerometer sample in m/s² (including gravity). */
    fun onAcceleration(ax: Double, ay: Double, az: Double, tMs: Long): SafetyEvent? {
        if (!config.dropDetection) return null
        val g = sqrt(ax * ax + ay * ay + az * az) / GRAVITY
        if (g > config.impactG) {
            freeFallStart = -1
            return SafetyEvent.PauseRequest(PauseReason.DROP_DETECTED)
        }
        if (g < config.freeFallG) {
            if (freeFallStart < 0) freeFallStart = tMs
            if (tMs - freeFallStart >= config.freeFallMs) {
                freeFallStart = -1
                return SafetyEvent.PauseRequest(PauseReason.DROP_DETECTED)
            }
        } else {
            freeFallStart = -1
        }
        return null
    }

    fun resetSession() {
        clearErratic()
        activeMs = 0
        nextReminderMs = reminderIntervalMs()
        lastActiveT = -1
        windowStart = -1
        elevatedWindows = 0
    }

    private fun clearErratic() {
        meanSquareSpeed = 0.0
        severeTimes.clear()
        severeArmed = true
    }

    private fun restTick(tMs: Long): SafetyEvent? {
        if (lastActiveT >= 0) activeMs += (tMs - lastActiveT).coerceIn(0, 100)
        lastActiveT = tMs
        if (config.restReminderMinutes <= 0 || activeMs < nextReminderMs) return null
        val minutes = (activeMs / 60_000).toInt()
        nextReminderMs += reminderIntervalMs()
        return if (config.enforceBreak) {
            SafetyEvent.PauseRequest(PauseReason.REST_BREAK)
        } else {
            SafetyEvent.RestReminder(minutes)
        }
    }

    private fun fatigueTick(residualDeg: Double, tMs: Long): SafetyEvent? {
        if (!config.fatigueWarnings || tremorBaselineDeg <= 0) return null
        if (windowStart < 0) windowStart = tMs
        windowSumSq += residualDeg * residualDeg
        windowCount++
        if (tMs - windowStart < FATIGUE_WINDOW_MS) return null

        val rms = sqrt(windowSumSq / windowCount)
        windowStart = tMs
        windowSumSq = 0.0
        windowCount = 0
        val elevated = rms > tremorBaselineDeg * config.fatigueRatio && rms > MIN_FATIGUE_RMS_DEG
        elevatedWindows = if (elevated) elevatedWindows + 1 else 0
        if (elevatedWindows >= FATIGUE_WINDOWS && tMs - lastFatigueWarning > FATIGUE_COOLDOWN_MS) {
            elevatedWindows = 0
            lastFatigueWarning = tMs
            return SafetyEvent.FatigueWarning
        }
        return null
    }

    private fun reminderIntervalMs() = config.restReminderMinutes * 60_000L

    private companion object {
        const val GRAVITY = 9.80665
        const val ERRATIC_TAU_MS = 1000.0
        const val FATIGUE_WINDOW_MS = 5_000L
        const val FATIGUE_WINDOWS = 3
        const val FATIGUE_COOLDOWN_MS = 10 * 60_000L
        const val MIN_FATIGUE_RMS_DEG = 0.3
    }
}
