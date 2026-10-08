package com.clu.motion.overlay

import android.view.ContextThemeWrapper
import android.view.View
import com.clu.motion.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Measures the floating controls at the target phone's real screen: POCO X7 Pro, 2712×1220 px
 * at 520 dpi = 834×375 dp in landscape. The first device test found the old panel and editor bar
 * covering well over half of that height.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement, so labels have their true width
@Config(sdk = [35], qualifiers = "w834dp-h375dp-land-520dpi")
class OverlaySizeTest {

    private fun widgets() = OverlayWidgets(ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Clu_Overlay))

    private fun screen(): Pair<Int, Int> {
        val dm = RuntimeEnvironment.getApplication().resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun View.measureOn(sw: Int, sh: Int): View {
        measure(View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST))
        layout(0, 0, measuredWidth, measuredHeight)
        return this
    }

    private fun hud() = HudPanel(widgets(), HudActions({}, {}, {}, {}, {}, {}, {}, {}))

    private fun bar() = EditorBar(widgets(), EditorActions({ _, _ -> }, {}, {}, {}, {}, {}, {}, {}), 0.01)

    @Test
    fun theTestRunsAtThePhonesScreenSize() {
        val (sw, sh) = screen()
        assertEquals(2712.0, sw.toDouble(), 3.0)
        assertEquals(1220.0, sh.toDouble(), 3.0)
    }

    @Test
    fun theExpandedPanelCoversAFractionOfALandscapeScreen() {
        val (sw, sh) = screen()
        val panel = hud().apply { status.text = "Strong tremor" }
        val v = panel.root.measureOn(sw, sh)
        assertTrue("height ${v.measuredHeight} of $sh", v.measuredHeight <= sh * 0.30)
        assertTrue("width ${v.measuredWidth} of $sw", v.measuredWidth <= sw * 0.50)
    }

    @Test
    fun aLongNoticeWrapsInsteadOfStretchingThePanel() {
        val (sw, sh) = screen()
        val panel = hud().apply {
            status.text = "Paused: very fast or jerky movement. Hold still and dwell in any direction, or tap Resume, to continue playing."
        }
        val v = panel.root.measureOn(sw, sh)
        assertTrue("height ${v.measuredHeight} of $sh", v.measuredHeight <= sh * 0.33)
        assertTrue("width ${v.measuredWidth} of $sw", v.measuredWidth <= sw * 0.50)
    }

    @Test
    fun duringPlayThePanelIsABubble() {
        val (sw, sh) = screen()
        val panel = hud().apply { show(expanded = false, showStatus = false) }
        val v = panel.root.measureOn(sw, sh)
        val maxBubble = (72 * 3.25).toInt() // 56 dp indicator + padding, at 520 dpi
        assertTrue("bubble ${v.measuredWidth}×${v.measuredHeight}", v.measuredWidth <= maxBubble && v.measuredHeight <= maxBubble)
        // A calibration prompt shows beside the bubble without the buttons.
        panel.show(expanded = false, showStatus = true)
        panel.status.text = "Hold still to set your center…"
        val prompt = panel.root.measureOn(sw, sh)
        assertTrue("prompt height ${prompt.measuredHeight}", prompt.measuredHeight <= maxBubble)
    }

    @Test
    fun moreButtonsStayUnderHalfTheHeight() {
        val (sw, sh) = screen()
        val panel = hud().apply {
            status.text = "Shooter aim"
            profileButton.text = "Shooter aim"
            setMoreOpen(true)
        }
        val v = panel.root.measureOn(sw, sh)
        assertTrue("height ${v.measuredHeight} of $sh", v.measuredHeight <= sh * 0.5)
    }

    @Test
    fun theEditorBarIsOneStripInLandscape() {
        val (sw, sh) = screen()
        val b = bar().apply { status.text = "Selected: button A (on) at 88% across, 80% down." }
        val v = b.root.measureOn(sw - 2 * (8 * 3.25).toInt(), sh)
        assertTrue("height ${v.measuredHeight} of $sh", v.measuredHeight <= sh * 0.30)
        val oneRow = (48 + 2 * 6) * 3.25 + 2 * 17 * 3.25 // buttons + padding + two status lines
        assertTrue("wrapped to more than one row: ${v.measuredHeight}", v.measuredHeight <= oneRow)
    }

    @Test
    fun theMinimisedEditorBarIsTwoButtons() {
        val (sw, sh) = screen()
        val b = bar().apply { setMinimized(true) }
        val v = b.root.measureOn(sw, sh)
        assertTrue("minimised ${v.measuredWidth}×${v.measuredHeight}", v.measuredWidth <= (2 * 56 + 12) * 3.25 && v.measuredHeight <= 64 * 3.25)
        assertTrue(b.minimized)
    }

    @Test
    @Config(qualifiers = "w375dp-h834dp-port-520dpi")
    fun inPortraitTheBarWrapsWithinTheScreenWidth() {
        val (sw, sh) = screen()
        val b = bar().apply { status.text = "Selected: Stick at 18% across, 72% down." }
        val v = b.root.measureOn(sw - 2 * (8 * 3.25).toInt(), sh)
        assertTrue("width ${v.measuredWidth} of $sw", v.measuredWidth <= sw)
        assertTrue("height ${v.measuredHeight} of $sh", v.measuredHeight <= sh * 0.30)
        val hudView = hud().apply { status.text = "Strong tremor" }.root.measureOn(sw, sh)
        assertTrue("panel width ${hudView.measuredWidth} of $sw", hudView.measuredWidth <= sw)
    }
}
