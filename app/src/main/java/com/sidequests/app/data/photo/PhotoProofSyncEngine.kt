package com.sidequests.app.data.photo

import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.local.PendingAnalyticsEvent
import com.sidequests.app.data.local.StoredPhotoProof
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Called serially by WorkManager on IO. Failure never removes the local evidence. */
class PhotoProofSyncEngine(
    private val store: LexLocalStore,
    private val activeUserId: () -> String?,
    private val upload: suspend (StoredPhotoProof) -> Result<PhotoProofUpload>,
) {
    suspend fun drain(userId: String): Boolean = ownerLocks.getOrPut(userId) { Mutex() }.withLock { drainSerially(userId) }

    private suspend fun drainSerially(userId: String): Boolean {
        var retryNeeded = false
        val pending = store.read().photos.filter { it.userId == userId && it.status in setOf("pending", "uploading") }
        for (entry in pending) {
            if (activeUserId() != userId) break
            if (!File(entry.localPath).let { it.isFile && it.length() > 0 }) {
                change(entry.id) { it.copy(status = "missing", error = "The local photo is missing. Capture it again.") }
                continue
            }
            change(entry.id) { it.copy(status = "uploading", attempts = it.attempts + 1, error = null) }
            val result = try { upload(entry) }
                catch (cancelled: CancellationException) {
                    change(entry.id) { it.copy(status = "pending") }
                    throw cancelled
                } catch (error: Exception) { Result.failure(error) }
            result.fold(
                onSuccess = { uploaded ->
                    store.update { state -> state.copy(
                        photos = state.photos.map { if (it.id == entry.id) it.copy(status = "uploaded", storagePath = uploaded.storagePath, error = null) else it },
                        events = state.events + photoEvent(entry.copy(storagePath = uploaded.storagePath), "photo_proof_uploaded", uploaded.byteCount),
                    ) }
                },
                onFailure = { error ->
                    if (error is CancellationException) {
                        change(entry.id) { it.copy(status = "pending") }
                        throw error
                    }
                    retryNeeded = true
                    store.update { state -> state.copy(
                        photos = state.photos.map { if (it.id == entry.id) it.copy(status = "pending", error = "Saved on this device. Upload will retry when connected.") else it },
                        events = state.events + photoEvent(entry, "photo_proof_upload_failed"),
                    ) }
                },
            )
        }
        return retryNeeded
    }

    private fun change(id: String, transform: (StoredPhotoProof) -> StoredPhotoProof) {
        store.update { state -> state.copy(photos = state.photos.map { if (it.id == id) transform(it) else it }) }
    }

    companion object {
        private val ownerLocks = ConcurrentHashMap<String, Mutex>()
    }
}

fun photoEvent(entry: StoredPhotoProof, type: String, bytes: Long = File(entry.localPath).length()): PendingAnalyticsEvent {
    val id = UUID.randomUUID().toString()
    return PendingAnalyticsEvent(id, entry.userId, entry.attemptId, type, Instant.now().toString(), entry.questId, buildJsonObject {
        put("event_id", id)
        put("attempt_id", entry.attemptId)
        put("proof_id", entry.id)
        put("step_index", entry.stepIndex)
        put("step_order", entry.stepIndex)
        put("byte_count", bytes)
        put("size_bytes", bytes)
        put("was_retry", entry.attempts > 0)
        put("verification_type", "photo")
        put("stored_locally", true)
        entry.storagePath?.let { put("storage_path", it) }
    }, category = entry.category)
}
