package com.sidequests.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class QuestCostDisplayTest {
    private fun remoteQuest(amount: Double) = QuestDto(
        id = "test-quest",
        title = "Test quest",
        description = "A short test quest",
        category = "Food",
        durationMinutes = 30,
        estimatedCost = amount,
        difficulty = "easy",
        locationMode = "anywhere",
        socialLevel = "solo",
    )

    @Test fun numericCostRendersAsMoneyInsteadOfTemplatePlaceholder() {
        assertEquals("$" + "12", remoteQuest(12.0).toDomain(emptyList()).budget)
        assertEquals("Free", remoteQuest(0.0).toDomain(emptyList()).budget)
    }
}
