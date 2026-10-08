package com.clu.motion.overlay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.clu.motion.R

internal class EditorActions(
    val nudge: (dx: Double, dy: Double) -> Unit,
    val next: () -> Unit,
    val resize: (delta: Double) -> Unit,
    val toggleButton: () -> Unit,
    val toggleLink: () -> Unit,
    val cycleBar: () -> Unit,
    val cancel: () -> Unit,
    val save: () -> Unit,
)

/**
 * The layout editor's control bar, compact enough for a landscape game screen:
 *
 *   [status, 1–2 lines                                                         ]
 *   [⠿] [◀][▲][▼][▶] [Next] [Size −][Size +] [On/off] [Link] [✕] [Save] [▾]
 *
 * One strip in landscape (≈ 90 dp of a 375 dp tall screen), a few rows in portrait. ⠿ is the
 * drag handle (tap: jump to the next screen edge). ▾ shrinks the bar to [⠿][▴] so the whole game
 * is visible while placing controls.
 */
internal class EditorBar(private val w: OverlayWidgets, actions: EditorActions, nudgeStep: Double) {
    private val ctx = w.ctx

    val status = w.statusText(13f).apply {
        setPadding(w.dp(4), 0, w.dp(4), w.dp(4))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    val handle: Button = w.iconButton("⠿", R.string.editor_handle) { actions.cycleBar() }

    val linkButton: Button = w.textButton(R.string.editor_link) { actions.toggleLink() }

    private val minimizeButton: Button = w.iconButton("▾", R.string.editor_minimize) { setMinimized(!minimized) }

    /** Everything that hides when the bar is minimised. */
    private val full: List<View> = listOf(
        w.iconButton("◀", R.string.editor_nudge_left) { actions.nudge(-nudgeStep, 0.0) },
        w.iconButton("▲", R.string.editor_nudge_up) { actions.nudge(0.0, -nudgeStep) },
        w.iconButton("▼", R.string.editor_nudge_down) { actions.nudge(0.0, nudgeStep) },
        w.iconButton("▶", R.string.editor_nudge_right) { actions.nudge(nudgeStep, 0.0) },
        w.textButton(R.string.editor_next) { actions.next() },
        w.textButton(R.string.editor_smaller) { actions.resize(-0.01) },
        w.textButton(R.string.editor_bigger) { actions.resize(0.01) },
        w.textButton(R.string.editor_toggle) { actions.toggleButton() },
        linkButton,
        w.iconButton("✕", R.string.editor_cancel) { actions.cancel() },
        w.textButton(R.string.editor_save) { actions.save() },
    )

    private val controls = w.flowRow().apply {
        addView(handle)
        full.forEach { addView(it) }
        addView(minimizeButton)
    }

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pad = w.dp(PADDING_DP)
        setPadding(pad, pad, pad, pad)
        background = w.panelBackground()
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(controls)
    }

    var minimized = false
        private set

    /** Called when the bar is minimised or restored (its size changes). */
    var onMinimizedChanged: (Boolean) -> Unit = {}

    fun setMinimized(m: Boolean) {
        if (m == minimized) return
        minimized = m
        val visibility = if (m) View.GONE else View.VISIBLE
        status.visibility = visibility
        full.forEach { it.visibility = visibility }
        minimizeButton.text = if (m) "▴" else "▾"
        minimizeButton.contentDescription = ctx.getString(if (m) R.string.editor_restore else R.string.editor_minimize)
        onMinimizedChanged(m)
    }

    companion object {
        const val PADDING_DP = 6
    }
}
