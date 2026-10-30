package com.sidequests.app.data.local

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class OnboardingJourney(
    val id: String,
    val step: Int = 0,
    val completedSteps: Set<Int> = emptySet(),
    val status: String = "active",
    val preferences: com.sidequests.app.model.UserPreferences = com.sidequests.app.model.UserPreferences(),
    val firstQuestId: String? = null,
    val firstExposureId: String? = null,
    val firstAccepted: Boolean = false,
    val sequence: Int = 0,
)

@Serializable
data class PendingAnalyticsEvent(
    val id: String,
    val userId: String,
    val journeyId: String,
    val eventType: String,
    val occurredAt: String,
    val questId: String? = null,
    val metadata: JsonObject,
    val category: String? = null,
)

@Serializable
data class StoredPhotoProof(
    val id: String,
    val userId: String,
    val attemptId: String,
    val questId: String,
    val stepIndex: Int,
    val localPath: String,
    val createdAt: String,
    val status: String = "pending",
    val storagePath: String? = null,
    val attempts: Int = 0,
    val error: String? = null,
    val category: String? = null,
)

@Serializable
data class PhotoQuestSnapshot(
    val userId: String,
    val questId: String,
    val attemptId: String,
    val currentStep: Int,
    val completedSteps: Set<Int>,
    val closed: Boolean = false,
)

@Serializable
data class LexLocalState(
    val journeys: Map<String, OnboardingJourney> = emptyMap(),
    val events: List<PendingAnalyticsEvent> = emptyList(),
    val photos: List<StoredPhotoProof> = emptyList(),
    val photoQuests: Map<String, PhotoQuestSnapshot> = emptyMap(),
)

/** Small private outbox. All callers use Dispatchers.IO. State and events commit together. */
class LexLocalStore(private val file: File) {
    val changes: StateFlow<Long> get() = revision

    fun read(): LexLocalState = synchronized(lock) { readLocked() }

    fun update(transform: (LexLocalState) -> LexLocalState): LexLocalState = synchronized(lock) {
        val previous = readLocked()
        val next = transform(previous)
        if (next == previous) return@synchronized previous
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val temporary = File(parent, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        revision.value += 1
        next
    }

    private fun readLocked(): LexLocalState =
        if (!file.exists()) LexLocalState() else json.decodeFromString(file.readText(Charsets.UTF_8))

    companion object {
        private val lock = Any()
        private val revision = MutableStateFlow(0L)
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
