package com.sidequests.app.domain

import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.Quest
import com.sidequests.app.model.QuestDifficulty
import com.sidequests.app.model.QuestStep
import com.sidequests.app.model.SocialLevel
import com.sidequests.app.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Sprint4DiscoveryTest {
    private fun quest(
        id: String,
        category: String,
        time: Int = 20,
        cost: Int = 0,
        social: SocialLevel = SocialLevel.Solo,
        group: Boolean = false,
    ) = Quest(
        id = id,
        title = id,
        category = category,
        emoji = "🧭",
        duration = "$time min",
        durationMinutes = time,
        distance = "",
        budget = "",
        budgetAmount = cost,
        difficulty = QuestDifficulty.Easy,
        location = LocationMode.Anywhere,
        socialLevel = social,
        description = "",
        isGroup = group,
        steps = listOf(QuestStep("Step 1", "Try it")),
        tags = listOf(category.lowercase()),
    )

    @Test fun eligibilityNeverBypassesTimeOrBudget() {
        val all = listOf(quest("ok", "Art"), quest("slow", "Art", time = 60),
            quest("costly", "Art", cost = 100))
        assertEquals(listOf("ok"), Sprint4Discovery.eligible(all, 30, 20).map { it.id })
        assertTrue(Sprint4Discovery.eligible(all, 30, 20, setOf("ok")).isEmpty())
    }

    @Test fun quickPlanFavorsInterestsButStillRespectsConstraints() {
        val all = listOf(quest("first", "Art", time = 10), quest("favorite", "Food", time = 20))
        val prefs = UserPreferences(interests = setOf("Food"), budgetMax = 20)
        assertEquals("favorite", Sprint4Discovery.instantPlan(all, prefs, 30)?.id)
        assertEquals("first", Sprint4Discovery.instantPlan(all, prefs, 15)?.id)
        assertNull(Sprint4Discovery.instantPlan(all, prefs, 5))
    }

    @Test fun groupViewUsesGroupFlagRatherThanSocialLabel() {
        val all = listOf(quest("group", "Food", group = true),
            quest("social-but-not-group", "Food", social = SocialLevel.Group))
        val prefs = UserPreferences(budgetMax = 50)
        assertEquals(listOf("group"), Sprint4Discovery.groupOptions(all, prefs, 30).map { it.id })
    }

    @Test fun varietyFirstShowsDistinctCategories() {
        val all = listOf(quest("a1", "Art"), quest("a2", "Art"), quest("f1", "Food"),
            quest("m1", "Movement"), quest("a3", "Art"))
        val picked = Sprint4Discovery.diverseOptions(all, UserPreferences(), 30)
        assertEquals(3, picked.size)
        assertEquals(3, picked.map { it.category }.distinct().size)
        assertEquals(listOf("a1", "f1", "m1"), picked.map { it.id })
    }

    @Test fun varietyFallsBackToRepeatedCategoryAndHonorsLimit() {
        val all = listOf(quest("a1", "Art"), quest("a2", "Art"))
        assertEquals(2, Sprint4Discovery.diverseOptions(all, UserPreferences(), 30, limit = 10).size)
        assertTrue(Sprint4Discovery.diverseOptions(all, UserPreferences(), 30, limit = 0).isEmpty())
    }

    @Test fun socialExperimentArmIsStablePerAccount() {
        val a = Sprint4Discovery.socialVariant("sample-account-001")
        assertTrue(a == "control" || a == "personalized")
        assertEquals(a, Sprint4Discovery.socialVariant("sample-account-001"))
        assertEquals("control", Sprint4Discovery.socialVariant(null))
    }

    @Test fun socialComfortDoesNotMutateStoredProfile() {
        val prefs = UserPreferences(socialLevel = SocialLevel.Group)
        val all = listOf(quest("solo", "Art"), quest("social", "Art", social = SocialLevel.Social),
            quest("group", "Art", social = SocialLevel.Group, group = true))
        assertEquals(listOf("solo"), Sprint4Discovery.comfortOptions(all, prefs, 30, SocialLevel.Solo).map { it.id })
        assertEquals(listOf("social", "solo"), Sprint4Discovery.comfortOptions(all, prefs, 30, SocialLevel.Social).map { it.id })
        assertEquals(3, Sprint4Discovery.comfortOptions(all, prefs, 30, SocialLevel.Group).size)
        assertEquals(SocialLevel.Group, prefs.socialLevel)
    }
}
