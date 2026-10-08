package com.clu.motion.engine

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.clu.motion.R
import com.clu.motion.core.PipelineEvent
import com.clu.motion.core.TriggerPhase
import com.clu.motion.core.diag.InjectionStats
import com.clu.motion.core.diag.SelfTest
import com.clu.motion.core.safety.PauseReason
import com.clu.motion.core.safety.SafetyEvent
import com.clu.motion.input.InputDispatcherService
import com.clu.motion.profile.ActionType
import com.clu.motion.profile.AxisMode
import com.clu.motion.profile.ControlProfile
import com.clu.motion.profile.Presets
import com.clu.motion.profile.ProfileRepository
import com.clu.motion.profile.ProfileState
import com.clu.motion.profile.TriggerKind
import com.clu.motion.sensor.MotionProcessor
import com.clu.motion.service.MotionSessionService
import com.clu.motion.trigger.AcousticClickTrigger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Stopped : SessionState
    data object Active : SessionState
    data class Paused(val reason: PauseReason) : SessionState
}

/** Requests for the injector, which owns all touch state. */
sealed interface TouchCommand {
    data class Tap(val buttonId: Int) : TouchCommand
    data class Press(val buttonId: Int) : TouchCommand
    data class Release(val buttonId: Int) : TouchCommand
    data class Toggle(val buttonId: Int) : TouchCommand
    data class Global(val action: Int) : TouchCommand
    data object ReleaseAll : TouchCommand
}

data class Notice(val text: String, val important: Boolean = false)

/**
 * Process-wide session owner: sensors → pipeline → trigger routing → touch commands.
 *
 * The accessibility service (injection + overlay), the foreground service (keep-alive +
 * notification controls) and the settings screen are all thin views over this object, so the
 * session survives any one of them restarting.
 *
 * Thread-safety: pause/resume/trigger routing may be called from the sensor, audio or main
 * thread (StateFlow CAS + channels). Lifecycle calls hop to the main thread.
 */
