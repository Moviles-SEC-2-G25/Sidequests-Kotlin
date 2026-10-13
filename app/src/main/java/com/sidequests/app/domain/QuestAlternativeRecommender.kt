package com.sidequests.app.domain

import com.sidequests.app.context.TimeOfDay
import com.sidequests.app.context.UserContext
import com.sidequests.app.context.WeatherCondition
import com.sidequests.app.model.AbandonmentReason
import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.Quest
import com.sidequests.app.model.QuestAlternative
import com.sidequests.app.model.UserPreferences
import com.sidequests.app.model.catalogueDistanceMeters

/** Lex's post-abandonment decision engine, independent of the shared BQ5 ranking. */
class QuestAlternativeRecommender {
    fun recommend(
        catalogue: List<Quest>,
        abandonedQuest: Quest,
        reason: AbandonmentReason,
        context: UserContext,
        availableMinutes: Int,
        preferences: UserPreferences,
        excludedIds: Set<String> = emptySet(),
        limit: Int = 3,
    ): List<QuestAlternative> {
        if (limit <= 0 || availableMinutes <= 0) return emptyList()
        return catalogue.asSequence()
            .distinctBy { it.id }
            .filter { it.id != abandonedQuest.id && it.id !in excludedIds }
            .filter { it.durationMinutes in 1..availableMinutes }
            .filter { it.budgetAmount in 0..preferences.budgetMax }
            .filter { preferences.locationMode != "anywhere" || it.location == LocationMode.Anywhere }
            .filter { improvesReason(it, abandonedQuest, reason) }
            .map { score(it, reason, context, preferences) }
            .sortedWith(compareByDescending<QuestAlternative> { it.score }
                .thenBy { it.quest.durationMinutes }
                .thenBy { it.quest.budgetAmount }
                .thenBy { it.quest.id })
            .take(limit)
            .toList()
    }

    private fun improvesReason(candidate: Quest, source: Quest, reason: AbandonmentReason): Boolean =
        when (reason) {
            AbandonmentReason.RanOutOfTime -> candidate.durationMinutes < source.durationMinutes
            AbandonmentReason.TooExpensive -> candidate.budgetAmount < source.budgetAmount
            AbandonmentReason.TooFar -> candidate.location == LocationMode.Anywhere ||
                (source.catalogueDistanceMeters()?.let { previous ->
                    candidate.catalogueDistanceMeters()?.let { it < previous }
                } == true)
            AbandonmentReason.TooDifficult -> candidate.difficulty.ordinal < source.difficulty.ordinal
            AbandonmentReason.PlaceUnavailable -> candidate.location == LocationMode.Anywhere
            AbandonmentReason.WeatherOrMoodChanged, AbandonmentReason.Other -> true
        }

    private fun score(
        quest: Quest,
        reason: AbandonmentReason,
        context: UserContext,
        preferences: UserPreferences,
    ): QuestAlternative {
        var points = 0
        val explanations = mutableListOf<String>()
        val rules = mutableListOf<String>()
        val tags = quest.tags.map { it.lowercase() }.toSet()
        val noTravel = quest.location == LocationMode.Anywhere
        val indoor = tags.any { it in setOf("indoor", "home", "digital") }
        val outdoor = tags.any { it in setOf("outdoor", "park", "hiking", "walking", "nature", "cycling", "running") }
        fun reward(value: Int, code: String, explanation: String) {
            points += value
            rules += code
            explanations += explanation
        }
        when (reason) {
            AbandonmentReason.RanOutOfTime -> reward(4, "shorter", "Shorter than the quest you left")
            AbandonmentReason.TooExpensive -> reward(4, "cheaper", "Lower estimated cost than the quest you left")
            AbandonmentReason.TooFar -> if (noTravel) {
                reward(5, "no_travel", "No travel required")
            } else {
                reward(3, "shorter_catalogue_distance", "Shorter catalogue distance")
            }
            AbandonmentReason.PlaceUnavailable -> reward(4, "no_venue", "Can be done without visiting a venue")
            AbandonmentReason.TooDifficult -> reward(4, "easier", "An easier difficulty level")
            AbandonmentReason.WeatherOrMoodChanged -> if (tags.any { it in setOf("calm", "chill", "creative") }) {
                reward(2, "calmer", "A calmer change of pace")
            }
            AbandonmentReason.Other -> Unit
        }
        when (context.weather?.condition ?: WeatherCondition.UNKNOWN) {
            WeatherCondition.RAIN, WeatherCondition.STORM, WeatherCondition.SNOW -> {
                if (noTravel) reward(4, "wet_no_travel", "No travel needed in wet weather")
                else if (indoor) reward(4, "wet_indoor", "An indoor option for wet weather")
                if (outdoor && !indoor) { points -= 4; rules += "wet_outdoor_penalty" }
            }
            WeatherCondition.CLEAR -> if (outdoor && !indoor) {
                reward(4, "clear_outdoor", "An outdoor option for clear weather")
            }
            WeatherCondition.CLOUDY, WeatherCondition.UNKNOWN -> Unit
        }
        when (context.timeOfDay) {
            TimeOfDay.NIGHT -> {
                if (noTravel) reward(4, "late_local", "Fits a late start without travel")
                else if (indoor) reward(4, "late_local", "An indoor option for a late start")
                if (outdoor && !indoor) { points -= 3; rules += "late_outdoor_penalty" }
            }
            TimeOfDay.MORNING -> if (tags.any { it in setOf("coffee", "learning", "movement") }) {
                reward(2, "morning", "Fits a morning start")
            }
            TimeOfDay.EVENING -> if (tags.any { it in setOf("food", "art", "social") }) {
                reward(2, "evening", "Fits an evening activity")
            }
            TimeOfDay.AFTERNOON -> Unit
        }
        if (preferences.interests.any { interest ->
                quest.category.equals(interest, ignoreCase = true) || tags.any { it.equals(interest, ignoreCase = true) }
            }) reward(2, "interest", "Matches your interests")
        if (quest.socialLevel == preferences.socialLevel) {
            points += 1
            rules += "social_preference"
        }
        if (quest.difficulty.ordinal <= preferences.difficulty.ordinal) {
            points += 1
            rules += "difficulty_preference"
        }
        if (quest.budgetAmount == 0) reward(1, "free", "Free according to the catalogue")
        explanations += "Fits your available time and budget"
        return QuestAlternative(quest, points, explanations.distinct(), rules)
    }
}
