package com.sidequests.app.telemetry

/** A return to the app after a long background period with an active Nearby (GPS) quest. */
data class ResumeInfo(
    val resumedAtMillis: Long,
    val backgroundMillis: Long,
    val questId: String,
)

/**
 * BQ1 (#21): detects "the user brings the app back to the foreground after more than 10 minutes in
 * the background while a location-based (Nearby) quest is active".
 *
 * Failures that happen during the following [windowMillis] are attributed to that resume.
 * Pure Kotlin (wall-clock millis are passed in) so it can be unit tested; the background start is
 * persisted by [Bq1Telemetry] so a resume is also detected if Android killed the process meanwhile.
 */
class ResumeSessionTracker(
    private val thresholdMillis: Long = TEN_MINUTES,
    private val windowMillis: Long = FIVE_MINUTES,
) {
    var backgroundSinceMillis: Long? = null
        private set
    var backgroundQuestId: String? = null
        private set
    var lastResume: ResumeInfo? = null
        private set

    fun onBackground(nowMillis: Long, activeNearbyQuestId: String?) {
        backgroundSinceMillis = nowMillis
        backgroundQuestId = activeNearbyQuestId
    }

    /** Restores a background period saved before the process was killed. */
    fun restore(backgroundSinceMillis: Long?, questId: String?) {
        this.backgroundSinceMillis = backgroundSinceMillis
        this.backgroundQuestId = questId
    }

    /** Returns the resume when it qualifies for BQ1, otherwise null. */
    fun onForeground(nowMillis: Long): ResumeInfo? {
        val since = backgroundSinceMillis ?: return null
        val questId = backgroundQuestId
        backgroundSinceMillis = null
        backgroundQuestId = null
        val elapsed = nowMillis - since
        if (questId == null || elapsed < thresholdMillis) return null
        return ResumeInfo(resumedAtMillis = nowMillis, backgroundMillis = elapsed, questId = questId)
            .also { lastResume = it }
    }

    /** Debug/demo helper: behaves as if the app had just returned from [backgroundMillis] away. */
    fun simulateResume(nowMillis: Long, backgroundMillis: Long, questId: String): ResumeInfo =
        ResumeInfo(nowMillis, backgroundMillis, questId).also { lastResume = it }

    /** The resume that is still "recent" at [nowMillis], used to tag failures. */
    fun activeResume(nowMillis: Long): ResumeInfo? =
        lastResume?.takeIf { nowMillis - it.resumedAtMillis in 0..windowMillis }

    companion object {
        const val TEN_MINUTES = 10 * 60 * 1000L
        const val FIVE_MINUTES = 5 * 60 * 1000L
    }
}
