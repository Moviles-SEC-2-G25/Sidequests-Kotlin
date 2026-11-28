package com.sidequests.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuestStatisticsTest {
    @Test fun `statistics attribute real attempts and walking data to their account`() {
        val attempts = listOf(
            attempt("1", "park", "completed"),
            attempt("2", "park", "accepted"),
            attempt("3", "park", "completed"),
            attempt("4", "park", "skipped"),
            attempt("5", "retired", "abandoned"),
            attempt("6", "park", "completed", userId = "another-user"),
        )
        val snapshot = HistorySnapshot(
            userId = "owner",
            attempts = attempts,
            catalog = listOf(HistoryQuest("park", "Park", "Outdoors", 30)),
            stepTotals = mapOf("1" to 0, "3" to 24, "6" to 150, "unknown" to 250),
        )
        val stats = summarizeQuestStatistics(snapshot)
        assertEquals(4, stats.attempts)
        assertEquals(2, stats.completed)
        assertEquals(24L, stats.steps)
        assertEquals(setOf("park"), stats.unavailableQuestIds)
        assertEquals(CategoryStatistics("outdoors", 3, 2), stats.categories.getValue("outdoors"))
        assertEquals(CompletionLikelihood.Medium, stats.categories.getValue("outdoors").likelihood)
        assertNull(summarizeQuestStatistics(snapshot.copy(stepTotals = emptyMap())).steps)
        assertEquals(0L, summarizeQuestStatistics(snapshot.copy(stepTotals = mapOf("1" to 0))).steps)
    }

    @Test fun `likelihood uses minimum evidence and Flutter thresholds`() {
        assertEquals(CompletionLikelihood.NewForYou, CategoryStatistics("art", 2, 2).likelihood)
        assertEquals(CompletionLikelihood.High, CategoryStatistics("art", 10, 7).likelihood)
        assertEquals(CompletionLikelihood.Medium, CategoryStatistics("art", 5, 2).likelihood)
        assertEquals(CompletionLikelihood.Low, CategoryStatistics("art", 3, 1).likelihood)
    }

    private fun attempt(id: String, quest: String, status: String, userId: String = "owner") =
        HistoryAttempt(id, userId, quest, status, "2026-11-27T12:00:00Z")
}
