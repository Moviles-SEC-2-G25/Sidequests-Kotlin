package com.sidequests.app.domain

import com.sidequests.app.model.QuestProgress
import com.sidequests.app.model.QuestStep

data class StepCompletionDecision(
    val allowed: Boolean,
    val reason: String? = null,
)

object QuestStepCompletionPolicy {
    const val CAPTURE_REQUIRED = "Capture the required photo proof before completing this step."
    const val UPLOAD_PENDING_OFFLINE =
        "Your photo proof is saved on this device. Connect to the internet so it can upload before you finish the quest."
    const val UPLOAD_PENDING_ONLINE =
        "Your photo proof is still uploading. You can finish the quest once the upload is confirmed."

    /**
     * A photo step can be completed with a valid local capture, so the quest keeps moving offline.
     * Completing the step that would finish the quest also requires every photo proof of the
     * attempt to be confirmed by Supabase when [requireUploadedProofToFinish] is set.
     */
    fun evaluate(
        stepIndex: Int,
        step: QuestStep,
        progress: QuestProgress,
        steps: List<QuestStep> = emptyList(),
        requireUploadedProofToFinish: Boolean = false,
        isOnline: Boolean = true,
    ): StepCompletionDecision {
        if (step.requiresPhoto && !progress.hasPhotoProof(stepIndex)) {
            return StepCompletionDecision(allowed = false, reason = CAPTURE_REQUIRED)
        }

        val finishesQuest = steps.isNotEmpty() && (progress.completedSteps + stepIndex).size >= steps.size
        if (requireUploadedProofToFinish && finishesQuest) {
            val unconfirmed = steps.indices.filter { index ->
                steps[index].requiresPhoto && index !in progress.uploadedPhotoProofByStep
            }
            if (unconfirmed.isNotEmpty()) {
                return StepCompletionDecision(
                    allowed = false,
                    reason = if (isOnline) UPLOAD_PENDING_ONLINE else UPLOAD_PENDING_OFFLINE,
                )
            }
        }

        return StepCompletionDecision(allowed = true)
    }
}
