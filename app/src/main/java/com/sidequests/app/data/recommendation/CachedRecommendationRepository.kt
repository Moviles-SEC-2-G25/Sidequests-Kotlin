package com.sidequests.app.data.recommendation

import com.sidequests.app.model.UserPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caching (Sprint 3, #23): LRU cache with TTL in front of the Smart Picks (BQ5) RPC.
 *
 * - Key = everything that changes the answer (time, social level, interests, location mode,
 *   excluded quests, limit). Same request within [ttlMillis] → served from memory, no network call.
 * - At most [maxEntries] requests are kept; the least recently used one is evicted
 *   (LinkedHashMap in access order).
 * - Eventual connectivity: if the network call fails, a stale entry for the same key is returned
 *   instead of an error ("stale-if-error"), so Smart Picks keep working offline.
 * - A Mutex makes it safe to call from several coroutines at the same time.
 */
class CachedRecommendationRepository(
    private val delegate: RecommendationRepository,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationRepository {

    private data class Key(
        val availableMinutes: Int,
        val socialLevel: String,
        val interests: List<String>,
        val locationMode: String,
        val excludedQuestIds: List<String>,
        val limit: Int,
    )

    private class Entry(val results: List<RecommendationResult>, val storedAtMillis: Long)

    private val mutex = Mutex()
    private val entries = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > maxEntries
    }

    /** Where the last answer came from, shown in the Smart Picks card. */
    @Volatile var lastSource: Source = Source.NETWORK
        private set

    var hits: Int = 0
        private set
    var misses: Int = 0
        private set

    enum class Source { NETWORK, CACHE, STALE_CACHE }

    override suspend fun recommend(
        availableMinutes: Int,
        preferences: UserPreferences,
        excludedQuestIds: Set<String>,
        limit: Int,
    ): Result<List<RecommendationResult>> {
        val key = Key(
            availableMinutes = availableMinutes,
            socialLevel = preferences.socialLevel.name,
            interests = preferences.interests.sorted(),
            locationMode = preferences.locationMode,
            excludedQuestIds = excludedQuestIds.sorted(),
            limit = limit,
        )

        val cached = mutex.withLock { entries[key] }
        if (cached != null && clock() - cached.storedAtMillis < ttlMillis) {
            mutex.withLock { hits++ }
            lastSource = Source.CACHE
            return Result.success(cached.results)
        }

        mutex.withLock { misses++ }
        val fresh = delegate.recommend(availableMinutes, preferences, excludedQuestIds, limit)
        fresh.onSuccess { results ->
            mutex.withLock { entries[key] = Entry(results, clock()) }
            lastSource = Source.NETWORK
            return fresh
        }

        // Network failed: fall back to the last known answer for the same request, if any.
        if (cached != null) {
            lastSource = Source.STALE_CACHE
            return Result.success(cached.results)
        }
        return fresh
    }

    suspend fun size(): Int = mutex.withLock { entries.size }

    suspend fun clear() = mutex.withLock { entries.clear() }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 16
        const val DEFAULT_TTL_MILLIS = 10 * 60 * 1000L
    }
}
