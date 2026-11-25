package com.sidequests.app.domain

import com.sidequests.app.model.QuestProgress
import com.sidequests.app.model.QuestStep
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class QuestStepCompletionPolicyTest {

    @Test
    fun nonPhotoStepCanCompleteWithoutProof() {
        val decision = QuestStepCompletionPolicy.evaluate(
            stepIndex = 0,
            step = QuestStep("Walk there", "Reach the location", requiresPhoto = false),
            progress = QuestProgress(),
        )

        assertTrue(decision.allowed)
        assertNull(decision.reason)
    }

    @Test
    fun photoStepCannotCompleteBeforeCameraProofExists() {
        val decision = QuestStepCompletionPolicy.evaluate(
            stepIndex = 1,
            step = QuestStep("Take a photo", "Capture proof", requiresPhoto = true),
            progress = QuestProgress(),
        )

        assertFalse(decision.allowed)
    }

    @Test
    fun photoStepCanCompleteAfterCameraProofExists() {
        val decision = QuestStepCompletionPolicy.evaluate(
            stepIndex = 1,
            step = QuestStep("Take a photo", "Capture proof", requiresPhoto = true),
            progress = QuestProgress(
                localPhotoProofByStep = mapOf(1 to "/tmp/proof.jpg"),
            ),
        )

        assertTrue(decision.allowed)
    }

    private val steps = listOf(
        QuestStep("Walk there", "Reach the location"),
        QuestStep("Take a photo", "Capture proof", requiresPhoto = true),
        QuestStep("Wrap up", "Reflect"),
    )

    @Test
    fun intermediatePhotoStepOnlyNeedsTheLocalCapture() {
        val decision = QuestStepCompletionPolicy.evaluate(
            stepIndex = 1,
            step = steps[1],
            progress = QuestProgress(completedSteps = setOf(0), localPhotoProofByStep = mapOf(1 to "/tmp/proof.jpg")),
            steps = steps,
            requireUploadedProofToFinish = true,
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun finalStepWaitsForConfirmedUploadOffline() {
        val decision = QuestStepCompletionPolicy.evaluate(
            stepIndex = 2,
            step = steps[2],
            progress = QuestProgress(completedSteps = setOf(0, 1), localPhotoProofByStep = mapOf(1 to "/tmp/proof.jpg")),
            steps = steps,
            requireUploadedProofToFinish = true,
            isOnline = false,
        )

        assertFalse(decision.allowed)
        assertEquals(QuestStepCompletionPolicy.UPLOAD_PENDING_OFFLINE, decision.reason)
    }

    @Test
    fun finalPhotoStepWaitsForItsOwnUploadOnline() {
        val decision = QuestStepCompletionPolicy.evaluate(
            stepIndex = 1,
            step = steps[1],
            progress = QuestProgress(completedSteps = setOf(0, 2), localPhotoProofByStep = mapOf(1 to "/tmp/proof.jpg")),
            steps = steps,
            requireUploadedProofToFinish = true,
        )

        assertFalse(decision.allowed)
        assertEquals(QuestStepCompletionPolicy.UPLOAD_PENDING_ONLINE, decision.reason)
    }

    @Test
    fun finalStepAllowedOnceEveryProofIsUploaded() {
        val decision = QuestStepCompletionPolicy.evaluate(
            stepIndex = 2,
            step = steps[2],
            progress = QuestProgress(
                completedSteps = setOf(0, 1),
                localPhotoProofByStep = mapOf(1 to "/tmp/proof.jpg"),
                uploadedPhotoProofByStep = mapOf(1 to "alice/attempt/quest/step-2.jpg"),
            ),
            steps = steps,
            requireUploadedProofToFinish = true,
        )

        assertTrue(decision.allowed)
    }
}
