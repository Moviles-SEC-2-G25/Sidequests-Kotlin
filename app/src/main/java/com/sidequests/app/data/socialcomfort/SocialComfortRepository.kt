package com.sidequests.app.data.socialcomfort

import com.sidequests.app.data.history.HistorySource
import com.sidequests.app.domain.HistoryAttempt
import com.sidequests.app.domain.SocialComfortAdvisor
import com.sidequests.app.domain.SocialComfortInsights
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Caching (Sprint 4, BQ14): LRU of computed insights.
 *
 * The key is a fingerprint of everything the result depends on (account, every attempt's id and
 * status, and the exposure of every quest), so a hit can never be stale: when the history or the
 * catalogue changes, the fingerprint changes too. A Mutex makes it safe from several coroutines.
 */
class SocialComfortInsightsCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val mutex = Mutex()
    private val entries = object : LinkedHashMap<String, SocialComfortInsights>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SocialComfortInsights>?): Boolean =
            size > maxEntries
    }

    var hits: Int = 0
        private set
    var misses: Int = 0
        private set

    suspend fun getOrCompute(key: String, compute: suspend () -> SocialComfortInsights): SocialComfortInsights {
        mutex.withLock {
            entries[key]?.let { hits += 1; return it }
            misses += 1
        }
        val value = compute()
        mutex.withLock { entries[key] = value }
        return value
    }

    suspend fun size(): Int = mutex.withLock { entries.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 8

        fun fingerprint(userId: String, attempts: List<HistoryAttempt>, exposureByQuestId: Map<String, Int>): String {
            var hash = 17L
            for (attempt in attempts.sortedBy { it.id }) {
                hash = hash * 31 + attempt.id.hashCode()
                hash = hash * 31 + attempt.questId.hashCode()
                hash = hash * 31 + attempt.status.hashCode()
            }
            for ((questId, exposure) in exposureByQuestId.toSortedMap()) {
                hash = hash * 31 + questId.hashCode()
                hash = hash * 31 + exposure
            }
            return "$userId:${attempts.size}:${exposureByQuestId.size}:$hash"
        }
    }
}

/** What the Social comfort view shows. */
data class SocialComfortReport(
    val insights: SocialComfortInsights,
    /** True when the history came from Supabase right now, false when it is the saved copy. */
    val fresh: Boolean,
    /** False when there is no history at all (never loaded online on this phone). */
    val hasHistory: Boolean,
)

/**
 * BQ14 history + insights.
 *
 * Eventual connectivity: the history comes from Mi bitácora's [HistorySource] (Supabase when
 * online, the private saved snapshot when offline or when the request fails).
 * Multi-threading: history I/O runs on [io], the classification of every attempt on [compute],
 * so the main thread only receives the finished result.
 */
class SocialComfortRepository(
    private val history: HistorySource,
    private val cache: SocialComfortInsightsCache = SocialComfortInsightsCache(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun report(userId: String, online: Boolean, exposureByQuestId: Map<String, Int>): SocialComfortReport {
        val load = withContext(io) { history.load(userId, online) }
        val snapshot = load.snapshot?.takeIf { it.userId == userId }
            ?: return SocialComfortReport(SocialComfortInsights.EMPTY, fresh = false, hasHistory = false)
        val attempts = snapshot.attempts.filter { it.userId == userId }
        val key = SocialComfortInsightsCache.fingerprint(userId, attempts, exposureByQuestId)
        val insights = cache.getOrCompute(key) {
            withContext(compute) { SocialComfortAdvisor.insights(attempts, exposureByQuestId) }
        }
        return SocialComfortReport(insights, fresh = load.fresh, hasHistory = true)
    }
}
