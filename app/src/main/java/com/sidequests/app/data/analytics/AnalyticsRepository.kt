package com.sidequests.app.data.analytics

import com.sidequests.app.model.Quest
import com.sidequests.app.model.UserPreferences
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

interface AnalyticsRepository {
    suspend fun track(
        expectedUserId: String?,
        sessionId: String,
        eventType: String,
        availableMinutes: Int,
        preferences: UserPreferences,
        quest: Quest? = null,
        metadata: JsonObject = JsonObject(emptyMap()),
    ): Result<Unit>
}

@Serializable
private data class AnalyticsEventInsertDto(
    @SerialName("user_id")
    val userId: String,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("event_type")
    val eventType: String,
    @SerialName("quest_id")
    val questId: String? = null,
    val category: String? = null,
    @SerialName("available_minutes")
    val availableMinutes: Int? = null,
    @SerialName("social_level")
    val socialLevel: String? = null,
    @SerialName("location_mode")
    val locationMode: String? = null,
    @SerialName("quest_duration_minutes")
    val questDurationMinutes: Int? = null,
    @SerialName("quest_difficulty")
    val questDifficulty: String? = null,
    @SerialName("estimated_cost")
    val estimatedCost: Int? = null,
    @SerialName("distance_meters")
    val distanceMeters: Int? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
)

class SupabaseAnalyticsRepository(
    private val client: SupabaseClient,
) : AnalyticsRepository {

    override suspend fun track(
        expectedUserId: String?,
        sessionId: String,
        eventType: String,
        availableMinutes: Int,
        preferences: UserPreferences,
        quest: Quest?,
        metadata: JsonObject,
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireNotNull(expectedUserId) { "No account was attached when the event occurred." }
            check(client.auth.currentUserOrNull()?.id == userId) { "Analytics account changed before delivery." }

            client.from("analytics_events").insert(
                AnalyticsEventInsertDto(
                    userId = userId,
                    sessionId = sessionId,
                    eventType = eventType,
                    questId = quest?.id,
                    category = quest?.category,
                    availableMinutes = availableMinutes,
                    socialLevel = preferences.socialLevel.name.lowercase(),
                    locationMode = preferences.locationMode,
                    questDurationMinutes = quest?.durationMinutes,
                    questDifficulty = quest?.difficulty?.name?.lowercase(),
                    estimatedCost = quest?.budgetAmount,
                    distanceMeters = quest?.estimatedDistanceMeters(),
                    metadata = metadata,
                )
            )
        }
    }
}
