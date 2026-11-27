package com.sidequests.app.data.socialcomfort

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sidequests.app.domain.ComfortLevel
import com.sidequests.app.domain.SocialComfortSettings
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

interface SocialComfortStore {
    suspend fun load(userId: String): SocialComfortSettings
    suspend fun save(userId: String, settings: SocialComfortSettings)
}

private val Context.socialComfortDataStore: DataStore<Preferences> by preferencesDataStore(name = "social_comfort")

/**
 * Local storage (Sprint 4, BQ14): comfort level and the "Adapt my Smart Picks" choice, stored per
 * account in Jetpack DataStore. DataStore does its I/O off the main thread; an unreadable file
 * gives the defaults instead of crashing, and keys include the user id so accounts never mix.
 */
class DataStoreSocialComfortStore(context: Context) : SocialComfortStore {
    private val dataStore = context.applicationContext.socialComfortDataStore

    override suspend fun load(userId: String): SocialComfortSettings {
        val prefs = dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()
        return SocialComfortSettings(
            level = ComfortLevel.entries.firstOrNull { it.name == prefs[levelKey(userId)] } ?: ComfortLevel.High,
            adaptChoice = prefs[adaptKey(userId)],
        )
    }

    override suspend fun save(userId: String, settings: SocialComfortSettings) {
        dataStore.edit { prefs ->
            prefs[levelKey(userId)] = settings.level.name
            val choice = settings.adaptChoice
            if (choice == null) prefs.remove(adaptKey(userId)) else prefs[adaptKey(userId)] = choice
        }
    }

    private companion object {
        fun levelKey(userId: String) = stringPreferencesKey("comfort_level_$userId")
        fun adaptKey(userId: String) = booleanPreferencesKey("adapt_picks_$userId")
    }
}
