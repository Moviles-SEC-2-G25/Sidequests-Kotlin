package com.sidequests.app.data.photo

import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.local.StoredPhotoProof
import com.sidequests.app.model.QuestStep
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RemoteEvidence(
    @SerialName("step_order") val stepIndex: Int,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
)

@Serializable
data class SavedEvidenceIndex(
    val userId: String,
    val rows: List<RemoteEvidence>,
)

internal data class EvidencePhoto(
    val id: String,
    val stepIndex: Int,
    val file: File?,
    val storagePath: String?,
    val uploaded: Boolean,
    val capturedAt: String? = null,
    val uploadedAt: String? = null,
)

internal data class AttemptEvidence(
    val photos: List<EvidencePhoto>,
    val hasSavedRemoteIndex: Boolean,
)

class AttemptEvidenceRepository(
    private val store: LexLocalStore,
    private val photoDirectory: File,
    private val client: SupabaseClient?,
    private val bucket: String,
) {
    val changes get() = store.changes

    internal suspend fun read(userId: String, attemptId: String, steps: List<QuestStep> = emptyList()): AttemptEvidence = withContext(Dispatchers.IO) {
        val state = store.read()
        val index = state.evidenceIndexes[attemptId]?.takeIf { it.userId == userId }
        val root = photoDirectory.canonicalFile.toPath()
        val local = state.photos.filter { it.userId == userId && it.attemptId == attemptId }
        val remote = index?.rows.orEmpty().filter { isOwnedPath(it.storagePath, userId, attemptId) }
        AttemptEvidence(
            mergeEvidence(local, remote, steps) { path ->
                runCatching { File(path).canonicalFile }.getOrNull()?.takeIf {
                    it.toPath().startsWith(root) && it.isFile && it.length() > 0
                }
            },
            hasSavedRemoteIndex = index != null,
        )
    }

    internal suspend fun refresh(userId: String, attemptId: String) = withContext(Dispatchers.IO) {
        val authenticated = authenticatedClient(userId)
        val rows = withTimeout(12_000) {
            val found = mutableListOf<RemoteEvidence>()
            var offset = 0L
            while (true) {
                authenticatedClient(userId)
                val page = authenticated.from("quest_photo_proofs")
                    .select(columns = Columns.list("step_order", "storage_path", "uploaded_at")) {
                        filter { eq("attempt_id", attemptId) }
                        order("storage_path", Order.ASCENDING)
                        range(offset..offset + 199)
                    }.decodeList<RemoteEvidence>()
                found.addAll(page.filter { isOwnedPath(it.storagePath, userId, attemptId) })
                if (page.size < 200) break
                offset += page.size
            }
            found
        }
        currentCoroutineContext().ensureActive()
        authenticatedClient(userId)
        store.update { state ->
            state.copy(evidenceIndexes = state.evidenceIndexes + (attemptId to SavedEvidenceIndex(userId, rows)))
        }
        Unit
    }

    internal suspend fun download(userId: String, attemptId: String, path: String): ByteArray =
        withContext(Dispatchers.IO) {
            check(isOwnedPath(path, userId, attemptId))
            val result = withTimeout(15_000) {
                authenticatedClient(userId).storage.from(bucket).downloadAuthenticated(path)
            }
            authenticatedClient(userId)
            result
        }

    private fun authenticatedClient(userId: String): SupabaseClient {
        val configured = checkNotNull(client)
        check(configured.auth.currentUserOrNull()?.id == userId)
        return configured
    }
}

private fun isOwnedPath(path: String, userId: String, attemptId: String): Boolean =
    path.startsWith("$userId/$attemptId/") && path.split('/').none { it in setOf("", ".", "..") }

internal fun mergeEvidence(
    local: List<StoredPhotoProof>,
    remote: List<RemoteEvidence>,
    steps: List<QuestStep> = emptyList(),
    localFile: (String) -> File?,
): List<EvidencePhoto> {
    val remoteByPath = remote.associateBy { it.storagePath }
    val matchedPaths = mutableSetOf<String>()
    val photos = local.map { proof ->
        // Reconcile a server upload whose confirmation did not reach the local outbox.
        val matching = remoteByPath[proof.storagePath] ?: remote.firstOrNull {
            it.storagePath.endsWith("-${proof.id}.jpg") &&
                (proof.remoteStepOrder == null || it.stepIndex == proof.remoteStepOrder)
        }
        val path = matching?.storagePath ?: proof.storagePath?.takeIf {
            isOwnedPath(it, proof.userId, proof.attemptId)
        }
        path?.let(matchedPaths::add)
        EvidencePhoto(
            id = proof.id,
            stepIndex = proof.stepIndex,
            file = localFile(proof.localPath),
            storagePath = path,
            uploaded = matching != null || proof.status == "uploaded",
            capturedAt = proof.createdAt,
            uploadedAt = matching?.uploadedAt,
        )
    }
    val indexesByOrder = steps.mapIndexed { index, step -> (step.remoteStepOrder ?: index) to index }.toMap()
    val remaining = remoteByPath.values.filterNot { it.storagePath in matchedPaths }.map {
        EvidencePhoto(
            "remote:${it.storagePath}", indexesByOrder[it.stepIndex] ?: it.stepIndex, null, it.storagePath,
            uploaded = true, uploadedAt = it.uploadedAt,
        )
    }
    return (photos + remaining).sortedBy { it.stepIndex }
}