class MotionEngine(private val app: Context, private val repository: ProfileRepository) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Engine coroutine failed", e) },
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    val profiles: StateFlow<ProfileState> = repository.state
        .stateIn(scope, SharingStarted.Eagerly, ProfileState(Presets.all(), Presets.HANDHELD))

    val activeProfile: StateFlow<ControlProfile> = profiles
        .map { it.active }
        .stateIn(scope, SharingStarted.Eagerly, Presets.handheld())

    private val _session = MutableStateFlow<SessionState>(SessionState.Stopped)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private val processor = MotionProcessor(app, ::onPipelineEvent)
    val frames get() = processor.frames
    val sensorStatus get() = processor.status

    private val commands = Channel<TouchCommand>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Single consumer: the injection thread of [InputDispatcherService]. */
    val touchCommands: ReceiveChannel<TouchCommand> get() = commands

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    /**
     * After start, resume or recalibration the joystick stays released until the stick has been
     * at neutral once, so resuming (often by holding a tilt) never lurches the character.
     * Set here, cleared by the injector.
     */
    @Volatile
    var stickLocked = true

    @Volatile
    private var keyLearner: ((Int) -> Unit)? = null

    /** Shared recorder for the on-device injection test (disabled unless that screen is open). */
    val injectionStats = InjectionStats()

    private val _selfTest = MutableStateFlow<SelfTest?>(null)

    /** Non-null while the synthetic injection test drives the virtual stick instead of sensors. */
    val selfTest: StateFlow<SelfTest?> = _selfTest.asStateFlow()

    private val acoustic = AcousticClickTrigger(
        app,
        onClick = { onTrigger(TriggerKind.SOUND_CLICK, 0, TriggerPhase.PULSE) },
        onSilenced = { silenced -> if (silenced && _session.value != SessionState.Stopped) notice(R.string.notice_mic_silenced, important = true) },
    )
    private var screenReceiverRegistered = false
    private var lastProfileId: String? = null
    private var lastSoundEnabled: Boolean? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    pause(PauseReason.SCREEN_OFF)
                    processor.suspendSensors()
                    acoustic.stop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    processor.resumeSensors()
                    updateAcoustic(activeProfile.value)
                }
            }
        }
    }

    init {
        scope.launch {
            activeProfile.collect { profile ->
                processor.setProfile(profile)
                if (_session.value != SessionState.Stopped) {
                    // Bindings and layout may differ: drop anything held under the old profile.
                    if (lastProfileId != null && lastProfileId != profile.id) commands.trySend(TouchCommand.ReleaseAll)
                    // The foreground service only gains its "microphone" type when (re)started, so a
                    // sound trigger switched on mid-session needs it promoted again (Android 11+).
                    if (profile.sound.enabled && lastSoundEnabled == false) MotionSessionService.start(app)
                    updateAcoustic(profile)
                }
                lastProfileId = profile.id
                lastSoundEnabled = profile.sound.enabled
            }
        }
    }

    // ---- Session lifecycle -------------------------------------------------------------------

    fun startSession() = onMain {
        if (_session.value != SessionState.Stopped) return@onMain
        val profile = activeProfile.value
        stickLocked = true
        processor.start(profile)
        processor.recalibrate()
        processor.resetSafetySession()
        processor.setActive(true)
        _session.value = SessionState.Active
        registerScreenReceiver()
        if (!MotionSessionService.start(app)) notice(R.string.notice_fgs_denied)
        updateAcoustic(profile)
        if (!InputDispatcherService.connected.value) notice(R.string.notice_service_off, important = true)
        notice(R.string.notice_calibrating)
    }

    fun stopSession() = onMain {
        if (_session.value == SessionState.Stopped) return@onMain
        _session.value = SessionState.Stopped
        commands.trySend(TouchCommand.ReleaseAll)
        processor.stop()
        acoustic.stop()
        unregisterScreenReceiver()
        MotionSessionService.stop(app)
    }

    /** Thread-safe. Releases every touch immediately. */
    fun pause(reason: PauseReason) {
        while (true) {
            val current = _session.value
            if (current !is SessionState.Active) return
            if (_session.compareAndSet(current, SessionState.Paused(reason))) break
        }
        commands.trySend(TouchCommand.ReleaseAll)
        processor.setActive(false)
        notice(
            when (reason) {
                PauseReason.USER -> R.string.notice_paused
                PauseReason.ERRATIC_MOTION -> R.string.notice_paused_erratic
                PauseReason.DROP_DETECTED -> R.string.notice_paused_drop
                PauseReason.REST_BREAK -> R.string.notice_paused_rest
                PauseReason.SCREEN_OFF -> R.string.notice_paused_screen_off
                PauseReason.LAYOUT_EDIT -> R.string.notice_paused_layout
                PauseReason.ERROR -> R.string.notice_paused_error
            },
            important = reason != PauseReason.USER && reason != PauseReason.LAYOUT_EDIT,
        )
    }

    /** Thread-safe. */
    fun resume() {
        val current = _session.value as? SessionState.Paused ?: return
        if (!_session.compareAndSet(current, SessionState.Active)) return
        stickLocked = true
        processor.setActive(true)
        notice(R.string.notice_resumed)
    }

    fun togglePause() {
        when (_session.value) {
            SessionState.Stopped -> startSession()
            SessionState.Active -> pause(PauseReason.USER)
            is SessionState.Paused -> resume()
        }
    }

    /** Re-captures the neutral posture ("ergonomic zero point"). Starts a session if needed. */
    fun recalibrate() {
        if (_session.value == SessionState.Stopped) {
            startSession()
            return
        }
        stickLocked = true
        processor.recalibrate()
        notice(R.string.notice_calibrating)
    }

    /** Guided range-of-motion learning; the result is saved into the active profile. */
    fun learnMoves() = onMain {
        if (_session.value == SessionState.Stopped) startSession()
        stickLocked = true
        processor.learnAxes()
    }

    /** Starts synthetic stick + tap injection (no sensors needed). Requires the accessibility service. */
    fun startSelfTest(nowMs: Long) {
        injectionStats.reset()
        _selfTest.value = SelfTest(startedAtMs = nowMs)
    }

    fun stopSelfTest() {
        if (_selfTest.value == null) return
        _selfTest.value = null
        commands.trySend(TouchCommand.ReleaseAll)
    }

    // ---- Profiles ----------------------------------------------------------------------------

    fun selectProfile(id: String) {
        scope.launch { repository.select(id) }
    }

    fun nextProfile() {
        scope.launch {
            val state = profiles.value
            val index = state.profiles.indexOfFirst { it.id == state.activeId }
            val next = state.profiles[(index + 1).mod(state.profiles.size)]
            repository.select(next.id)
            notice(app.getString(R.string.notice_profile, next.name))
        }
    }

    fun updateActiveProfile(transform: (ControlProfile) -> ControlProfile) {
        val id = activeProfile.value.id
        scope.launch { repository.update(id, transform) }
    }

    fun duplicateActiveProfile(name: String) {
        val source = activeProfile.value
        scope.launch { repository.duplicate(source, name) }
    }

    /** Restores a built-in profile's tuning (keeps layout and bindings). No-op for user profiles. */
    fun resetActiveProfile() {
        val id = activeProfile.value.id
        scope.launch { repository.resetToPreset(id) }
    }

    /** Deletes a user-created profile; built-in presets are kept. */
    fun deleteActiveProfile() {
        val id = activeProfile.value.id
        if (Presets.all().any { it.id == id }) return
        scope.launch { repository.delete(id) }
    }

    /** Auto-selects a profile linked to the app that just came to the foreground. */
    fun onForegroundPackage(packageName: String) {
        val state = profiles.value
        val linked = state.profiles.firstOrNull { packageName in it.linkedPackages } ?: return
        if (linked.id == state.activeId) return
        scope.launch {
            repository.select(linked.id)
            notice(app.getString(R.string.notice_profile, linked.name))
        }
    }

    // ---- Key / switch input ------------------------------------------------------------------

    /** Captures the next key press (for binding a switch). Main thread. */
    fun learnNextKey(onKey: (Int) -> Unit) {
        keyLearner = onKey
    }

    fun cancelKeyLearning() {
        keyLearner = null
    }

    /**
     * Called from the accessibility key filter. Returns true to consume the key. Keys are only
     * consumed while they mean something now, so e.g. the volume keys work normally when stopped
     * or paused.
     */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int): Boolean {
        keyLearner?.let { learner ->
            if (down) {
                keyLearner = null
                mainHandler.post { learner(keyCode) }
            }
            return true
        }
        val state = _session.value
        if (state == SessionState.Stopped) return false
        val binding = activeProfile.value.bindings.firstOrNull { it.matches(TriggerKind.KEY, keyCode) } ?: return false
        if (binding.action == ActionType.NONE) return false
        if (state !is SessionState.Active && binding.action.isTouch) return false
        when {
            down && repeatCount == 0 -> onTrigger(TriggerKind.KEY, keyCode, TriggerPhase.PRESS)
            !down -> onTrigger(TriggerKind.KEY, keyCode, TriggerPhase.RELEASE)
        }
        return true
    }

    // ---- Routing -----------------------------------------------------------------------------

    private fun onPipelineEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.Trigger -> onTrigger(event.kind, 0, event.phase)
            is PipelineEvent.Safety -> when (val e = event.event) {
                is SafetyEvent.PauseRequest -> pause(e.reason)
                is SafetyEvent.RestReminder -> notice(
                    app.resources.getQuantityString(R.plurals.notice_rest_reminder, e.activeMinutes, e.activeMinutes),
                    important = true,
                )
                SafetyEvent.FatigueWarning -> notice(R.string.notice_fatigue, important = true)
            }
            is PipelineEvent.Calibrated -> notice(
                if (event.result.unstable) R.string.notice_calibrated_unstable else R.string.notice_calibrated,
            )
            is PipelineEvent.AxesLearned -> {
                updateActiveProfile { it.copy(axes = it.axes.copy(mode = AxisMode.LEARNED, learned = event.axes)) }
                notice(R.string.notice_learned)
            }
            is PipelineEvent.Fault -> pause(PauseReason.ERROR)
        }
    }

    private fun onTrigger(kind: TriggerKind, keyCode: Int, phase: TriggerPhase) {
        val state = _session.value
        if (state == SessionState.Stopped) return
        val profile = activeProfile.value

        if (state is SessionState.Paused && kind.isDwell && phase == TriggerPhase.PRESS &&
            profile.dwell.resumeWhilePaused && state.reason.allowsMotionResume
        ) {
            resume()
            return
        }

        val binding = profile.bindings.firstOrNull { it.matches(kind, keyCode) } ?: return
        val active = state == SessionState.Active
        val start = phase != TriggerPhase.RELEASE
        val id = binding.buttonId
        when (binding.action) {
            ActionType.NONE -> Unit
            ActionType.RECALIBRATE -> if (start) recalibrate()
            ActionType.TOGGLE_PAUSE -> if (start) togglePause()
            ActionType.NEXT_PROFILE -> if (start) nextProfile()
            ActionType.BACK -> if (start) send(TouchCommand.Global(AccessibilityService.GLOBAL_ACTION_BACK))
            ActionType.HOME -> if (start) send(TouchCommand.Global(AccessibilityService.GLOBAL_ACTION_HOME))
            ActionType.RECENTS -> if (start) send(TouchCommand.Global(AccessibilityService.GLOBAL_ACTION_RECENTS))
            ActionType.TAP_BUTTON -> if (active && start) send(TouchCommand.Tap(id))
            ActionType.TOGGLE_BUTTON -> if (active && start) send(TouchCommand.Toggle(id))
            ActionType.HOLD_BUTTON -> when (phase) {
                TriggerPhase.PRESS -> if (active) send(TouchCommand.Press(id))
                TriggerPhase.RELEASE -> send(TouchCommand.Release(id)) // always: never leave a button stuck
                TriggerPhase.PULSE -> if (active) send(TouchCommand.Tap(id))
            }
        }
    }

    private fun send(command: TouchCommand) {
        commands.trySend(command)
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun updateAcoustic(profile: ControlProfile) = onMain {
        val wanted = profile.sound.enabled && _session.value != SessionState.Stopped
        if (!wanted) {
            acoustic.stop()
        } else if (!acoustic.start(profile.sound)) {
            notice(R.string.notice_mic_denied)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(app, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        app.unregisterReceiver(screenReceiver)
        screenReceiverRegistered = false
    }

    private fun notice(@StringRes res: Int, important: Boolean = false) = notice(app.getString(res), important)

    private fun notice(text: String, important: Boolean = false) {
        _notices.tryEmit(Notice(text, important))
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private val TriggerKind.isDwell
        get() = this == TriggerKind.DWELL_UP || this == TriggerKind.DWELL_DOWN ||
            this == TriggerKind.DWELL_LEFT || this == TriggerKind.DWELL_RIGHT

    private val ActionType.isTouch
        get() = this == ActionType.TAP_BUTTON || this == ActionType.HOLD_BUTTON || this == ActionType.TOGGLE_BUTTON

    private companion object {
        const val TAG = "MotionEngine"
    }
}
