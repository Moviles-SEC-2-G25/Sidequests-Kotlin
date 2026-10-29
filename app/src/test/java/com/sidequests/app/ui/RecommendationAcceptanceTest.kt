package com.sidequests.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationAcceptanceTest {
    private val shownRemotely = listOf("quest-a", "quest-b")

    @Test
    fun smartPicksAcceptanceIsRecordedForNewAttempt() {
        assertTrue(
            isRecommendationAcceptance(
                questId = "quest-a",
                openedFromSmartPicksId = "quest-a",
                currentRemoteRecommendations = shownRemotely,
                startsNewAttempt = true,
            )
        )
    }

    @Test
    fun acceptingFromExploreDoesNotInflateRecommendationAcceptance() {
        assertFalse(
            isRecommendationAcceptance(
                questId = "quest-a",
                openedFromSmartPicksId = null,
                currentRemoteRecommendations = shownRemotely,
                startsNewAttempt = true,
            )
        )
    }

    @Test
    fun localFallbackWithoutRemoteImpressionIsNotCounted() {
        assertFalse(
            isRecommendationAcceptance(
                questId = "quest-a",
                openedFromSmartPicksId = "quest-a",
                currentRemoteRecommendations = emptyList(),
                startsNewAttempt = true,
            )
        )
    }

    @Test
    fun resumingAnExistingAttemptDoesNotCountAsNewAcceptance() {
        assertFalse(
            isRecommendationAcceptance(
                questId = "quest-a",
                openedFromSmartPicksId = "quest-a",
                currentRemoteRecommendations = shownRemotely,
                startsNewAttempt = false,
            )
        )
    }

    @Test
    fun aDifferentQuestCannotBeCountedAsRecommendedAcceptance() {
        assertFalse(
            isRecommendationAcceptance(
                questId = "quest-b",
                openedFromSmartPicksId = "quest-a",
                currentRemoteRecommendations = shownRemotely,
                startsNewAttempt = true,
            )
        )
    }
}
