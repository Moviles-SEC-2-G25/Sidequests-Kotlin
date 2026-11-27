package com.sidequests.app.data.socialcomfort

import com.sidequests.app.data.history.HistoryLoad
import com.sidequests.app.data.history.HistorySource
import com.sidequests.app.domain.HistoryAttempt
import com.sidequests.app.domain.HistorySnapshot
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialComfortRepositoryTest {
    private fun attempt(id: String, user: String, quest: String, status: String) =
        HistoryAttempt(id = id, userId = user, questId = quest, status = status, acceptedAt = "2026-11-01T10:00:00Z")

    @Test fun `offline uses the saved snapshot and repeated reads hit the cache`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val onlineFlags = mutableListOf<Boolean>()
        val source = HistorySource { userId, online ->
            onlineFlags += online
            HistoryLoad(HistorySnapshot(userId, listOf(attempt("a", userId, "q1", "completed")), emptyList()), fresh = false)
        }
        val cache = SocialComfortInsightsCache()
        val repo = SocialComfortRepository(source, cache, io = dispatcher, compute = dispatcher)

        val first = repo.report("alice", online = false, exposureByQuestId = mapOf("q1" to 0))
        val second = repo.report("alice", online = false, exposureByQuestId = mapOf("q1" to 0))

        assertEquals(listOf(false, false), onlineFlags)
        assertFalse(first.fresh)
        assertTrue(first.hasHistory)
        assertEquals(1, first.insights.outcomes[0].finished)
        assertEquals(first.insights, second.insights)
        assertEquals(1, cache.misses)
        assertEquals(1, cache.hits)
    }

    @Test fun `a changed history is never served from the cache`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var status = "abandoned"
        val source = HistorySource { userId, _ ->
            HistoryLoad(HistorySnapshot(userId, listOf(attempt("a", userId, "q1", status)), emptyList()), fresh = true)
        }
        val cache = SocialComfortInsightsCache()
        val repo = SocialComfortRepository(source, cache, io = dispatcher, compute = dispatcher)

        assertEquals(1, repo.report("alice", true, mapOf("q1" to 2)).insights.outcomes[2].left)
        status = "completed"
        assertEquals(1, repo.report("alice", true, mapOf("q1" to 2)).insights.outcomes[2].finished)
        assertEquals(2, cache.misses)
    }

    @Test fun `no saved history and other accounts' rows are ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val empty = SocialComfortRepository(HistorySource { _, _ -> HistoryLoad(null, false) }, io = dispatcher, compute = dispatcher)
        assertFalse(empty.report("alice", false, emptyMap()).hasHistory)

        val mixed = SocialComfortRepository(
            HistorySource { _, _ -> HistoryLoad(HistorySnapshot("bob", listOf(attempt("b", "bob", "q1", "completed")), emptyList()), true) },
            io = dispatcher, compute = dispatcher,
        )
        assertFalse(mixed.report("alice", true, mapOf("q1" to 0)).hasHistory)
    }

    @Test fun `lru keeps at most max entries`() = runTest {
        val cache = SocialComfortInsightsCache(maxEntries = 2)
        val value = com.sidequests.app.domain.SocialComfortInsights.EMPTY
        cache.getOrCompute("a") { value }
        cache.getOrCompute("b") { value }
        cache.getOrCompute("c") { value }
        assertEquals(2, cache.size())
    }
}
