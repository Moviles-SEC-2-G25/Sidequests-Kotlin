package com.sidequests.app.domain

import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.Quest
import com.sidequests.app.model.QuestDifficulty
import com.sidequests.app.model.QuestStep
import com.sidequests.app.model.SocialLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VarietyDiscoveryTest {
    private fun quest(id: String, category: String, minutes: Int = 20, cost: Int = 0) = Quest(
        id = id, title = id, category = category, emoji = "🧭",
        duration = "$minutes min", durationMinutes = minutes, distance = "",
        budget = "", budgetAmount = cost, difficulty = QuestDifficulty.Easy,
        location = LocationMode.Anywhere, socialLevel = SocialLevel.Solo,
        description = "", steps = listOf(QuestStep("Step 1", "Try it")),
        tags = listOf(category.lowercase()),
    )

    @Test fun categoriesTakePriorityBeforeRepeats() {
        val catalogue = listOf(
            quest("a", "Art"), quest("b", "Art"), quest("c", "Food"),
            quest("d", "Movement"), quest("e", "Art"),
        )
        val results = VarietyDiscovery.select(catalogue, 30, 10)
        assertEquals(listOf("a", "c", "d"), results.map { it.id })
        assertEquals(3, results.map { it.category }.distinct().size)
    }

    @Test fun constraintsAndExclusionsAreAlwaysApplied() {
        val catalogue = listOf(
            quest("ok", "Food"), quest("slow", "Art", minutes = 60),
            quest("expensive", "Music", cost = 200),
        )
        assertEquals(listOf("ok"), VarietyDiscovery.select(catalogue, 30, 30).map { it.id })
        assertTrue(VarietyDiscovery.select(catalogue, 30, 30, excludedIds = setOf("ok")).isEmpty())
        assertTrue(VarietyDiscovery.select(catalogue, 30, 30, limit = 0).isEmpty())
    }

    @Test fun repeatCategoryFillsRemainingSlotsWhenNecessary() {
        val quests = listOf(quest("a", "Art"), quest("b", "Art"))
        assertEquals(listOf("a", "b"), VarietyDiscovery.select(quests, 30, 0).map { it.id })
    }
}
