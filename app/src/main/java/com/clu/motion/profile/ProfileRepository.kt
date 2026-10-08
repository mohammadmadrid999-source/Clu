package com.clu.motion.profile

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.profileStore by preferencesDataStore(name = "profiles")

data class ProfileState(val profiles: List<ControlProfile>, val activeId: String) {
    val active: ControlProfile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
}

/** Profiles persisted as one JSON document in DataStore (atomic writes, Flow-based reads). */
class ProfileRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true // forward compatible with downgrades
        coerceInputValues = true // an enum value from a newer version falls back to the default
        encodeDefaults = true
    }

    val state: Flow<ProfileState> = context.profileStore.data
        .map { prefs ->
            val profiles = stored(prefs[KEY_PROFILES])
            ProfileState(profiles, prefs[KEY_ACTIVE] ?: profiles.first().id)
        }
        .distinctUntilChanged()

    suspend fun update(id: String, transform: (ControlProfile) -> ControlProfile) = edit { profiles ->
        profiles.map { if (it.id == id) transform(it) else it }
    }

    suspend fun select(id: String) {
        context.profileStore.edit { it[KEY_ACTIVE] = id }
    }

    suspend fun duplicate(source: ControlProfile, name: String): ControlProfile {
        val copy = source.copy(id = "user.${UUID.randomUUID()}", name = name, linkedPackages = emptyList())
        edit { it + copy }
        select(copy.id)
        return copy
    }

    suspend fun delete(id: String) = edit { profiles -> profiles.filterNot { it.id == id }.ifEmpty { Presets.all() } }

    /** Restores a built-in preset's tuning, keeping the user's layout and bindings. */
    suspend fun resetToPreset(id: String) {
        val preset = Presets.all().firstOrNull { it.id == id } ?: return
        update(id) { current ->
            preset.copy(
                buttons = current.buttons,
                bindings = current.bindings,
                joystick = preset.joystick.copy(
                    centerX = current.joystick.centerX,
                    centerY = current.joystick.centerY,
                    radiusFraction = current.joystick.radiusFraction,
                ),
                aim = preset.aim.copy(
                    padX = current.aim.padX,
                    padY = current.aim.padY,
                    padRadiusFraction = current.aim.padRadiusFraction,
                ),
                linkedPackages = current.linkedPackages,
            )
        }
    }

    private suspend fun edit(transform: (List<ControlProfile>) -> List<ControlProfile>) {
        context.profileStore.edit { prefs ->
            prefs[KEY_PROFILES] = json.encodeToString(transform(stored(prefs[KEY_PROFILES])))
        }
    }

    /** Stored profiles, plus any built-in preset added since they were saved (presets can't be deleted). */
    private fun stored(raw: String?): List<ControlProfile> {
        val profiles = raw?.let(::decode)?.takeIf { it.isNotEmpty() }?.map(Presets::upgrade) ?: return Presets.all()
        val missing = Presets.all().filter { preset -> profiles.none { it.id == preset.id } }
        return profiles + missing
    }

    private fun decode(raw: String): List<ControlProfile>? = try {
        json.decodeFromString<List<ControlProfile>>(raw)
    } catch (e: SerializationException) {
        Log.e(TAG, "Stored profiles unreadable; falling back to presets", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "Stored profiles invalid; falling back to presets", e)
        null
    }

    private companion object {
        const val TAG = "ProfileRepository"
        val KEY_PROFILES = stringPreferencesKey("profiles_json")
        val KEY_ACTIVE = stringPreferencesKey("active_profile_id")
    }
}
