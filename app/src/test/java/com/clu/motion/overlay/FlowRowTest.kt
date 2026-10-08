package com.clu.motion.overlay

import android.view.View
import android.widget.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlowRowTest {

    private fun row(count: Int, childWidth: Int = 100, childHeight: Int = 50) = FlowRow(RuntimeEnvironment.getApplication(), 10).apply {
        repeat(count) {
            addView(Button(context).apply {
                minWidth = childWidth
                minimumWidth = childWidth
                minHeight = childHeight
                minimumHeight = childHeight
                maxWidth = childWidth
                setPadding(0, 0, 0, 0)
            })
        }
    }

    private fun FlowRow.measureAt(width: Int) = apply {
        measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    @Test
    fun oneRowWhenThereIsRoom() {
        val r = row(4).measureAt(1000)
        assertEquals(4 * 100 + 3 * 10, r.measuredWidth)
        assertEquals(50, r.measuredHeight)
    }

    @Test
    fun wrapsWhenNarrow() {
        val r = row(4).measureAt(250) // two per row
        assertEquals(210, r.measuredWidth)
        assertEquals(50 + 10 + 50, r.measuredHeight)
        assertEquals(0, r.getChildAt(2).left)
        assertEquals(60, r.getChildAt(2).top)
    }

    @Test
    fun hiddenChildrenTakeNoSpace() {
        val r = row(3)
        r.getChildAt(1).visibility = View.GONE
        r.measureAt(1000)
        assertEquals(210, r.measuredWidth)
        assertEquals(110, r.getChildAt(2).left)
    }

    @Test
    fun mirrorsForRightToLeft() {
        val r = row(2)
        r.layoutDirection = View.LAYOUT_DIRECTION_RTL
        r.measureAt(1000)
        assertTrue(r.getChildAt(0).left > r.getChildAt(1).left)
        assertEquals(r.measuredWidth, r.getChildAt(0).right)
    }
}
