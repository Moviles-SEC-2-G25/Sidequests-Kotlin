package com.sidequests.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sidequests.app.data.photo.AttemptEvidence
import com.sidequests.app.data.photo.AttemptEvidenceRepository
import com.sidequests.app.data.photo.EvidencePhoto
import com.sidequests.app.domain.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun AttemptEvidenceScreen(
    entry: HistoryEntry,
    repository: AttemptEvidenceRepository,
    thumbnails: EvidenceThumbnails,
    online: Boolean,
    onBack: () -> Unit,
) {
    val userId = entry.attempt.userId
    val attemptId = entry.attempt.id
    var reload by remember { mutableStateOf(0) }
    val saved by produceState<Result<AttemptEvidence>?>(null, repository, reload) {
        repository.changes.collectLatest {
            value = try {
                Result.success(repository.read(userId, attemptId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }
    var refreshing by remember(online, reload) { mutableStateOf(online) }
    var refreshFailed by remember(online, reload) { mutableStateOf(false) }
    LaunchedEffect(repository, online, reload) {
        if (!online) return@LaunchedEffect
        try {
            repository.refresh(userId, attemptId)
        } catch (_: TimeoutCancellationException) {
            refreshFailed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            refreshFailed = true
        } finally {
            refreshing = false
        }
    }

    var openPhotoId by remember { mutableStateOf<String?>(null) }
    val result = saved
    val data = result?.getOrNull()
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("Volver a Mi bitácora") }
        Text(
            text = "Evidencias del intento",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(entry.title, modifier = Modifier.padding(horizontal = 20.dp))
        if (data?.photos.orEmpty().isNotEmpty()) {
            when {
                !online -> Text("Sin conexión. Puedes abrir las fotos guardadas.", Modifier.padding(20.dp))
                refreshing -> Text("Actualizando evidencias…", Modifier.padding(20.dp))
            }
        }
        if (refreshFailed && (data?.hasSavedRemoteIndex == true || data?.photos.orEmpty().isNotEmpty())) {
            Text("No se pudieron actualizar las evidencias.", Modifier.padding(horizontal = 20.dp))
            TextButton(onClick = { reload++ }) { Text("Reintentar") }
        }
        when {
            result == null || data?.photos.isNullOrEmpty() && refreshing ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            result.isFailure -> Column(Modifier.padding(20.dp)) {
                Text("No se pudieron leer las evidencias guardadas.")
                TextButton(onClick = { reload++ }) { Text("Reintentar") }
            }
            data == null || data.photos.isEmpty() -> Column(Modifier.padding(20.dp)) {
                Text(
                    when {
                        data?.hasSavedRemoteIndex == true -> "No hay evidencias para este intento."
                        !online -> "Aún no hay datos guardados. Conéctate para consultar las evidencias."
                        else -> "No se pudieron consultar las evidencias de este intento."
                    },
                )
                if (online && data?.hasSavedRemoteIndex != true) {
                    TextButton(onClick = { reload++ }) { Text("Reintentar") }
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(data.photos, key = { it.id }) { photo ->
                    Column {
                        Text("Paso ${photo.stepIndex + 1}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                photo.file != null && photo.uploaded -> "Subida · guardada en este teléfono"
                                photo.file != null -> "Pendiente de subir"
                                photo.storagePath != null -> if (online) "Subida" else "Requiere conexión"
                                else -> "Archivo local no disponible"
                            },
                        )
                        val open = openPhotoId == photo.id
                        val canOpen = photo.file != null || online && photo.storagePath != null
                        TextButton(
                            enabled = canOpen,
                            onClick = { openPhotoId = if (open) null else photo.id },
                        ) { Text(if (open && canOpen) "Ocultar foto" else "Ver foto") }
                        if (open && canOpen) {
                            EvidenceImage(photo, thumbnails) { path ->
                                repository.download(userId, attemptId, path)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvidenceImage(
    photo: EvidencePhoto,
    thumbnails: EvidenceThumbnails,
    download: suspend (String) -> ByteArray,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val height = with(density) { 360.dp.roundToPx().coerceAtLeast(1) }
        var retry by remember(photo.id) { mutableStateOf(0) }
        val image by produceState<Result<ImageBitmap>?>(null, photo, Pair(width, height), retry) {
            value = null
            value = try {
                Result.success(thumbnails.load(photo, width, height, download).asImageBitmap())
            } catch (_: TimeoutCancellationException) {
                Result.failure(IllegalStateException("Evidence download timed out"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

        val result = image
        when {
            result == null -> CircularProgressIndicator(Modifier.padding(12.dp))
            result.isFailure -> Column {
                Text("No se pudo abrir la foto.")
                TextButton(onClick = { retry++ }) { Text("Reintentar foto") }
            }
            else -> Image(
                bitmap = result.getOrThrow(),
                contentDescription = "Evidencia del paso ${photo.stepIndex + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            )
        }
    }
}
