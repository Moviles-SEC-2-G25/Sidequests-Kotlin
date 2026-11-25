package com.sidequests.app.ui

import com.sidequests.app.data.group.GroupChallenge
import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.Quest
import com.sidequests.app.model.QuestDifficulty
import com.sidequests.app.model.SocialLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupChallengePresentationTest {
    private fun quest(id: String, group: Boolean = true) = Quest(
        id = id, title = id, category = "Art", emoji = "🎨",
        duration = "15 min", durationMinutes = 15, distance = "", budget = "Free",
        budgetAmount = 0, difficulty = QuestDifficulty.Easy, location = LocationMode.Anywhere,
        socialLevel = SocialLevel.Group, description = "", isGroup = group,
        steps = emptyList(), tags = emptyList(),
    )

    @Test fun joinedQuestCannotBeCreatedAgainWhileListed() {
        val catalogue = listOf(quest("photo"), quest("potluck"), quest("solo", false))
        val mine = listOf(GroupChallenge("challenge", "photo", "Photo", 2, 4, 4, 1))
        assertEquals(listOf("potluck"), creatableGroupQuests(catalogue, mine).map { it.id })
        assertEquals(listOf("photo", "potluck"), creatableGroupQuests(catalogue, emptyList()).map { it.id })
    }

    @Test fun participantLabelsUseCorrectPlural() {
        assertEquals("1 participant", participantLabel(1))
        assertEquals("0 participants", participantLabel(0))
        assertEquals("2 participants", participantLabel(2))
    }
}
