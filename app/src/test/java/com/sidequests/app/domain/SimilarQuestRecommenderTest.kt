package com.sidequests.app.domain

import com.sidequests.app.data.InMemorySidequestsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class SimilarQuestRecommenderTest {
    @Test fun `rating changes category weight without suggesting unavailable quests`() {
        val completed = InMemorySidequestsRepository().allQuests().first().copy(durationMinutes = 20)
        val same = completed.copy(id = "same")
        val other = completed.copy(id = "other", category = "Other")
        val unavailable = completed.copy(id = "unfinished")
        val tooLong = completed.copy(id = "long", durationMinutes = 40)
        val catalog = listOf(completed, same, other, unavailable, tooLong)
        val exclusions = setOf(unavailable.id)

        assertEquals(listOf("same", "other"), similarQuests(completed, catalog, exclusions, 5, 30).map { it.id })
        assertEquals(listOf("other", "same"), similarQuests(completed, catalog, exclusions, 2, 30).map { it.id })
    }
}
