package com.clu.motion.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.widget.Button
import android.widget.TextView
import androidx.annotation.StringRes
import kotlin.math.roundToInt

/** Shared look of the overlay controls: dark, high contrast, ≥ 48 dp targets, a yellow focus ring. */
internal class OverlayWidgets(val ctx: Context) {
    val density = ctx.resources.displayMetrics.density

    fun dp(v: Int) = (v * density).roundToInt()

    fun textButton(@StringRes label: Int, onClick: () -> Unit) = styledButton().apply {
        setText(label)
        setOnClickListener { onClick() }
    }

    fun iconButton(glyph: String, @StringRes description: Int, onClick: () -> Unit) = styledButton().apply {
        text = glyph
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        contentDescription = ctx.getString(description)
        setOnClickListener { onClick() }
    }

    fun statusText(sizeSp: Float) = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        // Visually short; screen readers still read the whole text.
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Color.argb(230, 16, 20, 24))
    }

    fun flowRow() = FlowRow(ctx, dp(SPACING_DP))

    private fun styledButton() = Button(ctx).apply {
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = dp(48)
        minimumWidth = dp(48)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), 0, dp(8), 0)
        stateListAnimator = null
        val normal = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.rgb(0x2A, 0x31, 0x38))
        }
        val focused = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.rgb(0x2A, 0x31, 0x38))
            setStroke(dp(3), Color.rgb(0xFF, 0xD4, 0x00))
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(80, 255, 255, 255)), states, null)
    }

    companion object {
        const val SPACING_DP = 4
    }
}
