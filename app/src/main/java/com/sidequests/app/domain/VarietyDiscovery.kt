package com.sidequests.app.domain

import com.sidequests.app.model.Quest

/**
 * Sprint 4 opt-in Something Different browsing flow.
 * This is NOT the server-side BQ8 experiment and must never emit an
 * experimental recommendation_shown event.
 */
object VarietyDiscovery {
    fun select(
        catalogue: List<Quest>,
        availableMinutes: Int,
        maxBudget: Int,
        excludedIds: Set<String> = emptySet(),
        limit: Int = 3,
    ): List<Quest> {
        if (limit <= 0) return emptyList()
        val eligible = catalogue.asSequence()
            .filter { it.id !in excludedIds }
            .filter { it.durationMinutes <= availableMinutes && it.budgetAmount <= maxBudget }
            .sortedWith(compareBy<Quest> { it.durationMinutes }.thenBy { it.id })
            .toList()
        val categories = HashSet<String>()
        val firstPass = eligible.filter { categories.add(it.category.trim().lowercase()) }
        val selectedIds = firstPass.mapTo(HashSet()) { it.id }
        return (firstPass + eligible.filter { it.id !in selectedIds }).take(limit)
    }
}
