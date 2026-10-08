package com.clu.motion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.clu.motion.CluApp
import com.clu.motion.R
import com.clu.motion.engine.SessionState
import com.clu.motion.input.InputDispatcherService
import com.clu.motion.input.ServiceHealth
import com.clu.motion.overlay.TiltIndicatorView
import com.clu.motion.profile.ActionType
import com.clu.motion.profile.AxisMode
import com.clu.motion.profile.ControlProfile
import com.clu.motion.profile.CurveType
import com.clu.motion.profile.FilterType
import com.clu.motion.profile.JoystickMode
import com.clu.motion.profile.OutputMode
import com.clu.motion.profile.ProfileState
import com.clu.motion.profile.TriggerBinding
import com.clu.motion.profile.TriggerKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Setup checklist, live tilt preview and profile tuning.
 *
 * Built for the people who use it: every control is ≥ 48 dp, labelled for screen readers,
 * operable by keyboard/switch focus, and changes are saved immediately (no Save button to reach).
 * Touch injection is automatically blocked while this screen is in front, so tuning with a live
 * session running is safe.
 */
class MainActivity : ComponentActivity() {

    private val engine by lazy { CluApp.engine(this) }
    private lateinit var content: LinearLayout
    private val binders = mutableListOf<(ControlProfile) -> Unit>()
    private var binding = false
    private var current: ControlProfile? = null

