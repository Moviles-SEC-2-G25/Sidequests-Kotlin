package com.sidequests.app.domain

import com.sidequests.app.model.Quest
import com.sidequests.app.model.SocialLevel

/**
 * BQ14 (Sprint 4, Type 5): social comfort personalization.
 *
 * Every quest has a social exposure: 0 = solo, 1 = social (talking to strangers, public
 * activity), 2 = group. The user picks the highest exposure they are comfortable with, and Smart
 * Picks can be adapted to hide quests above it.
 */
enum class ComfortLevel(val maxExposure: Int, val label: String, val description: String) {
    Low(0, "Low", "Solo quests only. Nothing that asks you to talk to strangers or join a group."),
    Medium(1, "Medium", "Solo and social quests, but no group challenges."),
    High(2, "High", "Anything goes: solo, social and group quests."),
    ;

    /** BQ14 population: users with low willingness to join socially demanding activities. */
    val lowWillingness: Boolean get() = this != High
}

object SocialExposure {
    const val SOLO = 0
    const val SOCIAL = 1
    const val GROUP = 2

    fun of(quest: Quest): Int = when {
        quest.isGroup || quest.socialLevel == SocialLevel.Group -> GROUP
        quest.socialLevel == SocialLevel.Social -> SOCIAL
        else -> SOLO
    }

    fun label(exposure: Int): String = when (exposure) {
        SOLO -> "Solo"
        SOCIAL -> "Social"
        else -> "Group"
    }
}

/** What the user chose. [adaptChoice] is null while the user has never touched the switch. */
data class SocialComfortSettings(
    val level: ComfortLevel = ComfortLevel.High,
    val adaptChoice: Boolean? = null,
)

enum class AdaptationSource { User, Default }

data class SocialAdaptation(val active: Boolean, val source: AdaptationSource)

/** Finished vs left attempts for one exposure level. */
data class ExposureOutcome(val exposure: Int, val finished: Int, val left: Int) {
    val closed: Int get() = finished + left
}

data class SocialComfortInsights(
    val outcomes: List<ExposureOutcome>,
    /** Attempts whose quest is not in the catalogue (exposure unknown), never guessed. */
    val unknownAttempts: Int,
    /** Level suggested by the user's own history, or null when there is nothing to suggest. */
    val suggestion: ComfortLevel?,
) {
    val totalClosed: Int get() = outcomes.sumOf { it.closed }

    companion object {
        val EMPTY = SocialComfortInsights(
            outcomes = listOf(SocialExposure.SOLO, SocialExposure.SOCIAL, SocialExposure.GROUP).map { ExposureOutcome(it, 0, 0) },
            unknownAttempts = 0,
            suggestion = null,
        )
    }
}

object SocialComfortAdvisor {
    const val ARM_ADAPT = "adapt_default"
    const val ARM_CONTROL = "control"
    private const val MIN_CLOSED_FOR_SUGGESTION = 2
    private const val ABANDON_RATE_FOR_SUGGESTION = 0.5

    /**
     * Stable 50/50 experiment arm by account id (32-bit FNV-1a). The arm only sets the default of
     * the "Adapt my Smart Picks" switch; the user's own choice always wins.
     */
    fun experimentArm(userId: String): String {
        var hash = 0x811C9DC5.toInt()
        for (byte in userId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return if ((hash.toLong() and 0xFFFFFFFFL) % 2L == 0L) ARM_ADAPT else ARM_CONTROL
    }

    /** User choice wins; otherwise the arm decides. At High there is nothing to hide. */
    fun adaptation(settings: SocialComfortSettings, arm: String): SocialAdaptation {
        val source = if (settings.adaptChoice != null) AdaptationSource.User else AdaptationSource.Default
        val wanted = settings.adaptChoice ?: (arm == ARM_ADAPT)
        return SocialAdaptation(active = wanted && settings.level != ComfortLevel.High, source = source)
    }

    /** Quests above the comfort level; sent as exclusions to the server and the local fallback. */
    fun excludedIds(quests: List<Quest>, level: ComfortLevel): Set<String> =
        quests.filter { SocialExposure.of(it) > level.maxExposure }.mapTo(mutableSetOf()) { it.id }

    /** Removes quests above the level and keeps the given (server) order untouched. */
    fun adapt(quests: List<Quest>, level: ComfortLevel, active: Boolean): List<Quest> =
        if (!active) quests else quests.filter { SocialExposure.of(it) <= level.maxExposure }

    /**
     * Classifies the user's closed attempts (completed / abandoned) by exposure.
     * Suggests the level just below the lowest exposure where at least half of 2+ closed
     * attempts were abandoned.
     */
    fun insights(attempts: List<HistoryAttempt>, exposureByQuestId: Map<String, Int>): SocialComfortInsights {
        val finished = IntArray(3)
        val left = IntArray(3)
        var unknown = 0
        for (attempt in attempts) {
            val status = attempt.status.lowercase()
            val isFinished = status == "completed"
            val isLeft = status == "abandoned"
            if (!isFinished && !isLeft) continue
            val exposure = exposureByQuestId[attempt.questId]
            if (exposure == null || exposure !in 0..2) {
                unknown += 1
                continue
            }
            if (isFinished) finished[exposure] += 1 else left[exposure] += 1
        }
        val outcomes = (0..2).map { ExposureOutcome(it, finished[it], left[it]) }
        val struggling = outcomes.firstOrNull {
            it.exposure > SocialExposure.SOLO &&
                it.closed >= MIN_CLOSED_FOR_SUGGESTION &&
                it.left.toDouble() / it.closed >= ABANDON_RATE_FOR_SUGGESTION
        }
        val suggestion = struggling?.let { outcome -> ComfortLevel.entries.first { it.maxExposure == outcome.exposure - 1 } }
        return SocialComfortInsights(outcomes, unknown, suggestion)
    }
}
