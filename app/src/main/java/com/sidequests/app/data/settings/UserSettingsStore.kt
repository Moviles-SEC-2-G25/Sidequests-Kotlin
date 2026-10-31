package com.sidequests.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sidequests.app.context.TimeOfDay
import com.sidequests.app.domain.DifficultyOutcome
import com.sidequests.app.domain.TimeChoice
import com.sidequests.app.model.AbandonmentReason
import com.sidequests.app.model.QuestDifficulty
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

/** Device-level settings: they belong to the phone, not to the signed-in user (#31). */
data class ThemeSettings(
    val darkMode: Boolean = false,
    val autoThemeEnabled: Boolean = false,
)

/** Per-user history used by the smart features (#31). */
data class SmartHistory(
    val timeChoices: List<TimeChoice> = emptyList(),
    val difficultyOutcomes: List<DifficultyOutcome> = emptyList(),
    val difficultySuggestionDismissedAt: Int? = null,
)

interface SettingsStore {
    suspend fun loadTheme(): ThemeSettings
    suspend fun saveTheme(theme: ThemeSettings)
    suspend fun loadHistory(userId: String): SmartHistory
    suspend fun saveHistory(userId: String, history: SmartHistory)
}

private val Context.userSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

/**
 * Local storage (Sprint 3, #31): Jetpack DataStore (Preferences).
 * DataStore does its I/O on a background dispatcher and exposes coroutines/Flow, so the UI thread
 * is never blocked. If the file cannot be read, the app starts with defaults instead of crashing.
 * The theme is stored once per device; the smart-feature history is stored per user id, so two
 * accounts on the same phone never mix their data.
 */
class DataStoreSettingsStore(context: Context) : SettingsStore {
    private val dataStore = context.applicationContext.userSettingsDataStore

    private suspend fun read(): Preferences = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .first()

    override suspend fun loadTheme(): ThemeSettings {
        val prefs = read()
        return ThemeSettings(
            darkMode = prefs[DARK_MODE] ?: false,
            autoThemeEnabled = prefs[AUTO_THEME] ?: false,
        )
    }

    override suspend fun saveTheme(theme: ThemeSettings) {
        dataStore.edit { prefs ->
            prefs[DARK_MODE] = theme.darkMode
            prefs[AUTO_THEME] = theme.autoThemeEnabled
        }
    }

    override suspend fun loadHistory(userId: String): SmartHistory {
        val prefs = read()
        return SmartHistory(
            timeChoices = SettingsCodec.decodeTimeChoices(prefs[timeChoicesKey(userId)]),
            difficultyOutcomes = SettingsCodec.decodeOutcomes(prefs[outcomesKey(userId)]),
            difficultySuggestionDismissedAt = prefs[dismissedKey(userId)],
        )
    }

    override suspend fun saveHistory(userId: String, history: SmartHistory) {
        dataStore.edit { prefs ->
            prefs[timeChoicesKey(userId)] = SettingsCodec.encodeTimeChoices(history.timeChoices)
            prefs[outcomesKey(userId)] = SettingsCodec.encodeOutcomes(history.difficultyOutcomes)
            val dismissed = history.difficultySuggestionDismissedAt
            if (dismissed == null) prefs.remove(dismissedKey(userId)) else prefs[dismissedKey(userId)] = dismissed
        }
    }

    private companion object {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val AUTO_THEME = booleanPreferencesKey("auto_theme")
        fun timeChoicesKey(userId: String) = stringPreferencesKey("time_choices_$userId")
        fun outcomesKey(userId: String) = stringPreferencesKey("difficulty_outcomes_$userId")
        fun dismissedKey(userId: String) = intPreferencesKey("difficulty_dismissed_at_$userId")
    }
}

/**
 * Compact text format for the histories (pure Kotlin, unit tested). Unknown or corrupted entries
 * are skipped instead of failing the whole load.
 *  - time choices: "MORNING:15;EVENING:45"
 *  - outcomes:     "Medium:1:-;Easy:0:TooDifficult" (difficulty:completed:reason)
 */
object SettingsCodec {
    fun encodeTimeChoices(choices: List<TimeChoice>): String =
        choices.joinToString(";") { "${it.timeOfDay.name}:${it.minutes}" }

    fun decodeTimeChoices(raw: String?): List<TimeChoice> =
        raw.orEmpty().split(';').mapNotNull { part ->
            val (time, minutes) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val timeOfDay = TimeOfDay.entries.firstOrNull { it.name == time } ?: return@mapNotNull null
            TimeChoice(timeOfDay, minutes.toIntOrNull() ?: return@mapNotNull null)
        }

    fun encodeOutcomes(outcomes: List<DifficultyOutcome>): String =
        outcomes.joinToString(";") { "${it.difficulty.name}:${if (it.completed) 1 else 0}:${it.reason?.name ?: "-"}" }

    fun decodeOutcomes(raw: String?): List<DifficultyOutcome> =
        raw.orEmpty().split(';').mapNotNull { part ->
            val fields = part.split(':').takeIf { it.size == 3 } ?: return@mapNotNull null
            val difficulty = QuestDifficulty.entries.firstOrNull { it.name == fields[0] } ?: return@mapNotNull null
            val completed = when (fields[1]) { "1" -> true; "0" -> false; else -> return@mapNotNull null }
            val reason = AbandonmentReason.entries.firstOrNull { it.name == fields[2] }
            DifficultyOutcome(difficulty, completed, reason)
        }
}
