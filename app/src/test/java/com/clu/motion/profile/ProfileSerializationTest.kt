package com.clu.motion.profile

import com.clu.motion.core.math.Vec3
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun presetsRoundTrip() {
        val learned = Presets.head().copy(
            axes = AxisConfig(mode = AxisMode.LEARNED, learned = LearnedAxes(Vec3.Y, Vec3.X, 1, 10.0, 8.0, 12.0, 9.0)),
        )
        val all = Presets.all() + learned
        assertEquals(all, json.decodeFromString<List<ControlProfile>>(json.encodeToString(all)))
    }

    @Test
    fun oldDocumentsGainDefaultsAndNewerFieldsAreIgnored() {
        val stored = """[{"id":"x","name":"Old","response":{"deadzoneDeg":2.5,"futureField":true},"anotherFuture":1}]"""
        val p = json.decodeFromString<List<ControlProfile>>(stored).single()
        assertEquals(2.5, p.response.deadzoneDeg, 0.0)
        assertEquals(DwellConfig(), p.dwell)
        assertTrue(p.bindings.isNotEmpty())
    }

    @Test
    fun presetIdsAreUnique() {
        assertEquals(Presets.all().size, Presets.all().map { it.id }.toSet().size)
    }

    @Test
    fun anOldShooterPresetIsUpgradedToAimOnlyKeepingTheLayout() {
        val old = Presets.shooter().copy(
            bindings = listOf(TriggerBinding(TriggerKind.TWIST_LEFT, ActionType.SWITCH_MOVE_AIM)),
            aim = AimConfig(padX = 0.61, padY = 0.4),
            buttons = listOf(VirtualButton(1, "A", 0.9, 0.85)),
            linkedPackages = listOf("com.example.game"),
        )
        val up = Presets.upgrade(old)
        assertTrue(up.bindings.none { it.action == ActionType.SWITCH_MOVE_AIM })
        assertEquals(JoystickMode.AIM, up.joystick.mode)
        assertEquals(0.61, up.aim.padX, 0.0)
        assertEquals(Presets.shooter().aim.freezeOnFireMs, up.aim.freezeOnFireMs)
        assertEquals(old.buttons, up.buttons)
        assertEquals(old.linkedPackages, up.linkedPackages)
        // Up-to-date and user profiles are left alone.
        assertEquals(Presets.shooter(), Presets.upgrade(Presets.shooter()))
        val user = old.copy(id = "user.1")
        assertEquals(user, Presets.upgrade(user))
    }
}
