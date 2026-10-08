package com.clu.motion.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import kotlin.math.max

/**
 * Lays its children out in a row and wraps to a new row when the width runs out, so a bar of
 * buttons is one strip in landscape and a few short rows in portrait, on any screen size.
 * Children keep their own measured size (buttons are ≥ 48 dp). Mirrors for right-to-left
 * languages. Rows are centred vertically.
 */
@SuppressLint("ViewConstructor") // Built in code only, never inflated from XML.
class FlowRow(context: Context, private val spacing: Int) : ViewGroup(context) {

    /** Children wider than this are clamped (e.g. a long status text). 0 = available width. */
    var maxChildWidth = 0

    private val rowOf = ArrayList<Int>()
    private val rowHeights = ArrayList<Int>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val available = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val childLimit = if (maxChildWidth > 0) minOf(maxChildWidth, available) else available
        val childWidthSpec = if (childLimit == Int.MAX_VALUE) {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        } else {
            MeasureSpec.makeMeasureSpec(childLimit, MeasureSpec.AT_MOST)
        }
        val childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        rowOf.clear()
        rowHeights.clear()
        var rowWidth = 0
        var rowHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) {
                rowOf += -1
                continue
            }
            child.measure(childWidthSpec, childHeightSpec)
            val cw = child.measuredWidth
            val needed = if (rowWidth == 0) cw else rowWidth + spacing + cw
            if (rowWidth > 0 && needed > available) {
                rowHeights += rowHeight
                widest = max(widest, rowWidth)
                rowWidth = cw
                rowHeight = child.measuredHeight
            } else {
                rowWidth = needed
                rowHeight = max(rowHeight, child.measuredHeight)
            }
            rowOf += rowHeights.size
        }
        if (rowWidth > 0) {
            rowHeights += rowHeight
            widest = max(widest, rowWidth)
        }
        val contentHeight = rowHeights.sum() + spacing * (rowHeights.size - 1).coerceAtLeast(0)
        val width = resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec)
        val height = resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val innerRight = r - l - paddingRight
        var row = -1
        var x = 0
        var y = paddingTop
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val childRow = rowOf.getOrElse(i) { -1 }
            if (child.isGone || childRow < 0) continue
            if (childRow != row) {
                if (row >= 0) y += rowHeights[row] + spacing
                row = childRow
                x = 0
            }
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            val top = y + (rowHeights[row] - ch) / 2
            val left = if (rtl) innerRight - x - cw else paddingLeft + x
            child.layout(left, top, left + cw, top + ch)
            x += cw + spacing
        }
    }
}
