package com.sidequests.app.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuestSponsorCategoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val basicQuestJson = """
        {
          "id": "sponsored-climbing",
          "title": "First Climb",
          "description": "Beginner climbing",
          "category": "Movement",
          "duration_minutes": 45,
          "estimated_cost": 0,
          "difficulty": "medium",
          "location_mode": "gps",
          "social_level": "solo",
          "is_sponsored": true,
          "sponsor_name": "Boulder Box"
        }
    """.trimIndent()

    @Test fun legacyQuestWithoutBusinessCategoryStillDecodes() {
        val dto = json.decodeFromString<QuestDto>(basicQuestJson)
        assertEquals("Movement", dto.category)
        assertNull(dto.sponsorBusinessCategory)
    }

    @Test fun sponsorBusinessCategoryIsDistinctFromQuestCategory() {
        val newJson = basicQuestJson.replace(
            "\"sponsor_name\": \"Boulder Box\"",
            "\"sponsor_name\": \"Boulder Box\", \"sponsor_business_category\": \"fitness_and_recreation\"",
        )
        val dto = json.decodeFromString<QuestDto>(newJson)
        assertEquals("Movement", dto.category)
        assertEquals("fitness_and_recreation", dto.sponsorBusinessCategory)
    }
}
