package com.sidequests.app.domain

import com.sidequests.app.model.Quest
import com.sidequests.app.model.SocialLevel
import com.sidequests.app.model.UserPreferences

/**
 * Sprint 4 discovery decisions are pure and deterministic. The shared Supabase BQ5 RPC remains
 * authoritative for Smart Picks; these local views never present their results as remote rankings.
 * A cached catalogue may be used, but the UI must label its source.
 */
object Sprint4Discovery {
    fun eligible(
        catalogue: List<Quest>,
        availableMinutes: Int,
        budgetMax: Int,
        excludedIds: Set<String> = emptySet(),
    ): List<Quest> = catalogue.filter { quest ->
        quest.id !in excludedIds &&
            quest.durationMinutes <= availableMinutes &&
            quest.budgetAmount <= budgetMax
    }

    /** One simple, feasible plan. Ties favour interests, then shorter and cheaper activities. */
    fun instantPlan(
        catalogue: List<Quest>,
        preferences: UserPreferences,
        availableMinutes: Int,
        excludedIds: Set<String> = emptySet(),
    ): Quest? = eligible(catalogue, availableMinutes, preferences.budgetMax, excludedIds)
        .minWithOrNull(
            compareByDescending<Quest> { quest ->
                quest.category in preferences.interests ||
                    quest.tags.any { tag -> preferences.interests.any { it.equals(tag, ignoreCase = true) } }
            }.thenBy { it.durationMinutes }
                .thenBy { it.budgetAmount }
                .thenBy { it.id }
        )

    /** Group labelled quests are not verified multi-user challenges. */
    fun groupOptions(
        catalogue: List<Quest>,
        preferences: UserPreferences,
        availableMinutes: Int,
        excludedIds: Set<String> = emptySet(),
    ): List<Quest> = eligible(catalogue, availableMinutes, preferences.budgetMax, excludedIds)
        .filter { it.isGroup }
        .sortedWith(compareBy<Quest> { it.durationMinutes }.thenBy { it.id })

    /**
     * Choose at most one representative per category before any repeated categories.
     * This is an opt-in browsing view, not the server-side BQ8 A/B ranking.
     */
    fun diverseOptions(
        catalogue: List<Quest>,
        preferences: UserPreferences,
        availableMinutes: Int,
        excludedIds: Set<String> = emptySet(),
        limit: Int = 3,
    ): List<Quest> {
        if (limit <= 0) return emptyList()
        val eligible = eligible(catalogue, availableMinutes, preferences.budgetMax, excludedIds)
            .sortedWith(compareBy<Quest> { it.durationMinutes }.thenBy { it.id })
        val seen = HashSet<String>()
        val unique = eligible.filter { seen.add(it.category.lowercase()) }
        val uniqueIds = unique.mapTo(HashSet()) { it.id }
        return (unique + eligible.filterNot { it.id in uniqueIds }).take(limit)
    }

    /**
     * Social comfort is a user-controlled browsing filter. Solo excludes group/social activities;
     * Social includes solo and social; Group can browse all. It does not modify profile preferences.
     */
    fun comfortOptions(
        catalogue: List<Quest>,
        preferences: UserPreferences,
        availableMinutes: Int,
        comfort: SocialLevel,
        excludedIds: Set<String> = emptySet(),
    ): List<Quest> = eligible(catalogue, availableMinutes, preferences.budgetMax, excludedIds)
        .filter { quest ->
            when (comfort) {
                SocialLevel.Solo -> quest.socialLevel == SocialLevel.Solo
                SocialLevel.Social -> quest.socialLevel != SocialLevel.Group && !quest.isGroup
                SocialLevel.Group -> true
            }
        }
        .sortedWith(compareBy<Quest> { it.durationMinutes }.thenBy { it.id })
}
