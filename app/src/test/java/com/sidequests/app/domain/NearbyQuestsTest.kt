package com.sidequests.app.domain

import com.sidequests.app.context.LocationData
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.model.LocationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyQuestsTest {
    @Test fun `compass calculates true bearings from the current fix`() {
        val base = InMemorySidequestsRepository().allQuests().first()
        val origin = LocationData(0.0, 0.0)
        assertEquals(90f, requireNotNull(base.copy(latitude = 0.0, longitude = 1.0).bearingFrom(origin)), .01f)
        assertEquals(0f, requireNotNull(base.copy(latitude = 1.0, longitude = 0.0).bearingFrom(origin)), .01f)
        assertNull(base.copy(latitude = null).bearingFrom(origin))
    }

    @Test fun `only GPS quests get ranked and missing coordinates are explicit`() {
        val base = InMemorySidequestsRepository().allQuests().first().copy(location = LocationMode.Gps)
        val unknown = base.copy(id = "unknown", latitude = null, longitude = null)
        val far = base.copy(id = "far", latitude = 4.70, longitude = -74.0)
        val near = base.copy(id = "near", latitude = 4.61, longitude = -74.0)
        val invalid = base.copy(id = "invalid", latitude = 91.0, longitude = -74.0)
        val anywhere = near.copy(id = "anywhere", location = LocationMode.Anywhere)
        val catalog = listOf(unknown, far, near, invalid, anywhere)

        val ranked = nearbyQuests(catalog, LocationData(4.60, -74.0))
        assertEquals(listOf("near", "far", "unknown", "invalid"), ranked.map { it.quest.id })
        assertEquals(1.11, requireNotNull(ranked.first().distanceKm), 0.01)
        assertNull(ranked.last().distanceKm)
        assertEquals(listOf("unknown", "far", "near", "invalid"), nearbyQuests(catalog, null).map { it.quest.id })
    }
}
