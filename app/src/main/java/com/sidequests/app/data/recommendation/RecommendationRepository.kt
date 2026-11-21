package com.sidequests.app.data.recommendation

import com.sidequests.app.model.UserPreferences
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface RecommendationRepository {
    suspend fun recommend(
        availableMinutes: Int,
        preferences: UserPreferences,
        excludedQuestIds: Set<String> = emptySet(),
        limit: Int = 3,
    ): Result<List<RecommendationResult>>
}

data class RecommendationResult(
    val questId: String,
    val score: Double,
    val variant: String? = null,
    val rankPosition: Int? = null,
    val category: String? = null,
)

@Serializable
private data class RecommendationRowDto(
    @SerialName("quest_id")
    val questId: String,
    val score: Double,
    val variant: String? = null,
    @SerialName("rank_position") val rankPosition: Int? = null,
    val category: String? = null,
)

class SupabaseRecommendationRepository(
    private val client: SupabaseClient,
) : RecommendationRepository {

    override suspend fun recommend(
        availableMinutes: Int,
        preferences: UserPreferences,
        excludedQuestIds: Set<String>,
        limit: Int,
    ): Result<List<RecommendationResult>> = runCatching {
        withContext(Dispatchers.IO) {
            client.postgrest
                .rpc(
                    function = "recommend_quests",
                    parameters = buildJsonObject {
                        put("p_available_minutes", availableMinutes)
                        put("p_social_level", preferences.socialLevel.name.lowercase())
                        put(
                            "p_interests",
                            JsonArray(preferences.interests.sorted().map(::JsonPrimitive)),
                        )
                        put("p_location_mode", preferences.locationMode)
                        put(
                            "p_excluded_quest_ids",
                            JsonArray(excludedQuestIds.sorted().map(::JsonPrimitive)),
                        )
                        put("p_limit", limit)
                    },
                )
                .decodeList<RecommendationRowDto>()
                .map { RecommendationResult(it.questId, it.score, it.variant, it.rankPosition, it.category) }
        }
    }
}
