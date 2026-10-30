package com.sidequests.app.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActiveQuestLocalStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun missingStoreStartsWithNoSavedQuestProgress() {
        val state = LexLocalStore(File(folder.root, "local-state.json")).read()

        assertTrue(state.photoQuests.isEmpty())
    }

    @Test
    fun activeQuestSnapshotSurvivesStoreRecreation() {
        val file = File(folder.root, "local-state.json")
        val store = LexLocalStore(file)
        val snapshot = PhotoQuestSnapshot(
            userId = "alice",
            questId = "botanical-garden",
            attemptId = "attempt-1",
            currentStep = 2,
            completedSteps = setOf(0, 1),
        )

        store.update { it.copy(photoQuests = it.photoQuests + (snapshot.attemptId to snapshot)) }
        val restored = LexLocalStore(file).read().photoQuests.getValue("attempt-1")

        assertEquals(snapshot, restored)
    }

    @Test
    fun malformedLocalStoreIsReportedInsteadOfRestoringUntrustedProgress() {
        val file = File(folder.root, "local-state.json")
        file.writeText("{not-valid-json")

        try {
            LexLocalStore(file).read()
            throw AssertionError("Expected malformed local state to fail decoding")
        } catch (_: kotlinx.serialization.SerializationException) {
            assertTrue(file.exists())
        }
    }
}
