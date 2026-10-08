package com.clu.motion.overlay

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.clu.motion.R

internal class HudActions(
    val togglePause: () -> Unit,
    val recenter: () -> Unit,
    val layout: () -> Unit,
    val nextProfile: () -> Unit,
    val learn: () -> Unit,
    val movePanel: () -> Unit,
    val stop: () -> Unit,
    val toggleExpanded: () -> Unit,
)

/**
 * The floating panel's views. Kept compact for landscape games:
 *
 *   [tilt indicator] [status, at most 2 lines            ]
 *                    [Pause] [Recenter] [Layout] [More]
 *                    [Profile] [Learn moves] [Move panel] [Stop] [Hide]   ← only after "More"
 *
 * Collapsed, only the indicator remains (a ~68 dp bubble), plus the status while there is
 * something to say. The indicator is also the drag handle: drag it to move the panel, tap it to
 * expand or collapse. Rows wrap on narrow (portrait) screens.
 */
internal class HudPanel(private val w: OverlayWidgets, actions: HudActions) {
    private val ctx = w.ctx

    val indicator = TiltIndicatorView(ctx).apply {
        setOnClickListener { actions.toggleExpanded() }
        ViewCompat.replaceAccessibilityAction(
            this,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            ctx.getString(R.string.hud_toggle_controls),
            null,
        )
    }

    val status = w.statusText(13f, lines = 4).apply {
        maxWidth = w.dp(STATUS_MAX_WIDTH_DP)
        setPadding(w.dp(2), 0, w.dp(2), w.dp(2))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    val pauseButton: Button = w.textButton(R.string.hud_start) { actions.togglePause() }
    val profileButton: Button = w.textButton(R.string.hud_profile) { actions.nextProfile() }.apply {
        maxWidth = w.dp(PROFILE_MAX_WIDTH_DP)
    }
    private val moreButton: Button = w.textButton(R.string.hud_more) { setMoreOpen(!moreOpen) }

    private val primary = w.flowRow().apply {
        addView(pauseButton)
        addView(w.textButton(R.string.hud_recenter) { actions.recenter() })
        addView(w.textButton(R.string.hud_layout) { actions.layout() })
        addView(moreButton)
    }

    private val secondary = w.flowRow().apply {
        addView(profileButton)
        addView(w.textButton(R.string.hud_learn) { actions.learn() })
        addView(w.textButton(R.string.hud_move) { actions.movePanel() })
        addView(w.textButton(R.string.hud_stop) { actions.stop() })
        addView(w.textButton(R.string.hud_hide) { actions.toggleExpanded() })
    }

    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        // The status takes the rows' width (wrapping inside it) instead of stretching the panel.
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(primary)
        addView(secondary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = w.dp(OverlayWidgets.SPACING_DP) })
    }

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = w.dp(PADDING_DP)
        setPadding(pad, pad, pad, pad)
        background = w.panelBackground()
        addView(indicator, LinearLayout.LayoutParams(w.dp(INDICATOR_DP), w.dp(INDICATOR_DP)))
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = w.dp(PADDING_DP) })
    }

    var expanded = true
        private set
    var moreOpen = false
        private set
    private var statusShown = true

    /**
     * @param showStatus show the status line even when collapsed (calibration prompts, notices).
     */
    fun show(expanded: Boolean, showStatus: Boolean) {
        if (expanded == this.expanded && showStatus == statusShown) return
        this.expanded = expanded
        statusShown = showStatus
        if (!expanded) moreOpen = false
        primary.visibility = if (expanded) View.VISIBLE else View.GONE
        secondary.visibility = if (expanded && moreOpen) View.VISIBLE else View.GONE
        status.visibility = if (expanded || showStatus) View.VISIBLE else View.GONE
        body.visibility = if (expanded || showStatus) View.VISIBLE else View.GONE
        updateMoreLabel()
    }

    fun setMoreOpen(open: Boolean) {
        moreOpen = open && expanded
        secondary.visibility = if (moreOpen) View.VISIBLE else View.GONE
        updateMoreLabel()
    }

    private fun updateMoreLabel() {
        moreButton.setText(if (moreOpen) R.string.hud_less else R.string.hud_more)
        ViewCompat.setStateDescription(moreButton, ctx.getString(if (moreOpen) R.string.state_expanded else R.string.state_collapsed))
    }

    init {
        expanded = false // force the first show() to apply
        show(expanded = true, showStatus = true)
    }

    companion object {
        const val INDICATOR_DP = 56
        const val PADDING_DP = 6
        const val STATUS_MAX_WIDTH_DP = 300
        const val PROFILE_MAX_WIDTH_DP = 140
    }
}
