package com.sidequests.app.data.photo

import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.local.StoredPhotoProof
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoProofSyncEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = LexLocalStore(File(folder.root, "queue.json"))
    private fun entry(id: String = "proof", owner: String = "alice") = StoredPhotoProof(
        id, owner, "attempt", "quest", 1,
        File(folder.root, "$id.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }.absolutePath,
        "2026-10-29T12:00:00Z",
    )

    @Test fun `network failure preserves durable photo and original proof id for a later worker`() = runTest {
        val photo = entry()
        store().update { it.copy(photos = listOf(photo)) }
        assertTrue(PhotoProofSyncEngine(store(), { "alice" }) { Result.failure(Exception("offline")) }.drain("alice"))
        assertEquals("pending", store().read().photos.single().status)
        assertTrue(File(photo.localPath).isFile)
        val delivered = mutableListOf<String>()
        assertFalse(PhotoProofSyncEngine(store(), { "alice" }) {
            delivered += it.id
            Result.success(PhotoProofUpload("alice/attempt/quest/step-2-${it.id}.jpg", 3))
        }.drain("alice"))
        assertEquals(listOf("proof"), delivered)
        assertEquals("uploaded", store().read().photos.single().status)
        assertEquals(2, store().read().photos.single().attempts)
    }

    @Test fun `another signed in account never uploads the owners files`() = runTest {
        store().update { it.copy(photos = listOf(entry())) }
        var calls = 0
        PhotoProofSyncEngine(store(), { "bob" }) { calls++; Result.success(PhotoProofUpload("path", 3)) }.drain("alice")
        assertEquals(0, calls)
        assertEquals("pending", store().read().photos.single().status)
    }

    @Test fun `switching account while draining stops before the next photo`() = runTest {
        store().update { it.copy(photos = listOf(entry("one"), entry("two"))) }
        var user = "alice"
        PhotoProofSyncEngine(store(), { user }) { user = "bob"; Result.success(PhotoProofUpload("path", 3)) }.drain("alice")
        assertEquals(listOf("uploaded", "pending"), store().read().photos.map { it.status })
    }

    @Test fun `already registered proof is not uploaded by another drain`() = runTest {
        store().update { it.copy(photos = listOf(entry().copy(status = "uploaded", storagePath = "path"))) }
        PhotoProofSyncEngine(store(), { "alice" }) { error("must not upload twice") }.drain("alice")
        assertTrue(store().read().events.isEmpty())
    }

    @Test fun `missing files require recapture rather than endless retries or false success`() = runTest {
        val photo = entry()
        File(photo.localPath).delete()
        store().update { it.copy(photos = listOf(photo)) }
        assertFalse(PhotoProofSyncEngine(store(), { "alice" }) { error("missing evidence") }.drain("alice"))
        assertEquals("missing", store().read().photos.single().status)
        assertNull(store().read().photos.single().storagePath)
    }

    @Test fun `cancellation leaves recoverable pending work and never claims upload success`() = runTest {
        store().update { it.copy(photos = listOf(entry())) }
        try {
            PhotoProofSyncEngine(store(), { "alice" }) { throw CancellationException() }.drain("alice")
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals("pending", store().read().photos.single().status)
        assertTrue(store().read().events.isEmpty())
    }

    @Test fun `an interrupted uploading state is retried after process recreation`() = runTest {
        store().update { it.copy(photos = listOf(entry().copy(status = "uploading"))) }
        PhotoProofSyncEngine(store(), { "alice" }) { Result.success(PhotoProofUpload("path", 3)) }.drain("alice")
        assertEquals("uploaded", store().read().photos.single().status)
        assertEquals("photo_proof_uploaded", store().read().events.single().eventType)
    }

    @Test fun `replacement workers serialize the same owner and upload each proof once`() = runTest {
        store().update { it.copy(photos = listOf(entry())) }
        var calls = 0
        suspend fun upload(photo: StoredPhotoProof): Result<PhotoProofUpload> {
            calls++
            delay(10)
            return Result.success(PhotoProofUpload(photo.id, 3))
        }
        launch { PhotoProofSyncEngine(store(), { "alice" }, ::upload).drain("alice") }
        runCurrent()
        launch { PhotoProofSyncEngine(store(), { "alice" }, ::upload).drain("alice") }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals("uploaded", store().read().photos.single().status)
        assertEquals(1, store().read().events.size)
    }
}
