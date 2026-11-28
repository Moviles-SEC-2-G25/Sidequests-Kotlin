package com.sidequests.app.domain

import com.sidequests.app.model.Quest
import com.sidequests.app.model.SidequestsUiState
import kotlin.math.abs

internal fun completionSuggestions(
    completed: Quest,
    catalog: List<Quest>,
    state: SidequestsUiState,
    history: QuestStatistics?,
    rating: Int,
): List<Quest> {
    val unavailable = history?.completedQuestIds.orEmpty().toMutableSet()
    history?.activeQuestIdsByAttempt?.forEach { (attemptId, questId) ->
        if (attemptId !in state.abandonedAttemptIds) unavailable.add(questId)
    }
    unavailable.addAll(state.attemptIdByQuest.keys)
    catalog.forEach { candidate ->
        if (candidate.steps.isNotEmpty() &&
            (state.progressByQuest[candidate.id]?.completedSteps?.size ?: 0) >= candidate.steps.size) {
            unavailable.add(candidate.id)
        }
    }
    return similarQuests(completed, catalog, unavailable, rating, state.availableTime)
}

fun similarQuests(
    completed: Quest,
    catalog: List<Quest>,
    unavailableQuestIds: Set<String>,
    rating: Int,
    maxMinutes: Int,
): List<Quest> {
    val tags = completed.tags.toSet()
    return catalog.asSequence()
        .filter { it.id != completed.id && it.id !in unavailableQuestIds && it.durationMinutes <= maxMinutes }
        .map { quest ->
            val categoryScore = when {
                quest.category != completed.category || rating == 0 -> 0
                rating <= 2 -> -3
                else -> 3
            }
            val tagScore = quest.tags.toSet().count { it in tags }
            val timeScore = if (abs(quest.durationMinutes - completed.durationMinutes) <= 15) 2 else 0
            val difficultyScore = if (quest.difficulty == completed.difficulty) 1 else 0
            quest to (categoryScore + tagScore + timeScore + difficultyScore)
        }
        .sortedWith(compareByDescending<Pair<Quest, Int>> { it.second }.thenBy { it.first.durationMinutes })
        .take(3)
        .map { it.first }
        .toList()
}
