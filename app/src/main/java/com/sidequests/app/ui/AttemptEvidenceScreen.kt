package com.sidequests.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sidequests.app.BuildConfig
import com.sidequests.app.data.local.StoredPhotoProof
import com.sidequests.app.domain.HistoryEntry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AttemptEvidenceScreen(
    entry: HistoryEntry,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val userId = entry.attempt.userId
    val attemptId = entry.attempt.id
    var reload by remember(userId, attemptId) { mutableStateOf(0) }
    val photos by produceState<Result<List<StoredPhotoProof>>?>(null, userId, attemptId, reload) {
        value = null
        value = try {
            Result.success(viewModel.readLocalAttemptEvidence(userId, attemptId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    var openPhotoId by remember(userId, attemptId) { mutableStateOf<String?>(null) }
    val result = photos

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("Back to Mission History") }
        Text(
            text = "Attempt evidence",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(entry.title, modifier = Modifier.padding(horizontal = 20.dp))
        when {
            result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            result.isFailure -> Column(Modifier.padding(20.dp)) {
                Text("Could not read saved evidence.")
                TextButton(onClick = { reload++ }) { Text("Retry") }
            }
            result.getOrThrow().isEmpty() -> Text(
                "No evidence is saved on this device for this attempt.",
                modifier = Modifier.padding(20.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(result.getOrThrow(), key = { it.id }) { photo ->
                    Column {
                        Text("Step ${photo.stepIndex + 1}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (photo.status) {
                                "uploaded" -> "Uploaded"
                                "missing" -> "Local file unavailable"
                                else -> "Pending upload"
                            },
                        )
                        val open = openPhotoId == photo.id
                        TextButton(onClick = { openPhotoId = if (open) null else photo.id }) {
                            Text(if (open) "Hide photo" else "View photo")
                        }
                        if (open) BaselineEvidencePhoto(photo)
                    }
                }
            }
        }
    }
}

@Composable
private fun BaselineEvidencePhoto(photo: StoredPhotoProof) {
    val image by produceState<Result<ImageBitmap>?>(null, photo.id, photo.localPath) {
        value = withContext(Dispatchers.IO) {
            try {
                val started = SystemClock.elapsedRealtimeNanos()
                val bitmap = decodeOriginalEvidence(File(photo.localPath))
                if (BuildConfig.DEBUG) {
                    val millis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    Log.i(
                        "SidequestsEvidence",
                        "mode=baseline width=${bitmap.width} height=${bitmap.height} " +
                            "bitmapBytes=${bitmap.allocationByteCount} decodeMs=$millis",
                    )
                }
                Result.success(bitmap.asImageBitmap())
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }

    val result = image
    when {
        result == null -> CircularProgressIndicator(Modifier.padding(12.dp))
        result.isFailure -> Text("The photo is not available on this device.")
        else -> Image(
            bitmap = result.getOrThrow(),
            contentDescription = "Evidence for step ${photo.stepIndex + 1}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        )
    }
}

// Reference build for the before/after measurement: decode the original with no reuse.
private fun decodeOriginalEvidence(file: File): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
    }
}
