package com.sidequests.app.data.analytics

import com.sidequests.app.data.local.LexLocalState
import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.local.OnboardingJourney
import com.sidequests.app.data.local.PendingAnalyticsEvent
import com.sidequests.app.model.UserPreferences
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Initial setup only; returning to the app resumes the same journey and first exposure. */
class OnboardingRecorder(
    private val store: LexLocalStore,
    private val userId: String,
    private val now: () -> String = { Instant.now().toString() },
) {
    fun startOrResume(): OnboardingJourney {
        store.update { state ->
            val existing = state.journeys[userId]
            if (existing?.status == "active") append(state, existing, listOf("onboarding_resumed" to existing.step))
            else if (existing != null) state else {
                val journey = OnboardingJourney(UUID.randomUUID().toString())
                append(state, journey, listOf("onboarding_started" to null, "onboarding_step_viewed" to 0))
            }
        }
        return store.read().journeys.getValue(userId)
    }

    fun completeStep(preferences: UserPreferences): OnboardingJourney = change { state, journey ->
        if (journey.status != "active") state else {
            val step = journey.step
            val completed = journey.copy(
                completedSteps = journey.completedSteps + step,
                step = (step + 1).coerceAtMost(3),
                status = if (step == 3) "completed" else "active",
                preferences = preferences,
            )
            append(state, completed, listOf(
                "onboarding_step_completed" to step,
                (if (step == 3) "onboarding_completed" else "onboarding_step_viewed") to (if (step == 3) null else step + 1),
            ))
        }
    }

    fun skip(preferences: UserPreferences): OnboardingJourney = change { state, journey ->
        if (journey.status != "active") state else append(
            state, journey.copy(status = "skipped", preferences = preferences),
            listOf("onboarding_skipped" to journey.step),
        )
    }

    fun savePreferences(preferences: UserPreferences) = change { state, journey ->
        state.copy(journeys = state.journeys + (userId to journey.copy(preferences = preferences)))
    }

    fun firstRecommendationShown(questId: String): OnboardingJourney = change { state, journey ->
        if (journey.status == "active" || journey.firstQuestId != null) state else append(
            state,
            journey.copy(firstQuestId = questId, firstExposureId = UUID.randomUUID().toString()),
            listOf("onboarding_first_recommendation_shown" to null),
            questId,
        )
    }

    fun firstRecommendationAccepted(questId: String, fromSmartPicks: Boolean, attemptId: String? = null): OnboardingJourney = change { state, journey ->
        if (!fromSmartPicks || journey.firstQuestId != questId || journey.firstAccepted) state else append(
            state, journey.copy(firstAccepted = true),
            listOf("onboarding_first_recommendation_accepted" to null), questId, attemptId,
        )
    }

    private fun change(block: (LexLocalState, OnboardingJourney) -> LexLocalState): OnboardingJourney {
        val state = store.update { block(it, it.journeys.getValue(userId)) }
        return state.journeys.getValue(userId)
    }

    private fun append(
        state: LexLocalState,
        journey: OnboardingJourney,
        types: List<Pair<String, Int?>>,
        questId: String? = null,
        attemptId: String? = null,
    ): LexLocalState {
        val events = types.mapIndexed { offset, (type, step) ->
            val id = UUID.randomUUID().toString()
            PendingAnalyticsEvent(id, userId, journey.id, type, now(), questId, buildJsonObject {
                put("schema_version", 2)
                put("bq_key", "BQ3")
                put("platform", "kotlin")
                put("event_id", id)
                put("onboarding_id", journey.id)
                put("event_sequence", journey.sequence + offset + 1)
                put("completed_steps", JsonArray(journey.completedSteps.sorted().map { JsonPrimitive(it + 1) }))
                put("onboarding_status", journey.status)
                step?.let {
                    put("step_order", it + 1)
                    put("step_name", STEP_NAMES[it])
                }
                journey.firstExposureId?.let { put("exposure_id", it) }
                journey.firstQuestId?.let { put("first_quest_id", it) }
                attemptId?.let { put("attempt_id", it) }
            })
        }
        return state.copy(
            journeys = state.journeys + (userId to journey.copy(sequence = journey.sequence + types.size)),
            events = state.events + events,
        )
    }

    companion object {
        val STEP_NAMES = listOf("interests", "difficulty", "typical_time", "budget_social")
    }
}
