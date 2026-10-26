package com.sidequests.app.data

import com.sidequests.app.data.remote.QuestDto
import com.sidequests.app.data.remote.QuestStepDto
import com.sidequests.app.data.remote.toDomain
import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.Quest
import com.sidequests.app.model.UserPreferences
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class QuestCatalogueOrigin { Remote, Saved, Sample }

interface SidequestsRepository {
    fun catalogueOrigin(): QuestCatalogueOrigin = QuestCatalogueOrigin.Sample
    suspend fun refresh(): Result<Int>
    fun allQuests(): List<Quest>
    fun questById(id: String): Quest
    fun categories(): List<String>
    fun recommendations(
        availableMinutes: Int,
        preferences: UserPreferences,
        skippedIds: Set<String>,
        completedIds: Set<String>,
        inProgressIds: Set<String>,
    ): List<Quest>
}

class InMemorySidequestsRepository : SidequestsRepository {
    override suspend fun refresh(): Result<Int> = Result.success(QuestSeed.quests.size)

    override fun allQuests(): List<Quest> = QuestSeed.quests

    override fun questById(id: String): Quest =
        QuestSeed.quests.firstOrNull { it.id == id } ?: QuestSeed.quests.first()

    override fun categories(): List<String> = QuestSeed.categories

    override fun recommendations(
        availableMinutes: Int,
        preferences: UserPreferences,
        skippedIds: Set<String>,
        completedIds: Set<String>,
        inProgressIds: Set<String>,
    ): List<Quest> = rankRecommendations(
        quests = QuestSeed.quests,
        availableMinutes = availableMinutes,
        preferences = preferences,
        skippedIds = skippedIds,
        completedIds = completedIds,
        inProgressIds = inProgressIds,
    )
}

class SupabaseSidequestsRepository(
    private val client: SupabaseClient,
    private val catalogueStore: LocalQuestCatalogueStore? = null,
) : SidequestsRepository {

    @Volatile
    private var cache: List<Quest> = QuestSeed.quests

    @Volatile
    private var origin: QuestCatalogueOrigin = QuestCatalogueOrigin.Sample

    override fun catalogueOrigin(): QuestCatalogueOrigin = origin

    override suspend fun refresh(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val questRows = client.from("quests")
                .select {
                    filter {
                        eq("is_active", true)
                    }
                }
                .decodeList<QuestDto>()

            val stepRows = client.from("quest_steps")
                .select()
                .decodeList<QuestStepDto>()

            check(questRows.isNotEmpty()) {
                "Remote quest catalogue is empty."
            }

            val snapshot = QuestCatalogueSnapshot(quests = questRows, steps = stepRows)
            val loaded = mapCatalogue(snapshot)
            cache = loaded
            origin = QuestCatalogueOrigin.Remote

            // A full remote response remains usable even if the device is out of storage.
            // In that case no claim is made that the new snapshot was persisted.
            runCatching { catalogueStore?.save(snapshot) }

            Result.success(loaded.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A saved snapshot is preferred to bundled sample quests.
            // Read on IO, not on Compose's main thread.
            runCatching { catalogueStore?.read()?.let(::mapCatalogue) }
                .getOrNull()
                ?.let { saved ->
                    cache = saved
                    origin = QuestCatalogueOrigin.Saved
                }
            Result.failure(error)
        }
    }

    private fun mapCatalogue(snapshot: QuestCatalogueSnapshot): List<Quest> {
        val localById = QuestSeed.quests.associateBy { it.id }
        val stepsByQuest = snapshot.steps.groupBy { it.questId }
        return snapshot.quests.map { dto ->
            dto.toDomain(
                remoteSteps = stepsByQuest[dto.id].orEmpty(),
                fallback = localById[dto.id],
            )
        }.sortedBy { it.title }
    }

    override fun allQuests(): List<Quest> = cache

    override fun questById(id: String): Quest =
        cache.firstOrNull { it.id == id }
            ?: QuestSeed.quests.firstOrNull { it.id == id }
            ?: cache.firstOrNull()
            ?: QuestSeed.quests.first()

    override fun categories(): List<String> =
        listOf("All") + cache.map { it.category }.distinct().sorted()

    override fun recommendations(
        availableMinutes: Int,
        preferences: UserPreferences,
        skippedIds: Set<String>,
        completedIds: Set<String>,
        inProgressIds: Set<String>,
    ): List<Quest> = rankRecommendations(
        quests = cache,
        availableMinutes = availableMinutes,
        preferences = preferences,
        skippedIds = skippedIds,
        completedIds = completedIds,
        inProgressIds = inProgressIds,
    )
}

private fun rankRecommendations(
    quests: List<Quest>,
    availableMinutes: Int,
    preferences: UserPreferences,
    skippedIds: Set<String>,
    completedIds: Set<String>,
    inProgressIds: Set<String>,
): List<Quest> {
    return quests
        .asSequence()
        .filterNot {
            it.id in completedIds ||
                it.id in skippedIds ||
                it.id in inProgressIds
        }
        .map { quest ->
            var score = 0.0

            if (quest.durationMinutes <= availableMinutes) score += 3

            if (
                preferences.interests.isEmpty() ||
                quest.category in preferences.interests ||
                quest.tags.any {
                    it.replaceFirstChar(Char::uppercase) in preferences.interests
                }
            ) {
                score += 2
            }

            if (quest.difficulty == preferences.difficulty) score += 2

            if (
                preferences.locationMode == "all" ||
                (preferences.locationMode == "gps" && quest.location == LocationMode.Gps) ||
                (preferences.locationMode == "anywhere" && quest.location == LocationMode.Anywhere)
            ) {
                score += 2
            }

            if (quest.budgetAmount <= preferences.budgetMax) score += 1
            if (quest.isNew) score += 1
            if (quest.isSponsored) score += 0.5

            quest to score
        }
        .sortedByDescending { it.second }
        .take(4)
        .map { it.first }
        .toList()
}
