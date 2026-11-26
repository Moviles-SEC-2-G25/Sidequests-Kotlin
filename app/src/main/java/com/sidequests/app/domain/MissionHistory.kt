package com.sidequests.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HistoryAttempt(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("quest_id") val questId: String,
    val status: String,
    @SerialName("accepted_at") val acceptedAt: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("abandoned_at") val abandonedAt: String? = null,
    val rating: Int? = null,
    @SerialName("abandon_reason") val abandonReason: String? = null,
)

@Serializable
data class HistoryQuest(
    val id: String,
    val title: String,
    val category: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
    @SerialName("location_name") val locationName: String? = null,
    val emoji: String? = null,
)

@Serializable
data class HistorySnapshot(
    val userId: String,
    val attempts: List<HistoryAttempt>,
    val catalog: List<HistoryQuest>,
    val stepTotals: Map<String, Int> = emptyMap(),
    val pendingSyncIds: Set<String> = emptySet(),
)

enum class HistoryFilter { All, Completed, Abandoned }

data class HistoryEntry(
    val attempt: HistoryAttempt,
    val quest: HistoryQuest?,
    val date: ZonedDateTime,
    val steps: Int?,
    val pending: Boolean,
) {
    val title: String get() = quest?.title ?: attempt.questId
}

data class HistoryMonth(
    val year: Int,
    val month: Int,
    val entries: List<HistoryEntry>,
    val completedCount: Int,
    val abandonedCount: Int,
    val inProgressCount: Int,
    val completedMinutes: Int,
    val steps: Int?,
)

data class MissionHistory(
    val months: List<HistoryMonth> = emptyList(),
    val totalAttempts: Int = 0,
    val matchCount: Int = 0,
    val pendingSyncCount: Int = 0,
)

private val accents = mapOf(
    'á' to 'a', 'à' to 'a', 'ä' to 'a', 'â' to 'a',
    'é' to 'e', 'è' to 'e', 'ë' to 'e', 'ê' to 'e',
    'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'î' to 'i',
    'ó' to 'o', 'ò' to 'o', 'ö' to 'o', 'ô' to 'o',
    'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'û' to 'u', 'ñ' to 'n',
)
private val whitespace = Regex("[\\s\\p{Z}]+")
fun normalizeHistorySearch(value: String): String =
    value.lowercase().map { accents[it] ?: it }.joinToString("").trim().replace(whitespace, " ")

fun historyCategoryLabel(category: String): String = when (category.lowercase()) {
    "outdoors" -> "Outdoors"
    "food" -> "Food"
    "art" -> "Art"
    "learning" -> "Learning"
    "mindfulness" -> "Mindfulness"
    "movement" -> "Movement"
    "social" -> "Social"
    else -> category
}

fun historyDateOf(attempt: HistoryAttempt, zone: ZoneId): ZonedDateTime {
    val date = when (attempt.status) {
        "completed" -> attempt.completedAt
        "abandoned" -> attempt.abandonedAt
        else -> attempt.startedAt
    } ?: attempt.acceptedAt
    return Instant.parse(date).atZone(zone)
}

/** Pure CPU transformation, independent of Android and the repository. */
fun buildMissionHistory(
    snapshot: HistorySnapshot,
    filter: HistoryFilter = HistoryFilter.All,
    query: String = "",
    zone: ZoneId = ZoneId.systemDefault(),
): MissionHistory {
    val catalog = snapshot.catalog.associateBy { it.id }
    val attempts = snapshot.attempts.filter { it.status != "skipped" }
    val tokens = normalizeHistorySearch(query).split(' ').filter(String::isNotEmpty)
    val entries = attempts.asSequence().filter {
        when (filter) {
            HistoryFilter.All -> true
            HistoryFilter.Completed -> it.status == "completed"
            HistoryFilter.Abandoned -> it.status == "abandoned"
        }
    }.mapNotNull { attempt ->
        val quest = catalog[attempt.questId]
        if (tokens.isNotEmpty()) {
            val haystack = normalizeHistorySearch(quest?.let {
                listOf(it.title, it.locationName.orEmpty(), it.category, historyCategoryLabel(it.category)).joinToString(" ")
            } ?: attempt.questId)
            if (!tokens.all { haystack.contains(it) }) return@mapNotNull null
        }
        HistoryEntry(attempt, quest, historyDateOf(attempt, zone),
            snapshot.stepTotals[attempt.id], attempt.id in snapshot.pendingSyncIds)
    }.sortedWith(compareByDescending<HistoryEntry> { it.date.toInstant() }.thenBy { it.attempt.id }).toList()
    val months = entries.groupBy { it.date.year to it.date.monthValue }.map { (month, rows) ->
        val steps = rows.mapNotNull { it.steps }
        val completed = rows.filter { it.attempt.status == "completed" }
        HistoryMonth(month.first, month.second, rows, completed.size,
            rows.count { it.attempt.status == "abandoned" },
            rows.count { it.attempt.status in setOf("accepted", "in_progress") },
            completed.sumOf { it.quest?.durationMinutes ?: 0 },
            steps.takeIf { it.isNotEmpty() }?.sum())
    }
    return MissionHistory(months, attempts.size, entries.size, attempts.count { it.id in snapshot.pendingSyncIds })
}
