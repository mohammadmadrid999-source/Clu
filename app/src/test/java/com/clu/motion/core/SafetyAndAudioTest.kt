package com.clu.motion.core

import com.clu.motion.core.audio.ClickOnsetDetector
import com.clu.motion.core.safety.PauseReason
import com.clu.motion.core.safety.SafetyEvent
import com.clu.motion.core.safety.SafetyMonitor
import com.clu.motion.profile.SafetyConfig
import com.clu.motion.profile.SoundTriggerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SafetyMonitorTest {
    private val g = 9.80665

    @Test
    fun freeFallPausesAsDrop() {
        val m = SafetyMonitor(SafetyConfig())
        var event: SafetyEvent? = null
        for (t in 0L..200L step 10) event = event ?: m.onAcceleration(0.0, 0.1 * g, 0.05 * g, t)
        assertEquals(SafetyEvent.PauseRequest(PauseReason.DROP_DETECTED), event)
    }

    @Test
    fun impactPausesAsDropButNormalHandlingDoesNot() {
        val m = SafetyMonitor(SafetyConfig())
        for (t in 0L..1000L step 10) assertNull(m.onAcceleration(0.0, 0.7 * g, 0.7 * g, t))
        assertEquals(SafetyEvent.PauseRequest(PauseReason.DROP_DETECTED), m.onAcceleration(4.0 * g, 0.0, 0.0, 1010))
    }

    @Test
    fun sustainedErraticMotionPauses() {
        val m = SafetyMonitor(SafetyConfig(erraticRmsDegPerSec = 220.0))
        var event: SafetyEvent? = null
        var t = 0L
        while (event == null && t < 5000) {
            event = m.onMotion(300.0 * kotlin.math.abs(sin(2 * PI * 3 * t / 1000.0)) + 150, null, active = true, tMs = t)
            t += 10
        }
        assertEquals(SafetyEvent.PauseRequest(PauseReason.ERRATIC_MOTION), event)
    }

    @Test
    fun clusterOfSevereJerksPauses() {
        val m = SafetyMonitor(SafetyConfig(severeCount = 3, severeWindowMs = 5000, erraticRmsDegPerSec = 10_000.0))
        var event: SafetyEvent? = null
        for (t in 0L until 4000L step 10) {
            val speed = if (t % 1000 in 0..20) 900.0 else 10.0
            event = event ?: m.onMotion(speed, null, true, t)
        }
        assertEquals(SafetyEvent.PauseRequest(PauseReason.ERRATIC_MOTION), event)
    }

    @Test
    fun nothingFiresWhilePaused() {
        val m = SafetyMonitor(SafetyConfig())
        for (t in 0L until 3000L step 10) assertNull(m.onMotion(900.0, null, active = false, tMs = t))
    }

    @Test
    fun restReminderAfterActivePlay() {
        val m = SafetyMonitor(SafetyConfig(restReminderMinutes = 1))
        var event: SafetyEvent? = null
        var t = 0L
        while (event == null && t < 70_000) {
            event = m.onMotion(5.0, null, true, t)
            t += 10
        }
        assertEquals(SafetyEvent.RestReminder(1), event)
        assertTrue(t in 59_000..61_000)
    }

    @Test
    fun fatigueWarningWhenRestingTremorGrows() {
        val m = SafetyMonitor(SafetyConfig())
        m.setTremorBaseline(0.4)
        var event: SafetyEvent? = null
        for (t in 0L until 20_000L step 10) event = event ?: m.onMotion(5.0, 1.0, true, t)
        assertEquals(SafetyEvent.FatigueWarning, event)
    }
}

class ClickOnsetDetectorTest {
    private fun feed(d: ClickOnsetDetector, level: (Long) -> Double, untilMs: Long): Int {
        var clicks = 0
        for (t in 0L..untilMs step 10) if (d.process(level(t), t)) clicks++
        return clicks
    }

    @Test
    fun shortClickAboveFloorFires() {
        val clicks = feed(ClickOnsetDetector(SoundTriggerConfig()), { t -> if (t in 1000..1040) -20.0 else -60.0 }, 2000)
        assertEquals(1, clicks)
    }

    @Test
    fun sustainedSoundIsRejected() {
        assertEquals(0, feed(ClickOnsetDetector(SoundTriggerConfig()), { t -> if (t in 1000..1600) -20.0 else -60.0 }, 2500))
    }

    @Test
    fun quietSoundsBelowAbsoluteMinimumAreIgnored() {
        assertEquals(0, feed(ClickOnsetDetector(SoundTriggerConfig()), { t -> if (t in 1000..1040) -50.0 else -80.0 }, 2000))
    }

    @Test
    fun levelOfFullScaleSineIsAboutMinus3Dbfs() {
        val pcm = ShortArray(1600) { (32767 * sin(2 * PI * 440 * it / 16000.0)).toInt().toShort() }
        assertEquals(-3.0, ClickOnsetDetector.levelDbfs(pcm, pcm.size), 0.1)
        assertFalse(ClickOnsetDetector.levelDbfs(ShortArray(10), 10) > -100)
    }
}
