package com.sidequests.app.domain

import com.sidequests.app.context.TimeOfDay
import com.sidequests.app.context.UserContext
import com.sidequests.app.context.WeatherCondition
import com.sidequests.app.context.WeatherData
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.model.LocationMode
import com.sidequests.app.sensor.ShakeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreCatalogueTest {
    @Test fun `weather and night prioritize anywhere without dropping eligible GPS quests`() {
        val gps = InMemorySidequestsRepository().allQuests().first().copy(durationMinutes = 20)
        val anywhere = gps.copy(id = "indoor", location = LocationMode.Anywhere)
        val long = gps.copy(id = "long", durationMinutes = 90)
        val catalog = listOf(gps, anywhere, long)
        assertEquals(listOf(anywhere, gps), exploreCatalogue(catalog, 30, "all", gps.category, emptySet(), true))
        assertEquals(listOf(gps), exploreCatalogue(catalog, 30, "gps", "All", emptySet(), true))
        assertEquals(listOf(anywhere), exploreCatalogue(catalog, 30, "all", "All", setOf(gps.id), false))
        assertNull(UserContext(timeOfDay = TimeOfDay.MORNING).exploreAdaptationMessage())
        assertNotNull(UserContext(timeOfDay = TimeOfDay.NIGHT).exploreAdaptationMessage())
        assertNotNull(UserContext(timeOfDay = TimeOfDay.MORNING,
            weather = WeatherData(15.0, WeatherCondition.RAIN)).exploreAdaptationMessage())
    }

    @Test fun `shake requires sufficient acceleration and enforces a two second cooldown`() {
        val detector = ShakeDetector()
        assertFalse(detector.sample(11f, 1f, 1f, 0L))
        assertTrue(detector.sample(16f, 0f, 0f, 1L))
        assertFalse(detector.sample(20f, 0f, 0f, 1_000_000_000L))
        assertTrue(detector.sample(0f, 16f, 0f, 2_000_000_001L))
    }
}
