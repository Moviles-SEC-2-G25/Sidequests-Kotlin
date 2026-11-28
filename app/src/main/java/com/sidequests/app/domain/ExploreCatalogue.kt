package com.sidequests.app.domain

import com.sidequests.app.context.TimeOfDay
import com.sidequests.app.context.UserContext
import com.sidequests.app.context.WeatherCondition
import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.Quest

fun UserContext.exploreAdaptationMessage(): String? = when {
    weather?.condition in setOf(WeatherCondition.RAIN, WeatherCondition.STORM, WeatherCondition.SNOW) ->
        if (weather?.isStale == true) "Last known weather favors quests you can do anywhere."
        else "Quests you can do anywhere are listed first because of the weather."
    timeOfDay == TimeOfDay.NIGHT -> "It's nighttime. Quests you can do anywhere are listed first."
    else -> null
}

fun exploreCatalogue(
    catalogue: List<Quest>,
    minutes: Int,
    scope: String,
    category: String,
    skipped: Set<String>,
    prioritizeAnywhere: Boolean,
): List<Quest> {
    val filtered = catalogue.filter { quest ->
        quest.id !in skipped &&
            quest.durationMinutes <= minutes &&
            (category.equals("All", ignoreCase = true) || quest.category.equals(category, ignoreCase = true)) &&
            when (scope) {
                "gps" -> quest.location == LocationMode.Gps
                "anywhere" -> quest.location == LocationMode.Anywhere
                else -> true
            }
    }
    return if (prioritizeAnywhere) filtered.sortedBy { it.location != LocationMode.Anywhere } else filtered
}
