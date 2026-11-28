package com.sidequests.app.sensor.camera

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

data class PendingPhotoCapture(
    val uri: Uri,
    val file: File,
    val stepIndex: Int,
)

internal data class QuestPhotoTarget(
    val path: String,
    val questId: String,
    val stepIndex: Int,
    val attemptId: String,
    val ownerId: String,
    val remoteStepOrder: Int?,
    val category: String,
)

/**
 * Adapts factory-created evidence files to Android content URIs for a full-resolution capture.
 * The UI only launches the camera and returns the resulting file to the ViewModel.
 */
class CameraPhotoProofService(
    private val context: Context,
    private val fileFactory: PhotoProofFileFactory = JpegPhotoProofFileFactory(context.filesDir),
) {
    fun createCapture(questId: String, stepIndex: Int): PendingPhotoCapture {
        val proof = fileFactory.create(questId, stepIndex)
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                proof.file,
            )
            return PendingPhotoCapture(uri = uri, file = proof.file, stepIndex = proof.stepIndex)
        } catch (error: Exception) {
            discardCapture(proof.file)
            throw error
        }
    }

    fun discardCapture(file: File) {
        if (file.exists()) file.delete()
    }
}
