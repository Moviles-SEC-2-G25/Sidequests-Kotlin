package com.sidequests.app.data.photo

import com.sidequests.app.data.remote.QuestStepDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class PhotoProofUpload(
    val storagePath: String,
    val byteCount: Long,
    val remoteStepOrder: Int? = null,
)

internal fun photoStepOrder(steps: List<QuestStepDto>, stepIndex: Int): Int =
    requireNotNull(steps.sortedBy { it.stepOrder }.getOrNull(stepIndex)
        ?.takeIf { it.verificationType in setOf("photo", "photo_and_location") }?.stepOrder) {
        "No photo verification is defined at this position in the quest."
    }

@Serializable
private data class PhotoProofInsertDto(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("step_order") val stepOrder: Int,
    @SerialName("storage_path") val storagePath: String,
)

@Serializable
private data class ExistingPhotoProof(@SerialName("storage_path") val storagePath: String)

interface PhotoProofRepository {
    suspend fun upload(
        attemptId: String,
        questId: String,
        stepIndex: Int,
        photoFile: File,
        proofId: String = UUID.randomUUID().toString(),
        expectedUserId: String? = null,
        remoteStepOrder: Int? = null,
    ): Result<PhotoProofUpload>
}

class SupabasePhotoProofRepository(
    private val client: SupabaseClient,
    private val bucketName: String,
) : PhotoProofRepository {
    override suspend fun upload(
        attemptId: String,
        questId: String,
        stepIndex: Int,
        photoFile: File,
        proofId: String,
        expectedUserId: String?,
        remoteStepOrder: Int?,
    ): Result<PhotoProofUpload> = runCatching {
        withContext(Dispatchers.IO) {
            check(photoFile.isFile && photoFile.length() > 0) {
                "The captured photo is empty or unavailable."
            }
            require(stepIndex >= 0) { "A photo proof needs a valid quest step." }

            val userId = client.auth.currentUserOrNull()?.id
                ?: error("No authenticated Supabase user is available.")
            check(expectedUserId == null || expectedUserId == userId) { "Sign in to the account that captured this photo." }
            val safeQuestId = safePathSegment(questId)
            fun pathFor(order: Int) = "$userId/$attemptId/$safeQuestId/step-${order + 1}-$proofId.jpg"
            suspend fun isRegistered(order: Int): Boolean = client.from("quest_photo_proofs").select {
                filter { eq("storage_path", pathFor(order)); eq("attempt_id", attemptId); eq("step_order", order) }
            }.decodeList<ExistingPhotoProof>().isNotEmpty()

            // Preserve an older upload if its registration succeeded but the acknowledgement was lost.
            if (remoteStepOrder == null && isRegistered(stepIndex)) {
                return@withContext PhotoProofUpload(pathFor(stepIndex), photoFile.length(), stepIndex)
            }
            val order = remoteStepOrder ?: photoStepOrder(client.from("quest_steps").select {
                filter { eq("quest_id", questId) }
            }.decodeList<QuestStepDto>(), stepIndex)
            check(client.auth.currentUserOrNull()?.id == userId)
            val storagePath = pathFor(order)
            val bucket = client.storage.from(bucketName)
            if (isRegistered(order)) return@withContext PhotoProofUpload(storagePath, photoFile.length(), order)
            val bytes = photoFile.readBytes()
            try {
                bucket.upload(storagePath, bytes) {
                    upsert = false
                    contentType = ContentType.Image.JPEG
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (uploadError: Exception) {
                // A retry may find a JPEG whose upload acknowledgement was lost.
                val sameFile = try { bucket.downloadAuthenticated(storagePath).contentEquals(bytes) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                if (!sameFile) throw uploadError
            }

            try {
                client.from("quest_photo_proofs").insert(
                    PhotoProofInsertDto(
                        attemptId = attemptId,
                        stepOrder = order,
                        storagePath = storagePath,
                    )
                )
            } catch (registrationError: Exception) {
                if (registrationError is CancellationException) throw registrationError
                // Keep the same object for retry. A lost insert acknowledgement is also safe.
                if (!isRegistered(order)) throw registrationError
            }

            PhotoProofUpload(
                storagePath = storagePath,
                byteCount = photoFile.length(),
                remoteStepOrder = order,
            )
        }
    }

    private companion object {
        val UNSAFE_PATH_CHARACTER = Regex("[^A-Za-z0-9._-]")

        fun safePathSegment(value: String): String = value
            .replace(UNSAFE_PATH_CHARACTER, "_")
            .trim('.', '_')
            .ifBlank { "quest" }
    }
}
