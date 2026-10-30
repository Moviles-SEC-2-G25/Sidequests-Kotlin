package com.sidequests.app.data.recommendation

import com.sidequests.app.model.UserPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedRecommendationRepositoryTest {
    private class FakeRemote : RecommendationRepository {
        var calls = 0
        var fail = false
        override suspend fun recommend(
            availableMinutes: Int,
            preferences: UserPreferences,
            excludedQuestIds: Set<String>,
            limit: Int,
        ): Result<List<RecommendationResult>> {
            calls++
            return if (fail) Result.failure(IllegalStateException("offline"))
            else Result.success(listOf(RecommendationResult("quest-$availableMinutes", 1.0)))
        }
    }

    private var now = 0L
    private val prefs = UserPreferences()

    @Test
    fun `same request within the TTL is served from the cache`() = runTest {
        val remote = FakeRemote()
        val cache = CachedRecommendationRepository(remote, ttlMillis = 1_000, clock = { now })
        cache.recommend(30, prefs)
        cache.recommend(30, prefs)
        assertEquals(1, remote.calls)
        assertEquals(CachedRecommendationRepository.Source.CACHE, cache.lastSource)
        now = 2_000
        cache.recommend(30, prefs)
        assertEquals(2, remote.calls)
    }

    @Test
    fun `least recently used entry is evicted`() = runTest {
        val remote = FakeRemote()
        val cache = CachedRecommendationRepository(remote, maxEntries = 2, clock = { now })
        cache.recommend(15, prefs)
        cache.recommend(30, prefs)
        cache.recommend(15, prefs) // 15 becomes most recently used
        cache.recommend(45, prefs) // evicts 30
        assertEquals(2, cache.size())
        val callsBefore = remote.calls
        cache.recommend(15, prefs)
        assertEquals(callsBefore, remote.calls)
        cache.recommend(30, prefs)
        assertEquals(callsBefore + 1, remote.calls)
    }

    @Test
    fun `stale entry is returned when the network fails`() = runTest {
        val remote = FakeRemote()
        val cache = CachedRecommendationRepository(remote, ttlMillis = 1_000, clock = { now })
        cache.recommend(30, prefs)
        now = 5_000
        remote.fail = true
        val result = cache.recommend(30, prefs)
        assertTrue(result.isSuccess)
        assertEquals("quest-30", result.getOrThrow().single().questId)
        assertEquals(CachedRecommendationRepository.Source.STALE_CACHE, cache.lastSource)
    }
}
