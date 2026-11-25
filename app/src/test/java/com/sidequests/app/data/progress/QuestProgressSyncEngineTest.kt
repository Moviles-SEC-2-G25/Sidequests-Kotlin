package com.sidequests.app.data.progress

import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.local.PendingAnalyticsEvent
import com.sidequests.app.data.local.PendingProgressOp
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuestProgressSyncEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = LexLocalStore(File(folder.root, "queue.json"))

    private fun op(id: String, attempt: String, type: String, owner: String = "alice", events: List<PendingAnalyticsEvent> = emptyList()) =
        PendingProgressOp(id, owner, attempt, "botanical-garden", type, "2026-11-24T12:00:00Z", confirmedEvents = events)

    private fun event(id: String) = PendingAnalyticsEvent(id, "alice", "session", "quest_accepted", "2026-11-24T12:00:00Z", "botanical-garden", JsonObject(emptyMap()))

    @Test fun `operations replay in order and release analytics only after confirmation`() = runTest {
        store().update { it.copy(progressOps = listOf(
            op("1", "a1", PendingProgressOp.ACCEPT, events = listOf(event("e1"))),
            op("2", "a1", PendingProgressOp.PROGRESS),
        )) }
        val applied = mutableListOf<String>()
        val retry = QuestProgressSyncEngine(store(), { "alice" }) { applied += it.id; Result.success(Unit) }.drain("alice")
        assertFalse(retry)
        assertEquals(listOf("1", "2"), applied)
        assertTrue(store().read().progressOps.isEmpty())
        assertEquals(listOf("e1"), store().read().events.map { it.id })
    }

    @Test fun `offline failure keeps the queue and its events for a later worker`() = runTest {
        store().update { it.copy(progressOps = listOf(
            op("1", "a1", PendingProgressOp.ACCEPT, events = listOf(event("e1"))),
            op("2", "a1", PendingProgressOp.PROGRESS),
        )) }
        val applied = mutableListOf<String>()
        val retry = QuestProgressSyncEngine(store(), { "alice" }) {
            applied += it.id
            Result.failure(java.net.UnknownHostException("Unable to resolve host"))
        }.drain("alice")
        assertTrue(retry)
        // The step update must not run before its acceptance exists remotely.
        assertEquals(listOf("1"), applied)
        assertEquals(listOf("1", "2"), store().read().progressOps.map { it.id })
        assertEquals(1, store().read().progressOps.first().attempts)
        assertTrue(store().read().events.isEmpty())
    }

    @Test fun `a failing attempt does not block another attempt`() = runTest {
        store().update { it.copy(progressOps = listOf(
            op("1", "a1", PendingProgressOp.ACCEPT),
            op("2", "a2", PendingProgressOp.ACCEPT),
        )) }
        val retry = QuestProgressSyncEngine(store(), { "alice" }) {
            if (it.attemptId == "a1") Result.failure(IllegalStateException("rejected")) else Result.success(Unit)
        }.drain("alice")
        assertTrue(retry)
        assertEquals(listOf("1"), store().read().progressOps.map { it.id })
    }

    @Test fun `only the signed-in owner is replayed`() = runTest {
        store().update { it.copy(progressOps = listOf(
            op("1", "a1", PendingProgressOp.ACCEPT, owner = "alice"),
            op("2", "b1", PendingProgressOp.ACCEPT, owner = "bob"),
        )) }
        val applied = mutableListOf<String>()
        QuestProgressSyncEngine(store(), { "alice" }) { applied += it.id; Result.success(Unit) }.drain("alice")
        assertEquals(listOf("1"), applied)
        assertEquals(listOf("2"), store().read().progressOps.map { it.id })

        // After switching accounts nothing of the previous owner is sent.
        val none = mutableListOf<String>()
        QuestProgressSyncEngine(store(), { "carol" }) { none += it.id; Result.success(Unit) }.drain("bob")
        assertTrue(none.isEmpty())
    }
}
