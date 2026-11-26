package com.sidequests.app.data.photo

import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.local.StoredPhotoProof
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttemptEvidenceRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun reopeningKeepsOwnFilesAndRemoteReferencesWithExactAttemptScope() = runTest {
        val directory = temp.newFolder("quest-proofs")
        val legitimateFile = File(directory, "proof.jpg").also { it.writeText("test") }
        val outside = temp.newFile("outside.jpg").also { it.writeText("test") }
        val stateFile = File(temp.root, "outbox.json")
        val store = LexLocalStore(stateFile)
        val remote = RemoteEvidence(1, "owner/attempt/quest/step-2-remote.jpg")
        val foreign = RemoteEvidence(1, "other/attempt/quest/step-2-other.jpg")
        val proof = StoredPhotoProof("proof", "owner", "attempt", "quest", 0, legitimateFile.path, "now")
        store.update {
            it.copy(
                photos = listOf(
                    proof,
                    proof.copy(id = "separate", attemptId = "second"),
                    proof.copy(id = "outside", stepIndex = 2, localPath = outside.path),
                ),
                evidenceIndexes = mapOf("attempt" to SavedEvidenceIndex("owner", listOf(remote, foreign))),
            )
        }

        val reopened = AttemptEvidenceRepository(LexLocalStore(stateFile), directory, null, "quest-proofs")
        val own = reopened.read("owner", "attempt")
        assertTrue(own.hasSavedRemoteIndex)
        assertEquals(listOf("proof", "remote:${remote.storagePath}", "outside"), own.photos.map { it.id })
        assertEquals(legitimateFile.canonicalFile, own.photos[0].file)
        assertNull(own.photos[1].file)
        assertNull(own.photos[2].file)
        assertEquals(listOf("separate"), reopened.read("owner", "second").photos.map { it.id })
        val other = reopened.read("other", "attempt")
        assertFalse(other.hasSavedRemoteIndex)
        assertTrue(other.photos.isEmpty())
    }

    @Test
    fun remoteUploadIsNotDuplicatedWhenLocalAcknowledgementIsMissing() {
        val proof = StoredPhotoProof("proof", "owner", "attempt", "quest", 0, "/photo.jpg", "now")
        val row = RemoteEvidence(0, "owner/attempt/quest/step-1-proof.jpg")
        val retake = RemoteEvidence(0, "owner/attempt/quest/step-1-another.jpg")
        val photos = mergeEvidence(listOf(proof), listOf(row, retake, row), ::File)
        assertEquals(2, photos.size)
        assertEquals("proof", photos.first().id)
        assertEquals(row.storagePath, photos.first().storagePath)
        assertTrue(photos.first().uploaded)
        assertEquals(retake.storagePath, photos.last().storagePath)
    }
}
