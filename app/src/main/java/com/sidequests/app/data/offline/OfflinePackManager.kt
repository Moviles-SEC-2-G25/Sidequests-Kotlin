package com.sidequests.app.data.offline

import com.sidequests.app.data.LocalQuestCatalogueStore
import com.sidequests.app.data.QuestCatalogueSnapshot
import com.sidequests.app.data.SidequestsRepository
import com.sidequests.app.model.Quest
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OfflinePackStatus {
    READY,
    PENDING_DOWNLOAD,
    DOWNLOADING,
    FAILED,
}

data class OfflinePackEntry(
    val questId: String,
    val status: OfflinePackStatus,
    val message: String? = null,
)

@Serializable
private data class OfflinePackManifest(
    val selectedQuestIds: List<String> = emptyList(),
)

/**
 * Device-local manifest for the quests selected for offline access.
 * Quest content itself remains in the existing catalogue snapshot/cache.
 */
class OfflinePackManager(
    private val repository: SidequestsRepository,
    private val manifestFile: File,
    private val catalogueStore: LocalQuestCatalogueStore? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val downloadSlots = Semaphore(3)

    private val _entries = MutableStateFlow<List<OfflinePackEntry>>(emptyList())
    val entries: StateFlow<List<OfflinePackEntry>> = _entries.asStateFlow()

    suspend fun restore() = mutex.withLock {
        val ids = withContext(Dispatchers.IO) { readManifest().selectedQuestIds.distinct() }
        _entries.value = ids.map { OfflinePackEntry(it, OfflinePackStatus.PENDING_DOWNLOAD) }
        updateLocalAvailability(ids)
    }

    suspend fun addQuest(questId: String, isOnline: Boolean) = mutex.withLock {
        require(questId.isNotBlank()) { "Quest ID cannot be blank." }
        val ids = withContext(Dispatchers.IO) {
            val updated = readManifest().copy(
                selectedQuestIds = (readManifest().selectedQuestIds + questId).distinct(),
            )
            writeManifest(updated)
            updated.selectedQuestIds
        }
        _entries.value = ids.map { id ->
            _entries.value.firstOrNull { it.questId == id }
                ?: OfflinePackEntry(id, OfflinePackStatus.PENDING_DOWNLOAD)
        }
        preparePendingLocked(ids, isOnline)
    }

    suspend fun removeQuest(questId: String) = mutex.withLock {
        val ids = withContext(Dispatchers.IO) {
            val updated = readManifest().copy(
                selectedQuestIds = readManifest().selectedQuestIds.filterNot { it == questId },
            )
            writeManifest(updated)
            updated.selectedQuestIds
        }
        _entries.value = _entries.value.filterNot { it.questId == questId }
        updateLocalAvailability(ids)
    }

    suspend fun retryPending(isOnline: Boolean) = mutex.withLock {
        preparePendingLocked(_entries.value.map { it.questId }, isOnline)
    }

    private suspend fun preparePendingLocked(ids: List<String>, isOnline: Boolean) {
        if (ids.isEmpty()) {
            _entries.value = emptyList()
            return
        }

        updateLocalAvailability(ids)
        val unresolved = _entries.value.filter { it.status != OfflinePackStatus.READY }
        if (unresolved.isEmpty() || !isOnline) return

        _entries.value = _entries.value.map {
            if (it.status == OfflinePackStatus.READY) it
            else it.copy(status = OfflinePackStatus.DOWNLOADING, message = null)
        }

        // The repository refresh fetches the catalogue and steps together and persists them
        // through the existing LocalQuestCatalogueStore. A failed refresh keeps the old snapshot.
        val refreshResult = repository.refresh()

        // Validate selected payloads in parallel on Default, keeping CPU work off the UI thread.
        // The semaphore bounds concurrent validation if a pack contains many quests.
        val savedSnapshot = catalogueStore?.let { store ->
            withContext(Dispatchers.IO) { store.read() }
        }
        val validation = coroutineScope {
            unresolved.map { entry ->
                async(Dispatchers.Default) {
                    downloadSlots.withPermit {
                        val ready = if (catalogueStore != null) {
                            savedSnapshot?.let { hasCompleteOfflinePayload(it, entry.questId) } == true
                        } else {
                            repository.allQuests().firstOrNull { it.id == entry.questId }
                                ?.let(::hasCompleteOfflinePayload) == true
                        }
                        entry.questId to ready
                    }
                }
            }.map { it.await() }.toMap()
        }

        _entries.value = ids.map { id ->
            val existing = _entries.value.firstOrNull { it.questId == id }
            if (existing?.status == OfflinePackStatus.READY) {
                existing
            } else if (validation[id] == true) {
                OfflinePackEntry(id, OfflinePackStatus.READY)
            } else if (refreshResult.isFailure) {
                OfflinePackEntry(id, OfflinePackStatus.FAILED, "Could not retrieve the quest content. Retry when connected.")
            } else {
                OfflinePackEntry(id, OfflinePackStatus.FAILED, "The quest has no complete cached steps.")
            }
        }
    }

    private suspend fun updateLocalAvailability(ids: List<String>) {
        val savedSnapshot = catalogueStore?.let { store ->
            withContext(Dispatchers.IO) { store.read() }
        }
        val current = repository.allQuests().associateBy { it.id }
        val available = coroutineScope {
            ids.map { id ->
                async(Dispatchers.Default) {
                    downloadSlots.withPermit {
                        val ready = if (catalogueStore != null) {
                            savedSnapshot?.let { hasCompleteOfflinePayload(it, id) } == true
                        } else {
                            current[id]?.let(::hasCompleteOfflinePayload) == true
                        }
                        id to ready
                    }
                }
            }.map { it.await() }.toMap()
        }
        _entries.value = ids.map { id ->
            if (available[id] == true) OfflinePackEntry(id, OfflinePackStatus.READY)
            else OfflinePackEntry(id, OfflinePackStatus.PENDING_DOWNLOAD)
        }
    }

    private fun hasCompleteOfflinePayload(quest: Quest): Boolean =
        quest.steps.isNotEmpty() &&
            quest.steps.all { step ->
                step.title.isNotBlank() && step.description.isNotBlank()
            }

    private fun hasCompleteOfflinePayload(snapshot: QuestCatalogueSnapshot, questId: String): Boolean {
        if (snapshot.quests.none { it.id == questId }) return false
        val steps = snapshot.steps.filter { it.questId == questId }
        return steps.isNotEmpty() && steps.all { it.title.isNotBlank() && it.description.isNotBlank() }
    }

    private fun readManifest(): OfflinePackManifest {
        if (!manifestFile.isFile) return OfflinePackManifest()
        return runCatching {
            json.decodeFromString<OfflinePackManifest>(manifestFile.readText(Charsets.UTF_8))
        }.getOrDefault(OfflinePackManifest())
    }

    private fun writeManifest(manifest: OfflinePackManifest) {
        val parent = requireNotNull(manifestFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create offline pack storage directory." }
        val temporary = File(parent, "${manifestFile.name}.tmp")
        try {
            val bytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    manifestFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), manifestFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