    private lateinit var serviceStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var sensorStatus: TextView
    private lateinit var sessionStatus: TextView
    private lateinit var preview: TiltIndicatorView
    private lateinit var profileSpinner: Spinner
    private lateinit var keyBindings: LinearLayout
    private lateinit var keyLearnStatus: TextView
    private var learningKey = false
    private var profileIds: List<String> = emptyList()
    private var keyRowsSignature: Any? = null
    private var restrictedNote: TextView? = null
    private var enabledSinceMs: Long? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) update { it.copy(sound = it.sound.copy(enabled = true)) } else current?.let(::bind)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val pad = dp(16)
            content.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        buildUi()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { engine.activeProfile.collect(::bind) }
                launch { engine.profiles.collect(::updateProfiles) }
                launch { engine.session.collect(::updateSession) }
                launch { InputDispatcherService.connected.collect { refreshStatus() } }
                launch { engine.sensorStatus.collect { refreshStatus() } }
                launch {
                    while (isActive) {
                        preview.render(engine.frames.value, engine.session.value is SessionState.Paused)
                        delay(PREVIEW_INTERVAL_MS)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        if (learningKey) engine.cancelKeyLearning()
        super.onDestroy()
    }

    /**
     * Fallback key capture when the accessibility service (which sees keys first) is off.
     * dispatchKeyEvent, not onKeyDown: a focused Button would otherwise swallow Space/Enter,
     * which is exactly what most switch interfaces send.
     */
    @SuppressLint("RestrictedApi") // Lint false positive: overriding the public Activity API.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (learningKey && event.action == KeyEvent.ACTION_DOWN && !isNavigationKey(event.keyCode)) {
            engine.cancelKeyLearning()
            onKeyLearned(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- UI construction ---------------------------------------------------------------------

    private fun buildUi() {
        title(R.string.app_name)
        body(getString(R.string.main_intro))

        heading(R.string.section_setup)
        serviceStatus = body("")
        ViewCompat.setAccessibilityLiveRegion(serviceStatus, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        buttonRow(button(R.string.action_open_accessibility) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ "restricted settings" (Android 15/16: Enhanced Confirmation Mode).
            restrictedNote = body("").apply { setTypeface(typeface, Typeface.BOLD) }
            body(getString(R.string.setup_restricted_settings))
            buttonRow(button(R.string.action_app_info) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            })
        }
        body(getString(R.string.setup_force_stop))
        notificationStatus = body("")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            buttonRow(button(R.string.action_allow_notifications) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) })
        }
        if (XiaomiSettings.isXiaomi) {
            // HyperOS: the per-app Battery saver is what protects Clu, not AOSP's Doze list.
            subheading(R.string.section_xiaomi)
            body(getString(R.string.xiaomi_checklist))
            buttonRow(
                button(R.string.action_xiaomi_battery) { XiaomiSettings.openBatterySaver(this) },
                button(R.string.action_xiaomi_autostart) { XiaomiSettings.openAutostart(this) },
            )
            body(getString(R.string.xiaomi_game_turbo))
        } else {
            body(getString(R.string.setup_battery))
            buttonRow(button(R.string.action_battery_settings) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        }
        sensorStatus = body("")

        subheading(R.string.section_device_check)
        body(getString(R.string.device_check_hint))
        buttonRow(
            button(R.string.action_device_test) { startActivity(Intent(this, InjectionTestActivity::class.java)) },
            button(R.string.action_share_report) {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.test_report_title))
                    .putExtra(Intent.EXTRA_TEXT, DeviceReport.build(this, engine))
                startActivity(Intent.createChooser(send, getString(R.string.action_share_report)))
            },
        )

        heading(R.string.section_session)
        preview = TiltIndicatorView(this)
        content.addView(preview, LinearLayout.LayoutParams(dp(160), dp(160)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        sessionStatus = body("")
        ViewCompat.setAccessibilityLiveRegion(sessionStatus, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        buttonRow(
            button(R.string.hud_start) { engine.startSession() },
            button(R.string.hud_pause) { engine.togglePause() },
            button(R.string.hud_stop) { engine.stopSession() },
        )
        buttonRow(button(R.string.hud_recenter) { engine.recalibrate() }, button(R.string.action_learn_moves) { engine.learnMoves() })
        body(getString(R.string.session_hint))

        heading(R.string.section_profile)
        profileSpinner = Spinner(this).apply { contentDescription = getString(R.string.label_profile) }
        content.addView(profileSpinner, matchWrap())
        profileSpinner.onItemSelectedListener = selectionListener(profileSpinner) { position ->
            profileIds.getOrNull(position)?.let { if (it != engine.activeProfile.value.id) engine.selectProfile(it) }
        }
        buttonRow(
            button(R.string.action_duplicate) {
                engine.duplicateActiveProfile(getString(R.string.profile_copy_name, engine.activeProfile.value.name))
            },
            button(R.string.action_reset) { engine.resetActiveProfile() },
            button(R.string.action_delete) { engine.deleteActiveProfile() },
        )

        heading(R.string.section_movement)
        slider(R.string.label_full_tilt, 2.0, 45.0, 0.5, { getString(R.string.fmt_degrees, it) }, { it.axes.fullTiltDeg }) { p, v ->
            p.copy(axes = p.axes.copy(fullTiltDeg = v))
        }
        slider(R.string.label_deadzone, 0.0, 8.0, 0.1, { getString(R.string.fmt_degrees, it) }, { it.response.deadzoneDeg }) { p, v ->
            p.copy(response = p.response.copy(deadzoneDeg = v))
        }
        choice(
            R.string.label_axis_mode,
            listOf(
                AxisMode.GRAVITY_TILT to R.string.axis_tilt,
                AxisMode.GRAVITY_TURN to R.string.axis_turn,
                AxisMode.DEVICE to R.string.axis_device,
                AxisMode.LEARNED to R.string.axis_learned,
            ),
            { it.axes.mode },
        ) { p, v -> p.copy(axes = p.axes.copy(mode = v)) }
        toggle(R.string.label_invert_x, { it.axes.invertX }) { p, v -> p.copy(axes = p.axes.copy(invertX = v)) }
        toggle(R.string.label_invert_y, { it.axes.invertY }) { p, v -> p.copy(axes = p.axes.copy(invertY = v)) }
        choice(
            R.string.label_curve,
            listOf(CurveType.LINEAR to R.string.curve_linear, CurveType.POWER to R.string.curve_power, CurveType.SIGMOID to R.string.curve_sigmoid),
            { it.response.curve },
        ) { p, v -> p.copy(response = p.response.copy(curve = v)) }
        slider(R.string.label_boost, 0.4, 2.0, 0.05, { "%.2f".format(it) }, { it.response.exponent }) { p, v ->
            p.copy(response = p.response.copy(exponent = v))
        }
        slider(R.string.label_sigmoid_mid, 0.1, 0.9, 0.05, { percent(it) }, { it.response.sigmoidMidpoint }) { p, v ->
            p.copy(response = p.response.copy(sigmoidMidpoint = v))
        }
        slider(R.string.label_anti_deadzone, 0.0, 0.5, 0.01, { percent(it) }, { it.response.antiDeadzone }) { p, v ->
            p.copy(response = p.response.copy(antiDeadzone = v))
        }
        slider(R.string.label_angle_snap, 0.0, 22.0, 1.0, { getString(R.string.fmt_degrees, it) }, { it.response.angleSnapDeg }) { p, v ->
            p.copy(response = p.response.copy(angleSnapDeg = v))
        }
        choice(
            R.string.label_output,
            listOf(OutputMode.ANALOG to R.string.output_analog, OutputMode.DIGITAL_8 to R.string.output_8, OutputMode.DIGITAL_4 to R.string.output_4),
            { it.response.outputMode },
        ) { p, v -> p.copy(response = p.response.copy(outputMode = v)) }
        choice(
            R.string.label_joystick_mode,
            listOf(
                JoystickMode.STICK to R.string.joystick_stick,
                JoystickMode.AIM to R.string.joystick_aim,
                JoystickMode.CAMERA_DRAG to R.string.joystick_camera,
                JoystickMode.OFF to R.string.joystick_off,
            ),
            { it.joystick.mode },
        ) { p, v -> p.copy(joystick = p.joystick.copy(mode = v)) }
        slider(R.string.label_camera_speed, 200.0, 4000.0, 50.0, { getString(R.string.fmt_px_per_s, it) }, { it.joystick.cameraSpeedPxPerSec }) { p, v ->
            p.copy(joystick = p.joystick.copy(cameraSpeedPxPerSec = v))
        }
        slider(R.string.label_release_after, 50.0, 1500.0, 50.0, { getString(R.string.fmt_ms, it) }, { it.joystick.releaseAfterNeutralMs.toDouble() }) { p, v ->
            p.copy(joystick = p.joystick.copy(releaseAfterNeutralMs = v.toLong()))
        }
        toggle(R.string.label_hold_center, { it.joystick.holdAtCenter }) { p, v -> p.copy(joystick = p.joystick.copy(holdAtCenter = v)) }
        toggle(R.string.label_magnetometer, { it.sensor.useMagnetometer }) { p, v -> p.copy(sensor = p.sensor.copy(useMagnetometer = v)) }

        heading(R.string.section_aim)
        body(getString(R.string.aim_explainer))
        slider(R.string.label_aim_sensitivity, 0.1, 2.0, 0.05, { percent(it) }, { it.aim.sensitivity }) { p, v ->
            p.copy(aim = p.aim.copy(sensitivity = v))
        }
        slider(R.string.label_aim_precision, 0.1, 1.0, 0.05, { percent(it) }, { it.aim.precisionScale }) { p, v ->
            p.copy(aim = p.aim.copy(precisionScale = v))
        }
        slider(R.string.label_aim_steady, 0.0, 6.0, 0.25, { if (it == 0.0) getString(R.string.off) else getString(R.string.fmt_deg_per_s_fine, it) }, {
            it.aim.steadyBelowDegPerSec
        }) { p, v -> p.copy(aim = p.aim.copy(steadyBelowDegPerSec = v)) }
        slider(R.string.label_aim_accel, 1.0, 4.0, 0.1, { if (it <= 1.0) getString(R.string.off) else getString(R.string.fmt_times, it) }, {
            it.aim.accelerationMax
        }) { p, v -> p.copy(aim = p.aim.copy(accelerationMax = v)) }
        slider(R.string.label_aim_edge, 0.0, 0.95, 0.05, { if (it == 0.0) getString(R.string.off) else percent(it) }, { it.aim.edgeTurnFrom }) { p, v ->
            p.copy(aim = p.aim.copy(edgeTurnFrom = v))
        }
        slider(R.string.label_aim_edge_speed, 0.2, 5.0, 0.1, { getString(R.string.fmt_per_s, it) }, { it.aim.edgeTurnSpeed }) { p, v ->
            p.copy(aim = p.aim.copy(edgeTurnSpeed = v))
        }
        slider(R.string.label_aim_idle, 300.0, 5000.0, 100.0, { getString(R.string.fmt_ms, it) }, { it.aim.releaseAfterIdleMs.toDouble() }) { p, v ->
            p.copy(aim = p.aim.copy(releaseAfterIdleMs = v.toLong()))
        }

        heading(R.string.section_tremor)
        choice(
            R.string.label_filter,
            listOf(FilterType.ONE_EURO to R.string.filter_one_euro, FilterType.KALMAN to R.string.filter_kalman, FilterType.NONE to R.string.filter_none),
            { it.filter.type },
        ) { p, v -> p.copy(filter = p.filter.copy(type = v)) }
        slider(R.string.label_steadiness, 0.3, 3.0, 0.05, { getString(R.string.fmt_hz, it) }, { it.filter.minCutoffHz }) { p, v ->
            p.copy(filter = p.filter.copy(minCutoffHz = v))
        }
        slider(R.string.label_responsiveness, 0.0, 0.15, 0.005, { "%.3f".format(it) }, { it.filter.beta }) { p, v ->
            p.copy(filter = p.filter.copy(beta = v))
        }
        toggle(R.string.label_spasm_guard, { it.filter.spasmGateEnabled }) { p, v -> p.copy(filter = p.filter.copy(spasmGateEnabled = v)) }
        slider(R.string.label_spasm_speed, 150.0, 800.0, 10.0, { getString(R.string.fmt_deg_per_s, it) }, { it.filter.spasmSpeedDegPerSec }) { p, v ->
            p.copy(filter = p.filter.copy(spasmSpeedDegPerSec = v))
        }
        toggle(R.string.label_auto_tune, { it.filter.autoTuneFromTremor }) { p, v -> p.copy(filter = p.filter.copy(autoTuneFromTremor = v)) }

        heading(R.string.section_actions)
        toggle(R.string.label_dwell, { it.dwell.enabled }) { p, v -> p.copy(dwell = p.dwell.copy(enabled = v)) }
        slider(R.string.label_dwell_time, 400.0, 3000.0, 50.0, { getString(R.string.fmt_ms, it) }, { it.dwell.dwellMs.toDouble() }) { p, v ->
            p.copy(dwell = p.dwell.copy(dwellMs = v.toLong()))
        }
        toggle(R.string.label_dwell_resume, { it.dwell.resumeWhilePaused }) { p, v -> p.copy(dwell = p.dwell.copy(resumeWhilePaused = v)) }
        toggle(R.string.label_flicks, { it.flick.enabled }) { p, v -> p.copy(flick = p.flick.copy(enabled = v)) }
        slider(R.string.label_flick_size, 3.0, 25.0, 0.5, { getString(R.string.fmt_degrees, it) }, { it.flick.minAmplitudeDeg }) { p, v ->
            p.copy(flick = p.flick.copy(minAmplitudeDeg = v))
        }
        sound()
        subheading(R.string.label_bindings)
        for (kind in TriggerKind.entries) {
            if (kind == TriggerKind.KEY) continue
            bindingChoice(kind)
        }
        subheading(R.string.label_key_bindings)
        body(getString(R.string.key_bindings_hint))
        keyBindings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(keyBindings, matchWrap())
        keyLearnStatus = body("")
        ViewCompat.setAccessibilityLiveRegion(keyLearnStatus, ViewCompat.ACCESSIBILITY_LIVE_REGION_ASSERTIVE)
        buttonRow(button(R.string.action_add_key) { startKeyLearning() })

        heading(R.string.section_safety)
        toggle(R.string.label_drop, { it.safety.dropDetection }) { p, v -> p.copy(safety = p.safety.copy(dropDetection = v)) }
        toggle(R.string.label_erratic, { it.safety.erraticPause }) { p, v -> p.copy(safety = p.safety.copy(erraticPause = v)) }
        slider(R.string.label_rest, 0.0, 60.0, 1.0, { if (it == 0.0) getString(R.string.off) else getString(R.string.fmt_minutes, it) }, {
            it.safety.restReminderMinutes.toDouble()
        }) { p, v -> p.copy(safety = p.safety.copy(restReminderMinutes = v.roundToInt())) }
        toggle(R.string.label_enforce_break, { it.safety.enforceBreak }) { p, v -> p.copy(safety = p.safety.copy(enforceBreak = v)) }
        toggle(R.string.label_fatigue, { it.safety.fatigueWarnings }) { p, v -> p.copy(safety = p.safety.copy(fatigueWarnings = v)) }

        heading(R.string.section_display)
        toggle(R.string.label_touch_points, { it.showTouchPoints }) { p, v -> p.copy(showTouchPoints = v) }
        body(getString(R.string.layout_hint))
    }

    private fun sound() {
        val sw = Switch(this).apply {
            text = getString(R.string.label_sound)
            minHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        content.addView(sw, matchWrap())
        sw.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            if (checked && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                update { it.copy(sound = it.sound.copy(enabled = checked)) }
            }
        }
        binders += { sw.isChecked = it.sound.enabled }
        body(getString(R.string.sound_hint))
    }

    private fun bindingChoice(kind: TriggerKind) {
        val spinner = labeledSpinner(getString(kind.label()))
        var options: List<ActionOption> = emptyList()
        spinner.onItemSelectedListener = selectionListener(spinner) { index ->
            val opt = options.getOrNull(index) ?: return@selectionListener
            update { p ->
                val existing = p.bindings.firstOrNull { it.trigger == kind }
                if (opt.matches(existing)) return@update p
                val others = p.bindings.filterNot { it.trigger == kind }
                p.copy(bindings = if (opt.type == ActionType.NONE) others else others + TriggerBinding(kind, opt.type, opt.buttonId))
            }
        }
        binders += { profile ->
            val fresh = actionOptions(profile)
            if (fresh != options) {
                options = fresh
                spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fresh.map { it.label })
            }
            spinner.select(options.indexOfFirst { it.matches(profile.bindings.firstOrNull { b -> b.trigger == kind }) }.coerceAtLeast(0))
        }
    }

    private fun bindKeyRows(profile: ControlProfile) {
        val signature = profile.bindings.filter { it.trigger == TriggerKind.KEY } to profile.buttons
        if (signature == keyRowsSignature) return
        keyRowsSignature = signature
        keyBindings.removeAllViews()
        val options = actionOptions(profile)
        for (b in profile.bindings.filter { it.trigger == TriggerKind.KEY }) {
            val name = KeyEvent.keyCodeToString(b.keyCode).removePrefix("KEYCODE_")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minWidth = dp(110)
            }
            val spinner = Spinner(this).apply { contentDescription = getString(R.string.key_binding_description, name) }
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.label })
            spinner.select(options.indexOfFirst { it.matches(b) }.coerceAtLeast(0))
            spinner.onItemSelectedListener = selectionListener(spinner) { index ->
                val opt = options[index]
                if (!opt.matches(b)) {
                    update { p -> p.copy(bindings = p.bindings.map { if (it == b) b.copy(action = opt.type, buttonId = opt.buttonId) else it }) }
                }
            }
            val remove = button(R.string.action_remove) {
                update { p -> p.copy(bindings = p.bindings - b) }
            }.apply { contentDescription = getString(R.string.key_remove_description, name) }
            row.addView(label)
            row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(remove)
            keyBindings.addView(row, matchWrap())
        }
    }

    private fun startKeyLearning() {
        learningKey = true
        keyLearnStatus.text = getString(R.string.key_learn_prompt)
        engine.learnNextKey { code -> onKeyLearned(code) }
    }

    private fun onKeyLearned(keyCode: Int) {
        learningKey = false
        val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        keyLearnStatus.text = getString(R.string.key_learned, name)
        update { p ->
            if (p.bindings.any { it.matches(TriggerKind.KEY, keyCode) }) {
                p
            } else {
                p.copy(bindings = p.bindings + TriggerBinding(TriggerKind.KEY, ActionType.HOLD_BUTTON, buttonId = 1, keyCode = keyCode))
            }
        }
    }

    // ---- State binding -----------------------------------------------------------------------

    private fun bind(profile: ControlProfile) {
        current = profile
        binding = true
        try {
            binders.forEach { it(profile) }
            bindKeyRows(profile)
        } finally {
            binding = false
        }
    }

    private fun updateProfiles(state: ProfileState) {
        profileIds = state.profiles.map { it.id }
        val names = state.profiles.map { it.name }
        profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        profileSpinner.select(profileIds.indexOf(state.activeId).coerceAtLeast(0))
    }

    private fun updateSession(state: SessionState) {
        sessionStatus.text = when (state) {
            SessionState.Stopped -> getString(R.string.status_stopped)
            SessionState.Active -> getString(R.string.session_active)
            is SessionState.Paused -> getString(R.string.status_paused)
        }
    }

    private fun refreshStatus() {
        val enabled = InputDispatcherService.isEnabled(this)
        val connected = InputDispatcherService.connected.value
        val now = SystemClock.uptimeMillis()
        enabledSinceMs = if (enabled && !connected) enabledSinceMs ?: now else null
        val everConnected = getSharedPreferences(InputDispatcherService.HEALTH_PREFS, MODE_PRIVATE)
            .getBoolean(InputDispatcherService.KEY_EVER_CONNECTED, false)
        val health = ServiceHealth.evaluate(enabled, connected, everConnected, enabledSinceMs, now)
        serviceStatus.text = getString(
            when (health) {
                ServiceHealth.RUNNING -> R.string.setup_service_on
                ServiceHealth.STARTING -> R.string.setup_service_starting
                ServiceHealth.STUCK -> R.string.setup_service_stuck
                ServiceHealth.TURNED_OFF -> R.string.setup_service_turned_off
                ServiceHealth.OFF -> R.string.setup_service_off
            },
        )
        // Re-check once the "starting" grace period is over, so a stuck service is called out.
        if (health == ServiceHealth.STARTING) serviceStatus.postDelayed({ refreshStatus() }, ServiceHealth.STUCK_AFTER_MS + 500)
        restrictedNote?.let { note ->
            val fromFile = !enabled && installedFromFile()
            note.visibility = if (fromFile) View.VISIBLE else View.GONE
            if (fromFile) note.setText(R.string.setup_installed_from_file)
        }
        val notificationsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notificationStatus.text = getString(if (notificationsOk) R.string.setup_notifications_on else R.string.setup_notifications_off)
        val sensor = engine.sensorStatus.value
        sensorStatus.text = when {
            sensor.source == null -> getString(R.string.setup_sensor_idle)
            !sensor.reliable -> getString(R.string.setup_sensor_unreliable, sensor.source.name)
            else -> getString(R.string.setup_sensor, sensor.source.name)
        }
    }

    // ---- Small view DSL ----------------------------------------------------------------------

    private fun update(transform: (ControlProfile) -> ControlProfile) {
        if (!binding) engine.updateActiveProfile(transform)
    }

    private fun slider(
        @StringRes label: Int,
        min: Double,
        max: Double,
        step: Double,
        format: (Double) -> String,
        get: (ControlProfile) -> Double,
        set: (ControlProfile, Double) -> ControlProfile,
    ) {
        val title = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(12), 0, 0)
        }
        val bar = SeekBar(this).apply {
            id = View.generateViewId()
            this.max = ((max - min) / step).roundToInt()
            minimumHeight = dp(48)
        }
        title.labelFor = bar.id
        content.addView(title, matchWrap())
        content.addView(bar, matchWrap())
        fun value(progress: Int) = min + progress * step
        fun show(v: Double) {
            val text = format(v)
            title.text = getString(R.string.fmt_label_value, getString(label), text)
            ViewCompat.setStateDescription(bar, text)
        }
        val commit = Runnable { update { set(it, value(bar.progress)) } }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                show(value(progress))
                if (fromUser && !binding) {
                    bar.removeCallbacks(commit)
                    bar.postDelayed(commit, SLIDER_DEBOUNCE_MS) // one write per pause, not per pixel
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        binders += { p ->
            val v = get(p)
            bar.progress = ((v - min) / step).roundToInt().coerceIn(0, bar.max)
            show(v)
        }
    }

    private fun toggle(@StringRes label: Int, get: (ControlProfile) -> Boolean, set: (ControlProfile, Boolean) -> ControlProfile) {
        val sw = Switch(this).apply {
            text = getString(label)
            minHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        content.addView(sw, matchWrap())
        sw.setOnCheckedChangeListener { _, checked -> update { set(it, checked) } }
        binders += { sw.isChecked = get(it) }
    }

    private fun <T> choice(
        @StringRes label: Int,
        options: List<Pair<T, Int>>,
        get: (ControlProfile) -> T,
        set: (ControlProfile, T) -> ControlProfile,
    ) {
        val spinner = labeledSpinner(getString(label))
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { getString(it.second) })
        spinner.onItemSelectedListener = selectionListener(spinner) { index ->
            val v = options[index].first
            if (current?.let(get) != v) update { p -> set(p, v) }
        }
        binders += { p -> spinner.select(options.indexOfFirst { it.first == get(p) }.coerceAtLeast(0)) }
    }

    private fun labeledSpinner(label: String): Spinner {
        val title = TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(12), 0, 0)
        }
        val spinner = Spinner(this).apply {
            id = View.generateViewId()
            minimumHeight = dp(48)
        }
        title.labelFor = spinner.id
        content.addView(title, matchWrap())
        content.addView(spinner, matchWrap())
        return spinner
    }

    /**
     * Spinner reports programmatic selections asynchronously (after layout), when [binding] is
     * already false. The position we set ourselves is remembered in the tag and ignored.
     */
    private fun selectionListener(spinner: Spinner, onSelected: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (binding || spinner.tag == position) return
            spinner.tag = position
            onSelected(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun Spinner.select(position: Int) {
        tag = position
        setSelection(position, false)
    }

    private data class ActionOption(val type: ActionType, val buttonId: Int, val label: String) {
        fun matches(b: TriggerBinding?) =
            if (b == null) type == ActionType.NONE else b.action == type && (!type.needsButton || b.buttonId == buttonId)
    }

    private fun actionOptions(profile: ControlProfile): List<ActionOption> = buildList {
        add(ActionOption(ActionType.NONE, 0, getString(R.string.action_none)))
        for (b in profile.buttons) {
            add(ActionOption(ActionType.TAP_BUTTON, b.id, getString(R.string.action_tap, b.label)))
            add(ActionOption(ActionType.HOLD_BUTTON, b.id, getString(R.string.action_hold, b.label)))
            add(ActionOption(ActionType.TOGGLE_BUTTON, b.id, getString(R.string.action_toggle, b.label)))
        }
        add(ActionOption(ActionType.RECALIBRATE, 0, getString(R.string.hud_recenter)))
        add(ActionOption(ActionType.TOGGLE_PAUSE, 0, getString(R.string.action_pause_resume)))
        add(ActionOption(ActionType.NEXT_PROFILE, 0, getString(R.string.action_next_profile)))
        add(ActionOption(ActionType.BACK, 0, getString(R.string.action_back)))
        add(ActionOption(ActionType.HOME, 0, getString(R.string.action_home)))
        add(ActionOption(ActionType.RECENTS, 0, getString(R.string.action_recents)))
        add(ActionOption(ActionType.SWITCH_MOVE_AIM, 0, getString(R.string.action_switch_move_aim)))
        add(ActionOption(ActionType.TOGGLE_PRECISION, 0, getString(R.string.action_precision)))
    }

    private fun TriggerKind.label() = when (this) {
        TriggerKind.DWELL_UP -> R.string.trigger_dwell_up
        TriggerKind.DWELL_DOWN -> R.string.trigger_dwell_down
        TriggerKind.DWELL_LEFT -> R.string.trigger_dwell_left
        TriggerKind.DWELL_RIGHT -> R.string.trigger_dwell_right
        TriggerKind.FLICK_UP -> R.string.trigger_flick_up
        TriggerKind.FLICK_DOWN -> R.string.trigger_flick_down
        TriggerKind.FLICK_LEFT -> R.string.trigger_flick_left
        TriggerKind.FLICK_RIGHT -> R.string.trigger_flick_right
        TriggerKind.TWIST_LEFT -> R.string.trigger_twist_left
        TriggerKind.TWIST_RIGHT -> R.string.trigger_twist_right
        TriggerKind.SOUND_CLICK -> R.string.trigger_sound
        TriggerKind.KEY -> R.string.label_key_bindings
    }

    private fun title(@StringRes res: Int) = TextView(this).apply {
        setText(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        setTypeface(typeface, Typeface.BOLD)
        ViewCompat.setAccessibilityHeading(this, true)
    }.also { content.addView(it, matchWrap()) }

    private fun heading(@StringRes res: Int) = TextView(this).apply {
        setText(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(28), 0, dp(4))
        ViewCompat.setAccessibilityHeading(this, true)
    }.also { content.addView(it, matchWrap()) }

    private fun subheading(@StringRes res: Int) = TextView(this).apply {
        setText(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, 0)
        ViewCompat.setAccessibilityHeading(this, true)
    }.also { content.addView(it, matchWrap()) }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(6), 0, dp(6))
    }.also { content.addView(it, matchWrap()) }

    private fun button(@StringRes res: Int, onClick: () -> Unit) = Button(this).apply {
        setText(res)
        isAllCaps = false
        minHeight = dp(48)
        minimumHeight = dp(48)
        setOnClickListener { onClick() }
    }

    private fun buttonRow(vararg buttons: Button) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (b in buttons) row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row, matchWrap())
    }

    /** Android 13+: APKs opened from a file manager or browser are subject to restricted settings. */
    private fun installedFromFile(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val source = packageManager.getInstallSourceInfo(packageName).packageSource
            source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun percent(v: Double) = getString(R.string.fmt_percent, (v * 100).roundToInt())

    private fun isNavigationKey(code: Int) = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_TAB ||
        code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val PREVIEW_INTERVAL_MS = 33L
        const val SLIDER_DEBOUNCE_MS = 200L
    }
}

private val ActionType.needsButton
    get() = this == ActionType.TAP_BUTTON || this == ActionType.HOLD_BUTTON || this == ActionType.TOGGLE_BUTTON
