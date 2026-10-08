package com.clu.motion.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import com.clu.motion.R
import com.clu.motion.core.MotionFrame
import com.clu.motion.core.PipelinePhase
import com.clu.motion.core.gesture.Direction4
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Spirit level" HUD: the outer ring is full deflection, the grey disc the deadzone, the hollow
 * ring the filtered tilt and the yellow dot the stick actually being injected. A cyan arc fills
 * while a dwell builds up. Colours are chosen to stay distinguishable with common colour-vision
 * deficiencies, and shapes (not colour alone) carry the meaning.
 */
class TiltIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var frame = MotionFrame.IDLE
    private var paused = false
    private var lastDescriptionAt = 0L

    private val density = resources.displayMetrics.density
    private val ringPaint = stroke(Color.argb(220, 255, 255, 255), 2f)
    private val crossPaint = stroke(Color.argb(90, 255, 255, 255), 1f)
    private val deadzonePaint = fill(Color.argb(70, 200, 200, 200))
    private val tiltPaint = stroke(Color.WHITE, 2f)
    private val stickPaint = fill(Color.rgb(0xFF, 0xD4, 0x00))
    private val dwellPaint = stroke(Color.rgb(0x00, 0xC8, 0xFF), 5f).apply { strokeCap = Paint.Cap.ROUND }
    private val pausedPaint = fill(Color.argb(160, 30, 30, 30))
    private val pauseBarPaint = fill(Color.WHITE)
    private val arcRect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.hud_indicator_description)
    }

    /** Called at the HUD's render rate (≤ 30 Hz), never per sensor sample. */
    fun render(frame: MotionFrame, paused: Boolean) {
        if (frame == this.frame && paused == this.paused) return
        this.frame = frame
        this.paused = paused
        invalidate()
        updateStateDescription()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 6 * density

        canvas.drawCircle(cx, cy, r, ringPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, crossPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, crossPaint)

        val range = frame.rangeDeg.coerceAtLeast(0.1f)
        val dz = (frame.deadzoneDeg / range).coerceIn(0f, 1f) * r
        canvas.drawCircle(cx, cy, dz, deadzonePaint)

        when (frame.phase) {
            PipelinePhase.CALIBRATING, PipelinePhase.LEARNING -> {
                arcRect.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(arcRect, -90f, 360f * frame.phaseProgress, false, dwellPaint)
            }
            else -> {
                var tx = frame.tiltXDeg / range
                var ty = frame.tiltYDeg / range
                val tl = hypot(tx, ty)
                if (tl > 1f) {
                    tx /= tl
                    ty /= tl
                }
                canvas.drawCircle(cx + tx * r, cy + ty * r, 7 * density, tiltPaint)
                canvas.drawCircle(cx + frame.stickX * r, cy + frame.stickY * r, 5 * density, stickPaint)

                val dwellDirection = frame.dwellDirection
                if (frame.dwellProgress > 0f && dwellDirection != null) {
                    val center = when (dwellDirection) {
                        Direction4.RIGHT -> 0f
                        Direction4.DOWN -> 90f
                        Direction4.LEFT -> 180f
                        Direction4.UP -> 270f
                    }
                    val sweep = 90f * frame.dwellProgress
                    arcRect.set(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(arcRect, center - sweep / 2, sweep, false, dwellPaint)
                }
            }
        }

        if (paused) {
            canvas.drawCircle(cx, cy, r, pausedPaint)
            val barW = r * 0.18f
            val barH = r * 0.7f
            canvas.drawRect(cx - barW * 2, cy - barH / 2, cx - barW, cy + barH / 2, pauseBarPaint)
            canvas.drawRect(cx + barW, cy - barH / 2, cx + barW * 2, cy + barH / 2, pauseBarPaint)
        }
    }

    /** Spoken state for screen-reader users, refreshed at most once a second. */
    private fun updateStateDescription() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastDescriptionAt < 1000) return
        lastDescriptionAt = now
        stateDescription = when {
            paused -> context.getString(R.string.hud_state_paused)
            frame.phase == PipelinePhase.CALIBRATING -> context.getString(R.string.hud_state_calibrating)
            frame.isNeutral -> context.getString(R.string.hud_state_centered)
            else -> context.getString(
                R.string.hud_state_tilt,
                (frame.stickX * 100).roundToInt(),
                (-frame.stickY * 100).roundToInt(),
            )
        }
    }

    private fun stroke(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * density
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
}
