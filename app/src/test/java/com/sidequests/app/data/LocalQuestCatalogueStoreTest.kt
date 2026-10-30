package com.sidequests.app.data

import com.sidequests.app.data.remote.QuestDto
import com.sidequests.app.data.remote.QuestStepDto
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalQuestCatalogueStoreTest {
    private fun snapshot(title: String) = QuestCatalogueSnapshot(
        quests = listOf(
            QuestDto(
                id = "saved-quest",
                title = title,
                description = "Offline description",
                category = "Art",
                durationMinutes = 30,
                estimatedCost = 0.0,
                difficulty = "easy",
                locationMode = "anywhere",
                socialLevel = "solo",
            ),
        ),
        steps = listOf(
            QuestStepDto(
                id = 1L,
                questId = "saved-quest",
                stepOrder = 0,
                title = "First step",
                description = "A saved step",
                verificationType = "none",
            ),
        ),
    )

    @Test fun absentFileHasNoSavedCatalogue() {
        val root = Files.createTempDirectory("sidequests-absent").toFile()
        try {
            assertNull(LocalQuestCatalogueStore(root.resolve("catalogue.json")).read())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun savedCatalogueSurvivesNewStoreInstance() {
        val root = Files.createTempDirectory("sidequests-persist").toFile()
        try {
            val path = root.resolve("catalogue.json")
            LocalQuestCatalogueStore(path).save(snapshot("Art walk"))
            val restored = LocalQuestCatalogueStore(path).read()
            assertEquals("Art walk", restored?.quests?.single()?.title)
            assertEquals("First step", restored?.steps?.single()?.title)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun invalidContentDoesNotCrashTheReader() {
        val root = Files.createTempDirectory("sidequests-corrupt").toFile()
        try {
            val path = root.resolve("catalogue.json")
            path.writeText("broken json")
            assertNull(LocalQuestCatalogueStore(path).read())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun emptySnapshotDoesNotReplacePreviousSavedData() {
        val root = Files.createTempDirectory("sidequests-empty").toFile()
        try {
            val store = LocalQuestCatalogueStore(root.resolve("catalogue.json"))
            store.save(snapshot("Original"))
            try {
                store.save(QuestCatalogueSnapshot(emptyList(), emptyList()))
                fail("Empty catalogue should be rejected")
            } catch (_: IllegalArgumentException) { }
            assertEquals("Original", store.read()?.quests?.single()?.title)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun newerSuccessReplacesPreviousCatalogue() {
        val root = Files.createTempDirectory("sidequests-refresh").toFile()
        try {
            val store = LocalQuestCatalogueStore(root.resolve("catalogue.json"))
            store.save(snapshot("First"))
            store.save(snapshot("Updated"))
            assertEquals("Updated", store.read()?.quests?.single()?.title)
        } finally {
            root.deleteRecursively()
        }
    }
}
