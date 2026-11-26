package com.sidequests.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sidequests.app.data.photo.AttemptEvidence
import com.sidequests.app.data.photo.AttemptEvidenceRepository
import com.sidequests.app.data.photo.EvidencePhoto
import com.sidequests.app.domain.HistoryEntry
import com.sidequests.app.model.QuestStep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collectLatest

private val EvidenceIndigo = Color(0xFF5848D5)
private val EvidenceTeal = Color(0xFF08786F)
private val EvidenceTealSurface = Color(0xFFDEF3F0)
private val ViewerBackground = Color(0xFF100F1C)
private val ViewerPanel = Color(0xFF1A182D)
private val ViewerTile = Color(0xFF23203D)
private val ViewerText = Color(0xFFF0EBFF)
private val ViewerMuted = Color(0xFFCCC4E7)
private val ViewerAccent = Color(0xFFBAAAFE)
private val ViewerImage = Color(0xFF28372E)
private val EvidencePlaceHolder = Color(0xFFDCE9DE)
private val AttemptDateFormat = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH)
private val PhotoDateFormat = DateTimeFormatter.ofPattern("MMM d, uuuu · h:mm a", Locale.ENGLISH)

@Composable
internal fun AttemptEvidenceScreen(
    entry: HistoryEntry,
    steps: List<QuestStep>,
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

    val result = saved
    val data = result?.getOrNull()
    val photos = data?.photos.orEmpty()
    val catalogSteps = steps.indices.toList()
    val photosByStep = photos.groupBy { it.stepIndex }
    val stepIndexes = (catalogSteps + photosByStep.keys).distinct().sorted()
    val totalSteps = stepIndexes.lastOrNull()?.plus(1)?.takeIf { steps.isNotEmpty() }
    var expandedId by remember { mutableStateOf<String?>(null) }
    val download: suspend (String) -> ByteArray = { repository.download(userId, attemptId, it) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EvidenceBack(false, "Back to Mission History", onBack)
            Column {
                SectionLabel("MISSION HISTORY", MaterialTheme.colorScheme.primary)
                Text("Attempt evidence", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        AttemptCard(entry, totalSteps, if (data == null) null else photos.size)
        Spacer(Modifier.height(14.dp))
        when {
            result?.isFailure == true -> EvidenceNotice(
                "Evidence unavailable", "The saved evidence could not be read.", onRetry = { reload++ },
            )
            !online -> EvidenceNotice(
                "Offline",
                if (photos.isEmpty() && data?.hasSavedRemoteIndex != true) {
                    "Connect to look up evidence for this attempt."
                } else "Photos saved on this phone are available.",
            )
            refreshFailed -> EvidenceNotice(
                "Could not refresh",
                if (data?.hasSavedRemoteIndex == true || photos.isNotEmpty()) "Showing saved evidence."
                else "Check your connection and try again.",
                onRetry = { reload++ },
            )
            result == null || refreshing -> EvidenceNotice("Checking evidence", "Looking for photos from this attempt.")
            photos.isEmpty() -> EvidenceNotice("No photos", "This attempt has no photo evidence.")
            else -> {
                val pending = photos.count { !it.uploaded && it.file != null }
                val uploaded = photos.count { it.uploaded }
                val savedOnPhone = photos.count { it.file != null }
                when {
                    pending > 0 -> EvidenceNotice(
                        "Upload pending", photoCount(pending) + " waiting to upload.",
                    )
                    uploaded == photos.size -> EvidenceNotice(
                        "All synced",
                        photoCount(uploaded) + " uploaded" +
                            if (savedOnPhone > 0) " · ${photoCount(savedOnPhone)} saved on this phone" else "",
                        synced = true,
                    )
                    else -> EvidenceNotice("Some photos are unavailable", "An evidence file is missing from this phone.")
                }
            }
        }
        if (stepIndexes.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionLabel("QUEST JOURNEY", MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    stepIndexes.forEachIndexed { index, stepIndex ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        val step = steps.getOrNull(stepIndex)
                        val stepPhotos = photosByStep[stepIndex].orEmpty()
                        EvidenceStep(
                            stepIndex = stepIndex,
                            step = step,
                            photos = stepPhotos,
                            completed = entry.attempt.status == "completed",
                            online = online,
                            thumbnails = thumbnails,
                            download = download,
                            onOpen = { expandedId = it },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
    }

    photos.firstOrNull { it.id == expandedId }?.let { photo ->
        EvidenceViewer(
            photo = photo,
            step = steps.getOrNull(photo.stepIndex),
            totalSteps = totalSteps,
            questTitle = entry.title,
            online = online,
            thumbnails = thumbnails,
            download = download,
            onClose = { expandedId = null },
        )
    }
}

@Composable
private fun AttemptCard(entry: HistoryEntry, totalSteps: Int?, photoTotal: Int?) {
    val completed = entry.attempt.status == "completed"
    val status = if (completed) "Completed" else "Abandoned"
    val category = entry.quest?.category?.let(::evidenceCategory)
    val date = entry.date.format(AttemptDateFormat)
    val steps = when {
        totalSteps != null && completed -> "$totalSteps/$totalSteps"
        totalSteps != null && entry.steps != null && entry.steps in 0..totalSteps -> "${entry.steps}/$totalSteps"
        else -> "—"
    }
    Surface(color = EvidenceIndigo, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.17f)),
                    contentAlignment = Alignment.Center,
                ) { Text(entry.quest?.emoji ?: "🎯", fontSize = 22.sp) }
                EvidenceChip(if (completed) "✓  $status" else status, Color.White, Color(0xFF4030A7))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(date, category).joinToString(" · "), color = Color(0xFFEAE5FF))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AttemptStat(steps, "Steps", Modifier.weight(1f))
                AttemptStat(photoTotal?.toString() ?: "—", "Photos", Modifier.weight(1f))
                AttemptStat(entry.quest?.let { "${it.durationMinutes} min" } ?: "—", "Duration", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AttemptStat(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = 0.16f)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFFEAE5FF))
        }
    }
}

