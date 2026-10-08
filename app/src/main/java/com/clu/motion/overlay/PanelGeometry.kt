package com.clu.motion.overlay

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where a floating panel sits, as a fraction of the free space around it: 0 = against the
 * left/top margin, 1 = against the right/bottom margin. Unlike pixel offsets this survives
 * rotation, and a panel that grows (expanded, a longer notice) grows away from the edge it
 * hugs, so it never slides off the screen.
 */
data class PanelPlacement(val fx: Float, val fy: Float) {
    init {
        require(fx in 0f..1f && fy in 0f..1f) { "placement out of range: $fx, $fy" }
    }

    companion object {
        /**
         * The eight edge positions offered to people who can't drag, in a clockwise cycle that
         * starts at the top-left. Index-compatible with the old Gravity-based anchors.
         */
        val ANCHORS = listOf(
            PanelPlacement(0f, 0f),
            PanelPlacement(0.5f, 0f),
            PanelPlacement(1f, 0f),
            PanelPlacement(1f, 0.5f),
            PanelPlacement(1f, 1f),
            PanelPlacement(0.5f, 1f),
            PanelPlacement(0f, 1f),
            PanelPlacement(0f, 0.5f),
        )

        fun of(fx: Float, fy: Float) = PanelPlacement(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
    }
}

/** Pixel geometry for panels on a screen. Pure, so it is unit-tested on the JVM. */
object PanelGeometry {

    /** Top-left corner, in screen pixels, of a [w]×[h] panel placed on an [sw]×[sh] screen. */
    fun topLeft(p: PanelPlacement, w: Int, h: Int, sw: Int, sh: Int, margin: Int): IntPoint {
        val freeX = sw - w - 2 * margin
        val freeY = sh - h - 2 * margin
        // A panel larger than the screen is pinned to the top-left corner, never pushed off it.
        val x = if (freeX <= 0) 0 else margin + (p.fx * freeX).roundToInt()
        val y = if (freeY <= 0) 0 else margin + (p.fy * freeY).roundToInt()
        return IntPoint(x, y)
    }

    /** The placement that puts the panel's top-left at ([left], [top]), clamped to the screen. */
    fun placementAt(left: Int, top: Int, w: Int, h: Int, sw: Int, sh: Int, margin: Int): PanelPlacement {
        val freeX = sw - w - 2 * margin
        val freeY = sh - h - 2 * margin
        val fx = if (freeX <= 0) 0.5f else (left - margin).toFloat() / freeX
        val fy = if (freeY <= 0) 0.5f else (top - margin).toFloat() / freeY
        return PanelPlacement.of(fx, fy)
    }

    /** Placement after dragging by ([dx], [dy]) pixels from [start]. */
    fun dragged(start: PanelPlacement, dx: Float, dy: Float, w: Int, h: Int, sw: Int, sh: Int, margin: Int): PanelPlacement {
        val origin = topLeft(start, w, h, sw, sh, margin)
        return placementAt((origin.x + dx).roundToInt(), (origin.y + dy).roundToInt(), w, h, sw, sh, margin)
    }

    /** Next anchor in the cycle after the one nearest to [p]. */
    fun nextAnchor(p: PanelPlacement): PanelPlacement {
        val anchors = PanelPlacement.ANCHORS
        val nearest = anchors.indices.minBy { abs(anchors[it].fx - p.fx) + abs(anchors[it].fy - p.fy) }
        return anchors[(nearest + 1) % anchors.size]
    }

    /** One step ([step] of the free space) in a direction, for accessibility "move" actions. */
    fun stepped(p: PanelPlacement, dirX: Int, dirY: Int, step: Float = 0.25f) =
        PanelPlacement.of(p.fx + dirX * step, p.fy + dirY * step)

    /** True if the two rectangles (left, top, right, bottom) overlap. */
    fun intersects(a: IntRect, b: IntRect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /**
     * Where to move a bar so it stops covering [target]: the same horizontal placement on the other
     * half of the screen. Null if it doesn't cover the target.
     */
    fun awayFrom(bar: PanelPlacement, barRect: IntRect, target: IntRect, sh: Int): PanelPlacement? {
        if (!intersects(barRect, target)) return null
        val targetCentreY = (target.top + target.bottom) / 2
        val fy = if (targetCentreY < sh / 2) 1f else 0f
        if (fy == bar.fy) return null
        return PanelPlacement(bar.fx, fy)
    }
}

data class IntPoint(val x: Int, val y: Int)

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
