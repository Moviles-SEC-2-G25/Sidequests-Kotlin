package com.sidequests.app.domain

import com.sidequests.app.model.Quest
import kotlin.math.abs

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
