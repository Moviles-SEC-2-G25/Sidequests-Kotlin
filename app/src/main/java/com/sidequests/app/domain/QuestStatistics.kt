package com.sidequests.app.domain

enum class CompletionLikelihood(val label: String) {
    High("High completion likelihood"),
    Medium("Medium completion likelihood"),
    Low("Low completion likelihood"),
    NewForYou("New for you"),
}

data class CategoryStatistics(val category: String, val attempts: Int, val completed: Int) {
    val rate: Double get() = completed.toDouble() / attempts
    val likelihood: CompletionLikelihood get() = when {
        attempts < 3 -> CompletionLikelihood.NewForYou
        rate >= .7 -> CompletionLikelihood.High
        rate >= .4 -> CompletionLikelihood.Medium
        else -> CompletionLikelihood.Low
    }
}

data class QuestStatistics(
    val attempts: Int,
    val completed: Int,
    val steps: Long?,
    val categories: Map<String, CategoryStatistics>,
    val unavailableQuestIds: Set<String>,
    val completedQuestIds: Set<String>,
)

fun summarizeQuestStatistics(snapshot: HistorySnapshot): QuestStatistics {
    val attempts = snapshot.attempts.filter { it.userId == snapshot.userId && it.status != "skipped" }
    val categoryByQuest = snapshot.catalog.associate { it.id to it.category.trim().lowercase() }
    val attemptsByCategory = attempts.mapNotNull { attempt ->
        categoryByQuest[attempt.questId]?.let { it to attempt }
    }.groupBy({ it.first }, { it.second })
    val categories = attemptsByCategory.mapValues { (category, rows) ->
        CategoryStatistics(category, rows.size, rows.count { it.status == "completed" })
    }
    val steps = attempts.mapNotNull { snapshot.stepTotals[it.id] }
    return QuestStatistics(
        attempts = attempts.size,
        completed = attempts.count { it.status == "completed" },
        steps = steps.takeIf { it.isNotEmpty() }?.sumOf { it.toLong() },
        categories = categories,
        unavailableQuestIds = attempts.filter { it.status in setOf("accepted", "in_progress", "completed") }
            .mapTo(mutableSetOf()) { it.questId },
        completedQuestIds = attempts.filter { it.status == "completed" }.mapTo(mutableSetOf()) { it.questId },
    )
}
