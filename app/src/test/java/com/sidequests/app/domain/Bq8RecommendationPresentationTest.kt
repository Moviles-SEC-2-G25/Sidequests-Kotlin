package com.sidequests.app.domain

import com.sidequests.app.data.InMemorySidequestsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Bq8RecommendationPresentationTest {
    private val catalogue = InMemorySidequestsRepository().allQuests()

    @Test fun serverOrderIsNeverChangedByPhone() {
        val serverIds = catalogue.take(3).map { it.id }.reversed()
        val displayed = Bq8RecommendationPresentation.orderedQuests(serverIds, catalogue)
        assertEquals(serverIds, displayed.map { it.id })
    }

    @Test fun unknownAndDuplicateQuestIdsAreNotDisplayedAsFakeResults() {
        val id = catalogue.first().id
        val displayed = Bq8RecommendationPresentation.orderedQuests(
            listOf("unknown-quest", id, id), catalogue
        )
        assertEquals(listOf(id), displayed.map { it.id })
    }

    @Test fun onlyConsistentValidServerArmCanBeLabelled() {
        assertEquals("control", Bq8RecommendationPresentation.experimentArm(listOf("control", "control")))
        assertEquals("diverse", Bq8RecommendationPresentation.experimentArm(listOf("diverse", "diverse")))
        assertNull(Bq8RecommendationPresentation.experimentArm(emptyList()))
        assertNull(Bq8RecommendationPresentation.experimentArm(listOf(null)))
        assertNull(Bq8RecommendationPresentation.experimentArm(listOf("control", "diverse")))
        assertNull(Bq8RecommendationPresentation.experimentArm(listOf("invented", "invented")))
    }
}
