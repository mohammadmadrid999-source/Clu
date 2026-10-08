package com.clu.motion.core.audio

import com.clu.motion.profile.SoundTriggerConfig
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Detects short percussive sounds (tongue click, lip pop, a tap on the wheelchair tray) as a
 * discrete trigger, from per-frame levels (e.g. 10 ms frames, dBFS).
 *
 * A click must jump [SoundTriggerConfig.riseDb] above an adaptive noise floor and fall back
 * within [SoundTriggerConfig.maxClickMs]; speech and game audio last longer and are rejected.
 */
class ClickOnsetDetector(private val config: SoundTriggerConfig) {
    private enum class State { IDLE, ACTIVE, SUSTAINED }

    private var state = State.IDLE
    private var floorDb = Double.NaN
    private var onsetT = 0L
    private var lastT = -1L
    private var refractoryUntil = 0L

    /** @return true when a click completes on this frame. */
    fun process(levelDb: Double, tMs: Long): Boolean {
        val dt = if (lastT < 0) 0L else tMs - lastT
        lastT = tMs
        if (floorDb.isNaN()) floorDb = levelDb

        val above = levelDb >= floorDb + config.riseDb && levelDb >= config.minLevelDb
        val fallen = levelDb < floorDb + config.riseDb - HYSTERESIS_DB

        when (state) {
            State.IDLE -> {
                if (above && tMs >= refractoryUntil) {
                    state = State.ACTIVE
                    onsetT = tMs
                } else {
                    trackFloor(levelDb, dt)
                }
            }

            State.ACTIVE -> when {
                tMs - onsetT > config.maxClickMs -> state = State.SUSTAINED
                fallen -> {
                    state = State.IDLE
                    refractoryUntil = tMs + config.refractoryMs
                    return true
                }
            }

            State.SUSTAINED -> if (fallen) state = State.IDLE
        }
        return false
    }

    private fun trackFloor(levelDb: Double, dtMs: Long) {
        // Fast to fall, slow to rise: a floor that follows the quiet parts of ambient noise.
        val tau = if (levelDb < floorDb) FLOOR_FALL_TAU_MS else FLOOR_RISE_TAU_MS
        floorDb += (1 - exp(-dtMs / tau)) * (levelDb - floorDb)
    }

    companion object {
        private const val HYSTERESIS_DB = 6.0
        private const val FLOOR_FALL_TAU_MS = 200.0
        private const val FLOOR_RISE_TAU_MS = 2_000.0

        /** RMS level of 16-bit PCM in dBFS (−120 for digital silence). */
        fun levelDbfs(pcm: ShortArray, count: Int): Double {
            if (count <= 0) return SILENCE_DB
            var sum = 0.0
            for (i in 0 until count) {
                val s = pcm[i].toDouble()
                sum += s * s
            }
            val rms = sqrt(sum / count) / 32768.0
            return if (rms <= 1e-6) SILENCE_DB else 20 * log10(rms)
        }

        const val SILENCE_DB = -120.0
    }
}
