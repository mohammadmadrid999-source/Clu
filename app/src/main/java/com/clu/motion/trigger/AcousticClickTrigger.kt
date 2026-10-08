package com.clu.motion.trigger

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.clu.motion.core.audio.ClickOnsetDetector
import com.clu.motion.profile.SoundTriggerConfig

/**
 * Optional hands-free "button": a tongue click / lip pop / tap detected from the microphone.
 *
 * Only levels are computed and discarded frame by frame; no audio is stored or sent anywhere.
 * Game audio from the loudspeaker can trigger it, so headphones are recommended. While the app
 * is in the background, Android only delivers microphone audio to a foreground service of type
 * "microphone" that was started while the app was visible (see MotionSessionService).
 */
class AcousticClickTrigger(private val context: Context, private val onClick: () -> Unit) {

    /** Identity of the current capture run; a stale thread can never stop a newer one. */
    @Volatile
    private var activeRun: Any? = null
    private var thread: Thread? = null
    private var config: SoundTriggerConfig? = null

    val isRunning get() = activeRun != null

    /** @return false if the RECORD_AUDIO permission is missing. Main thread. */
    fun start(config: SoundTriggerConfig): Boolean {
        if (!hasPermission()) return false
        if (isRunning && config == this.config) return true
        stop()
        this.config = config
        val run = Any()
        activeRun = run
        thread = Thread({ loop(config, run) }, "clu-sound").apply { start() }
        return true
    }

    /** Main thread. Returns within about one audio frame. */
    fun stop() {
        activeRun = null
        thread?.join(STOP_TIMEOUT_MS)
        thread = null
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked right here and in start().
    private fun loop(config: SoundTriggerConfig, run: Any) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0 || !hasPermission()) {
            finish(run)
            return
        }
        val record = try {
            // VOICE_RECOGNITION: minimal platform processing (no AGC on most devices), so transients survive.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Microphone permission revoked", e)
            finish(run)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            finish(run)
            return
        }

        val detector = ClickOnsetDetector(config)
        val buffer = ShortArray(FRAME_SAMPLES)
        try {
            record.startRecording()
            while (activeRun === run) {
                val n = record.read(buffer, 0, FRAME_SAMPLES)
                if (n < 0) break
                if (n == 0) continue
                if (detector.process(ClickOnsetDetector.levelDbfs(buffer, n), SystemClock.uptimeMillis())) onClick()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Audio capture failed", e)
        } finally {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
            finish(run)
        }
    }

    private fun finish(run: Any) {
        if (activeRun === run) activeRun = null
    }

    private companion object {
        const val TAG = "AcousticClickTrigger"
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 160 // 10 ms
        const val STOP_TIMEOUT_MS = 200L
    }
}
