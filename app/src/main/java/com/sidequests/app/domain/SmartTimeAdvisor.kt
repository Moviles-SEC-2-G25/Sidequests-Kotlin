package com.sidequests.app.domain

import com.sidequests.app.context.TimeOfDay

/** One explicit "I have X minutes" choice made by the user, tagged with the time of day. */
data class TimeChoice(val timeOfDay: TimeOfDay, val minutes: Int)

/**
 * Smart feature (Sprint 2, Carlos): smart available time.
 *
 * Learns which "I have..." option the user picks in each part of the day (morning, afternoon,
 * evening, night) and preselects it when Explorer opens, so recommendations are already matched
 * to the time the user usually has. Needs at least [MIN_CHOICES] choices in that part of the day;
 * ties are broken by the most recent choice. Runs on the device, so it also works offline.
 */
object SmartTimeAdvisor {
    const val MIN_CHOICES = 2
    const val HISTORY_SIZE = 30

    fun preferredMinutes(history: List<TimeChoice>, timeOfDay: TimeOfDay): Int? {
        val relevant = history.filter { it.timeOfDay == timeOfDay }
        if (relevant.size < MIN_CHOICES) return null
        val counts = relevant.groupingBy { it.minutes }.eachCount()
        val best = counts.values.max()
        return relevant.asReversed().first { counts[it.minutes] == best }.minutes
    }
}
