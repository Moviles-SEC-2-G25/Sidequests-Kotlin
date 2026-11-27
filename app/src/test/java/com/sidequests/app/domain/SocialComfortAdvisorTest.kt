package com.sidequests.app.domain

import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.model.SocialLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialComfortAdvisorTest {
    private val quests = InMemorySidequestsRepository().allQuests()

    private fun attempt(id: String, questId: String, status: String) =
        HistoryAttempt(id = id, userId = "u", questId = questId, status = status, acceptedAt = "2026-11-01T10:00:00Z")

    @Test fun `exposure follows social level and group flag`() {
        val group = quests.first { it.isGroup }
        val solo = quests.first { !it.isGroup && it.socialLevel == SocialLevel.Solo }
        assertEquals(SocialExposure.GROUP, SocialExposure.of(group))
        assertEquals(SocialExposure.SOLO, SocialExposure.of(solo))
        assertEquals(SocialExposure.SOCIAL, SocialExposure.of(solo.copy(socialLevel = SocialLevel.Social)))
    }

    @Test fun `arm is stable and splits accounts`() {
        val arms = (1..200).map { SocialComfortAdvisor.experimentArm("user-$it") }
        assertEquals(arms, (1..200).map { SocialComfortAdvisor.experimentArm("user-$it") })
        val adapt = arms.count { it == SocialComfortAdvisor.ARM_ADAPT }
        assertTrue("split was $adapt/200", adapt in 70..130)
    }

    @Test fun `user choice wins over the arm and high never adapts`() {
        val low = SocialComfortSettings(level = ComfortLevel.Low)
        assertTrue(SocialComfortAdvisor.adaptation(low, SocialComfortAdvisor.ARM_ADAPT).active)
        assertFalse(SocialComfortAdvisor.adaptation(low, SocialComfortAdvisor.ARM_CONTROL).active)
        val off = SocialComfortAdvisor.adaptation(low.copy(adaptChoice = false), SocialComfortAdvisor.ARM_ADAPT)
        assertFalse(off.active)
        assertEquals(AdaptationSource.User, off.source)
        assertFalse(SocialComfortAdvisor.adaptation(SocialComfortSettings(ComfortLevel.High, true), SocialComfortAdvisor.ARM_ADAPT).active)
    }

    @Test fun `adapt hides quests above the level and keeps the order`() {
        val ordered = quests.reversed()
        val adapted = SocialComfortAdvisor.adapt(ordered, ComfortLevel.Low, active = true)
        assertTrue(adapted.none { SocialExposure.of(it) > 0 })
        assertEquals(ordered.filter { SocialExposure.of(it) == 0 }.map { it.id }, adapted.map { it.id })
        assertEquals(ordered, SocialComfortAdvisor.adapt(ordered, ComfortLevel.Low, active = false))
        assertEquals(setOf("group-photo-walk"), SocialComfortAdvisor.excludedIds(quests, ComfortLevel.Medium))
    }

    @Test fun `insights count closed attempts per exposure and suggest a lower level`() {
        val exposures = mapOf("solo" to 0, "group" to 2)
        val insights = SocialComfortAdvisor.insights(
            listOf(
                attempt("1", "solo", "completed"),
                attempt("2", "group", "abandoned"),
                attempt("3", "group", "abandoned"),
                attempt("4", "group", "completed"),
                attempt("5", "group", "in_progress"),
                attempt("6", "removed-quest", "completed"),
            ),
            exposures,
        )
        assertEquals(ExposureOutcome(0, 1, 0), insights.outcomes[0])
        assertEquals(ExposureOutcome(2, 1, 2), insights.outcomes[2])
        assertEquals(1, insights.unknownAttempts)
        assertEquals(ComfortLevel.Medium, insights.suggestion)
    }

    @Test fun `no suggestion with too little history`() {
        val insights = SocialComfortAdvisor.insights(listOf(attempt("1", "group", "abandoned")), mapOf("group" to 2))
        assertNull(insights.suggestion)
    }
}
