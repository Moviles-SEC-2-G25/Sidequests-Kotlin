package com.sidequests.app.data.analytics

import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.model.UserPreferences
import java.io.File
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnboardingRecorderTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = LexLocalStore(File(folder.root, "state.json"))

    @Test fun `restart resumes setup without a second start or step impression`() {
        val first = OnboardingRecorder(store(), "alice").startOrResume()
        OnboardingRecorder(store(), "alice").completeStep(UserPreferences(interests = setOf("Art")))
        val restored = OnboardingRecorder(store(), "alice").startOrResume()
        assertEquals(first.id, restored.id)
        assertEquals(1, restored.step)
        assertEquals(setOf(0), restored.completedSteps)
        assertEquals(setOf("Art"), restored.preferences.interests)
        assertEquals(1, store().read().events.count { it.eventType == "onboarding_started" })
    }

    @Test fun `four real steps end setup and repeated finish does not add events`() {
        val recorder = OnboardingRecorder(store(), "alice")
        recorder.startOrResume()
        repeat(4) { recorder.completeStep(UserPreferences()) }
        val before = store().read()
        recorder.completeStep(UserPreferences())
        assertEquals(before, store().read())
        assertEquals("completed", before.journeys.getValue("alice").status)
        assertEquals(listOf("interests", "difficulty", "typical_time", "budget_social"), before.events
            .filter { it.eventType == "onboarding_step_completed" }.map { it.metadata.getValue("step_name").jsonPrimitive.content })
    }

    @Test fun `skipping preserves completed steps and emits no completion for skipped step`() {
        val recorder = OnboardingRecorder(store(), "alice")
        recorder.startOrResume()
        recorder.completeStep(UserPreferences())
        recorder.skip(UserPreferences())
        recorder.skip(UserPreferences())
        val saved = store().read()
        assertEquals(setOf(0), saved.journeys.getValue("alice").completedSteps)
        assertEquals(1, saved.events.count { it.eventType == "onboarding_skipped" })
        assertEquals(1, saved.events.count { it.eventType == "onboarding_step_completed" })
    }

    @Test fun `first displayed recommendation is frozen across refresh and process recreation`() {
        val recorder = OnboardingRecorder(store(), "alice")
        recorder.startOrResume()
        recorder.firstRecommendationShown("not-visible-during-setup")
        assertNull(store().read().journeys.getValue("alice").firstQuestId)
        recorder.skip(UserPreferences())
        recorder.firstRecommendationShown("first")
        OnboardingRecorder(store(), "alice").firstRecommendationShown("refreshed")
        assertEquals("first", store().read().journeys.getValue("alice").firstQuestId)
        assertEquals(1, store().read().events.count { it.eventType == "onboarding_first_recommendation_shown" })
    }

    @Test fun `catalogue taps another quest and repeated accepts do not inflate first acceptance`() {
        val recorder = OnboardingRecorder(store(), "alice")
        recorder.startOrResume()
        recorder.skip(UserPreferences())
        recorder.firstRecommendationShown("first")
        recorder.firstRecommendationAccepted("first", false)
        recorder.firstRecommendationAccepted("other", true)
        assertFalse(store().read().journeys.getValue("alice").firstAccepted)
        recorder.firstRecommendationAccepted("first", true)
        recorder.firstRecommendationAccepted("first", true)
        val events = store().read().events
        assertEquals(1, events.count { it.eventType == "onboarding_first_recommendation_accepted" })
        assertEquals(events.single { it.eventType == "onboarding_first_recommendation_shown" }.metadata["exposure_id"],
            events.single { it.eventType == "onboarding_first_recommendation_accepted" }.metadata["exposure_id"])
    }

    @Test fun `accounts have independent journeys and durable event ids`() {
        OnboardingRecorder(store(), "alice").startOrResume()
        OnboardingRecorder(store(), "bob").startOrResume()
        val saved = store().read()
        assertNotEquals(saved.journeys.getValue("alice").id, saved.journeys.getValue("bob").id)
        assertEquals(saved, store().read())
        assertEquals(saved.events.size, saved.events.map { it.id }.distinct().size)
    }

    @Test fun `corrupt storage is reported rather than silently deleting queued evidence`() {
        val file = File(folder.root, "state.json").apply { writeText("corrupt") }
        assertThrows(Exception::class.java) { LexLocalStore(file).read() }
        assertEquals("corrupt", file.readText())
    }
}
