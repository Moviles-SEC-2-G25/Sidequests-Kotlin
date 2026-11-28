package com.sidequests.app.data.history

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.first

private val Context.walkingTotalsDataStore by preferencesDataStore(name = "quest_walking_totals")

/** Walking totals live on the device and belong to an authenticated account and attempt. */
class WalkingTotalsStore(context: Context) {
    private val dataStore = context.applicationContext.walkingTotalsDataStore

    suspend fun read(userId: String): Map<String, Int> = try {
        val prefix = "$userId:"
        dataStore.data.first().asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith(prefix) && value is Int) key.name.removePrefix(prefix) to value else null
        }.toMap()
    } catch (_: IOException) {
        emptyMap()
    }

    suspend fun add(userId: String, attemptId: String, delta: Int) {
        val key = intPreferencesKey("$userId:$attemptId")
        dataStore.edit { preferences ->
            val total = preferences[key] ?: 0
            preferences[key] = (total.toLong() + delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
}
