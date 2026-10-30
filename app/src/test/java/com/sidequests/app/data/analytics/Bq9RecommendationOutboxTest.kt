package com.sidequests.app.data.analytics

import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.model.UserPreferences
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Bq9RecommendationOutboxTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store() = LexLocalStore(File(folder.root, "shared-outbox.json"))

    @Test fun `recommendation events survive restart with their original timestamps and fields`() {
        val item = InMemorySidequestsRepository().questById("botanical-garden")
        val originalMeta = buildJsonObject { put("rank", 1) }
        Bq9RecommendationOutbox.enqueue(
            store = store(),
            userId = "alice",
            sessionId = "session-123",
            eventType = "recommendation_shown",
            availableMinutes = 30,
            preferences = UserPreferences(),
            quest = item,
            metadata = originalMeta,
            occurredAt = "2026-10-29T12:00:00Z",
            eventId = "event-123",
        )

        val saved = store().read().events.single()
        assertEquals("event-123", saved.id)
        assertEquals("alice", saved.userId)
        assertEquals("session-123", saved.journeyId)
        assertEquals("2026-10-29T12:00:00Z", saved.occurredAt)
        assertEquals("recommendation_shown", saved.eventType)
        assertEquals(item.id, saved.questId)
        assertEquals(item.category, saved.category)
        assertEquals(30, saved.availableMinutes)
        assertEquals("1", saved.metadata.getValue("rank").jsonPrimitive.content)
        assertEquals("event-123", saved.metadata.getValue("event_id").jsonPrimitive.content)
    }

    @Test fun `different users and actions remain separate while sharing Lex outbox`() {
        val quest = InMemorySidequestsRepository().questById("botanical-garden")
        for ((id, eventType) in listOf(
            "alice" to "recommendation_skipped",
            "bob" to "recommendation_accepted",
        )) {
            Bq9RecommendationOutbox.enqueue(
                store = store(),
                userId = id,
                sessionId = "$id-session",
                eventType = eventType,
                availableMinutes = 20,
                preferences = UserPreferences(),
                quest = quest,
                metadata = buildJsonObject {},
            )
        }
        val saved = store().read().events
        assertEquals(2, saved.size)
        assertEquals(setOf("alice", "bob"), saved.map { it.userId }.toSet())
        assertEquals(setOf("recommendation_skipped", "recommendation_accepted"), saved.map { it.eventType }.toSet())
    }

    @Test fun `non BQ9 events stay on their existing delivery path`() {
        assertFalse(Bq9RecommendationOutbox.supports("quest_accepted"))
        assertFalse(Bq9RecommendationOutbox.supports("onboarding_step_completed"))
        assertTrue(Bq9RecommendationOutbox.supports("recommendation_shown"))
    }
}
