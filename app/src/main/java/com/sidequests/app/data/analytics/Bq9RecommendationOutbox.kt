package com.sidequests.app.data.analytics

import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.local.PendingAnalyticsEvent
import com.sidequests.app.model.Quest
import com.sidequests.app.model.UserPreferences
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persists recommendation telemetry before attempting delivery, using the
 * existing per-account WorkManager outbox instead of introducing another queue.
 *
 * The BQ9 query matches impressions and actions by user/session/quest and
 * occurred_at. Keeping the original timestamp is essential on delayed sends.
 */
internal object Bq9RecommendationOutbox {
    private val events = setOf(
        "recommendation_shown",
        "recommendation_accepted",
        "recommendation_skipped",
    )

    fun supports(eventType: String): Boolean = eventType in events

    fun enqueue(
        store: LexLocalStore,
        userId: String,
        sessionId: String,
        eventType: String,
        availableMinutes: Int,
        preferences: UserPreferences,
        quest: Quest?,
        metadata: JsonObject,
        occurredAt: String = Instant.now().toString(),
        eventId: String = UUID.randomUUID().toString(),
    ) {
        require(supports(eventType)) { "Only recommendation events use this outbox." }

        val event = PendingAnalyticsEvent(
            id = eventId,
            userId = userId,
            journeyId = sessionId, // Existing outbox field stores the analytics session ID.
            eventType = eventType,
            occurredAt = occurredAt,
            questId = quest?.id,
            category = quest?.category,
            availableMinutes = availableMinutes,
            socialLevel = preferences.socialLevel.name.lowercase(),
            locationMode = preferences.locationMode,
            questDurationMinutes = quest?.durationMinutes,
            questDifficulty = quest?.difficulty?.name?.lowercase(),
            estimatedCost = quest?.budgetAmount,
            distanceMeters = quest?.estimatedDistanceMeters(),
            metadata = buildJsonObject {
                metadata.forEach { (key, value) -> put(key, value) }
                put("event_id", eventId)
            },
        )
        store.update { state -> state.copy(events = state.events + event) }
    }
}
