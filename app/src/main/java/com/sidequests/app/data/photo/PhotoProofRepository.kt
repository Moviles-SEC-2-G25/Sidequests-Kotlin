package com.sidequests.app.data.photo

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
)

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
            val storagePath = listOf(
                userId,
                attemptId,
                safeQuestId,
                "step-${stepIndex + 1}-$proofId.jpg",
            ).joinToString("/")

            val bucket = client.storage.from(bucketName)
            suspend fun isRegistered(): Boolean = client.from("quest_photo_proofs").select {
                filter { eq("storage_path", storagePath); eq("attempt_id", attemptId); eq("step_order", stepIndex) }
            }.decodeList<ExistingPhotoProof>().isNotEmpty()

            if (isRegistered()) return@withContext PhotoProofUpload(storagePath, photoFile.length())
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
                        stepOrder = stepIndex,
                        storagePath = storagePath,
                    )
                )
            } catch (registrationError: Exception) {
                if (registrationError is CancellationException) throw registrationError
                // Keep the same object for retry. A lost insert acknowledgement is also safe.
                if (!isRegistered()) throw registrationError
            }

            PhotoProofUpload(
                storagePath = storagePath,
                byteCount = photoFile.length(),
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