@Composable
private fun EvidenceNotice(title: String, details: String, synced: Boolean = false, onRetry: (() -> Unit)? = null) {
    val tone = if (synced) Color(0xFF08786F) else MaterialTheme.colorScheme.primary
    val titleTone = if (synced && MaterialTheme.colorScheme.surface.luminance() < 0.25f) {
        Color(0xFF72DDD0)
    } else tone
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.27f)),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EvidenceMark(
                    if (synced) EvidenceIcon.Check else EvidenceIcon.Info,
                    if (synced) EvidenceTealSurface else MaterialTheme.colorScheme.secondaryContainer,
                    if (synced) EvidenceTeal else MaterialTheme.colorScheme.onSecondaryContainer,
                    36,
                )
                Column(Modifier.weight(1f)) {
                    Text(title, color = titleTone, fontWeight = FontWeight.Bold)
                    Text(details, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun EvidenceStep(
    stepIndex: Int,
    step: QuestStep?,
    photos: List<EvidencePhoto>,
    completed: Boolean,
    online: Boolean,
    thumbnails: EvidenceThumbnails,
    download: suspend (String) -> ByteArray,
    onOpen: (String) -> Unit,
) {
    val stepTitle = step?.title ?: "Step ${stepIndex + 1}"
    val photoStep = step?.requiresPhoto == true || photos.isNotEmpty()
    val detail = when {
        photoStep -> "Photo evidence"
        step != null -> "No evidence required"
        else -> null
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                photoStep -> EvidenceMark(EvidenceIcon.Camera, EvidenceIndigo, Color.White, 28)
                completed -> EvidenceMark(EvidenceIcon.Check, EvidenceTealSurface, EvidenceTeal, 28)
                else -> EvidenceMark(EvidenceIcon.Info, MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer, 28)
            }
            Column(Modifier.weight(1f)) {
                Text(stepTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull("Step ${stepIndex + 1}", detail).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        photos.forEach { photo ->
            key(photo.id) {
                Spacer(Modifier.height(11.dp))
                EvidencePreview(photo, online, thumbnails, download, onOpen = { onOpen(photo.id) })
            }
        }
        if (photoStep && photos.isEmpty()) {
            Text(
                "No photo available",
                modifier = Modifier.padding(start = 38.dp, top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EvidencePreview(
    photo: EvidencePhoto,
    online: Boolean,
    thumbnails: EvidenceThumbnails,
    download: suspend (String) -> ByteArray,
    onOpen: () -> Unit,
) {
    val canOpen = photo.canOpen(online)
    val height = 180.dp
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(height).clip(shape).background(EvidencePlaceHolder)
            .clickable(enabled = canOpen, onClickLabel = "View photo", onClick = onOpen),
    ) {
        val density = LocalDensity.current
        val result = rememberEvidenceImage(
            photo, with(density) { maxWidth.roundToPx() }, with(density) { height.roundToPx() },
            enabled = canOpen, retry = 0, thumbnails = thumbnails, download = download,
        )
        val image = result?.getOrNull()
        when {
            image != null -> Image(image, "Evidence for step ${photo.stepIndex + 1}", Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
            canOpen && result == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp), color = EvidenceTeal)
            else -> EvidenceMark(EvidenceIcon.Camera, Color.Transparent, Color(0xFF335144), 44, Modifier.align(Alignment.Center))
        }
        EvidenceChip(
            text = photo.label(online),
            background = Color(0xE6242631),
            foreground = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
        )
        if (canOpen) {
            EvidenceMark(
                EvidenceIcon.Expand, Color(0xE6242631), Color.White, 34,
                Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val date = photo.captureOrUploadDate()
        Text(
            date?.let { "${it.first} ${it.second}" } ?: "Capture date unavailable",
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canOpen) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
                TextButton(onClick = onOpen) {
                    Text("View photo  ›", color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EvidenceViewer(
    photo: EvidencePhoto,
    step: QuestStep?,
    totalSteps: Int?,
    questTitle: String,
    online: Boolean,
    thumbnails: EvidenceThumbnails,
    download: suspend (String) -> ByteArray,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = ViewerBackground, contentColor = ViewerText) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val photoHeight = (maxHeight * 0.62f).coerceIn(220.dp, 640.dp)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EvidenceBack(true, "Close full-screen photo", onClose)
                        Column {
                            SectionLabel(
                                "STEP ${photo.stepIndex + 1}" + (totalSteps?.let { " OF $it" } ?: ""),
                                ViewerAccent,
                            )
                            Text(step?.title ?: "Photo evidence", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    ViewerPhoto(photo, online, photoHeight, thumbnails, download)
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        Modifier.fillMaxWidth(),
                        color = ViewerPanel,
                        contentColor = ViewerText,
                        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (photo.uploaded) EvidenceChip("Uploaded to cloud", Color(0xFF193642), Color(0xFF79DED4))
                                if (photo.file != null) EvidenceChip("Saved on this phone", Color(0xFF2D2852), ViewerMuted)
                                if (!photo.uploaded && photo.file != null) {
                                    EvidenceChip("Upload pending", Color(0xFF3B2A27), Color(0xFFFFC998))
                                }
                            }
                            step?.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = ViewerMuted, style = MaterialTheme.typography.bodyMedium)
                            }
                            val date = photo.captureOrUploadDate()
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ViewerStat(date?.first?.uppercase(Locale.ENGLISH) ?: "CAPTURED",
                                    date?.second ?: "Date unavailable", Modifier.weight(1f))
                                ViewerStat("QUEST", questTitle, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerPhoto(
    photo: EvidencePhoto,
    online: Boolean,
    height: androidx.compose.ui.unit.Dp,
    thumbnails: EvidenceThumbnails,
    download: suspend (String) -> ByteArray,
) {
    val canOpen = photo.canOpen(online)
    var retry by remember(photo.id) { mutableStateOf(0) }
    var zoom by remember(photo.id) { mutableFloatStateOf(1f) }
    var offset by remember(photo.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember(photo.id) { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { _, zoomChange, pan, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 3f)
        val maxX = viewport.width * (zoom - 1f) / 2f
        val maxY = viewport.height * (zoom - 1f) / 2f
        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(height)
            .clip(RoundedCornerShape(22.dp)).background(ViewerImage)
            .onSizeChanged { viewport = it },
    ) {
        val density = LocalDensity.current
        val result = rememberEvidenceImage(
            photo, with(density) { maxWidth.roundToPx() }, with(density) { height.roundToPx() },
            canOpen, retry, thumbnails, download,
        )
        val image = result?.getOrNull()
        if (image != null) {
            Image(
                image, "Full-screen evidence for step ${photo.stepIndex + 1}",
                Modifier.fillMaxSize().pointerInput(photo.id) {
                    detectTapGestures(onDoubleTap = {
                        zoom = if (zoom > 1f) 1f else 2f
                        offset = Offset.Zero
                    })
                }.transformable(transform, enabled = zoom > 1f).graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            )
            EvidenceChip(
                if (zoom > 1f) "Double tap to reset" else "Double tap to zoom",
                Color(0xE6100F1C), ViewerText, Modifier.align(Alignment.TopStart).padding(10.dp),
            )
        } else if (canOpen && result == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = ViewerAccent)
        } else {
            Column(Modifier.align(Alignment.Center).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (canOpen) "This photo could not be opened." else "Connect to open this photo.",
                    textAlign = TextAlign.Center, color = ViewerText,
                )
                if (canOpen) TextButton(onClick = { retry++ }) { Text("Retry photo", color = ViewerAccent) }
            }
        }
    }
}

@Composable
private fun rememberEvidenceImage(
    photo: EvidencePhoto,
    width: Int,
    height: Int,
    enabled: Boolean,
    retry: Int,
    thumbnails: EvidenceThumbnails,
    download: suspend (String) -> ByteArray,
): Result<ImageBitmap>? {
    val source = photo.file?.path ?: photo.storagePath
    val result by produceState<Result<ImageBitmap>?>(null, source, width, height, enabled, retry) {
        value = null
        if (!enabled) return@produceState
        value = try {
            Result.success(thumbnails.load(photo, width.coerceAtLeast(1), height.coerceAtLeast(1), download).asImageBitmap())
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    return if (enabled) result else null
}

@Composable
private fun ViewerStat(label: String, value: String, modifier: Modifier) {
    Surface(modifier, color = ViewerTile, contentColor = ViewerText, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SectionLabel(label, ViewerMuted)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.05.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun EvidenceChip(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = background, shape = CircleShape) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = foreground,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EvidenceBack(dark: Boolean, description: String, onBack: () -> Unit) {
    val background = if (dark) Color(0xFF272534) else MaterialTheme.colorScheme.surface
    val color = if (dark) ViewerText else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = CircleShape,
        color = background,
        border = if (dark) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = description }) {
            Glyph(EvidenceIcon.Back, color, Modifier.size(20.dp))
        }
    }
}

private enum class EvidenceIcon { Back, Check, Camera, Expand, Info }

@Composable
private fun EvidenceMark(icon: EvidenceIcon, bg: Color, fg: Color, size: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Glyph(icon, fg, Modifier.size((size * 0.5f).dp))
    }
}

@Composable
private fun Glyph(icon: EvidenceIcon, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x: Float, y: Float, endX: Float, endY: Float) =
            drawLine(color, point(x, y), point(endX, endY), 2.5f * unit, StrokeCap.Round)
        when (icon) {
            EvidenceIcon.Back -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
            EvidenceIcon.Check -> { line(4f, 12f, 9f, 17f); line(9f, 17f, 20f, 6f) }
            EvidenceIcon.Camera -> {
                drawRoundRect(color, point(2f, 6f), Size(20f * unit, 15f * unit),
                    CornerRadius(3f * unit), style = Stroke(2f * unit))
                drawCircle(color, 3.6f * unit, point(12f, 13f), style = Stroke(2f * unit))
                line(9f, 3.5f, 15f, 3.5f)
            }
            EvidenceIcon.Expand -> {
                line(14f, 10f, 21f, 3f); line(16f, 3f, 21f, 3f); line(21f, 3f, 21f, 8f)
                line(10f, 14f, 3f, 21f); line(3f, 16f, 3f, 21f); line(3f, 21f, 8f, 21f)
            }
            EvidenceIcon.Info -> {
                drawCircle(color, 10f * unit, point(12f, 12f), style = Stroke(2f * unit))
                drawCircle(color, 1.3f * unit, point(12f, 7f))
                line(12f, 11f, 12f, 16f)
            }
        }
    }
}

private fun EvidencePhoto.canOpen(online: Boolean): Boolean = file != null || online && storagePath != null

private fun EvidencePhoto.label(online: Boolean): String = when {
    file != null && uploaded -> "Uploaded · on this phone"
    file != null -> "Upload pending"
    storagePath != null -> if (online) "Uploaded to cloud" else "Connection required"
    else -> "Local file unavailable"
}

private fun EvidencePhoto.captureOrUploadDate(): Pair<String, String>? =
    formattedEvidenceInstant(capturedAt)?.let { "Captured" to it }
        ?: formattedEvidenceInstant(uploadedAt)?.let { "Uploaded" to it }

private fun formattedEvidenceInstant(value: String?): String? = value?.let {
    runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(PhotoDateFormat) }.getOrNull()
}

private fun photoCount(count: Int): String = if (count == 1) "1 photo" else "$count photos"

private fun evidenceCategory(category: String): String = when (category.lowercase(Locale.ROOT)) {
    "outdoors" -> "Outdoors"
    "food" -> "Food"
    "movement" -> "Movement"
    "art" -> "Art"
    "culture" -> "Culture"
    "wellness" -> "Wellness"
    "social" -> "Social"
    else -> category.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ENGLISH) }
}
