package com.sidequests.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.AbandonmentReason
import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.Quest
import com.sidequests.app.model.QuestDifficulty
import com.sidequests.app.model.QuestProgress
import com.sidequests.app.model.SidequestsUiState
import com.sidequests.app.model.SocialLevel
import com.sidequests.app.sensor.camera.CameraPhotoProofService
import com.sidequests.app.ui.theme.DiscoveryTeal
import com.sidequests.app.ui.theme.ExplorerIndigo
import com.sidequests.app.ui.theme.QuestAmber
import com.sidequests.app.domain.SmartDifficultyAdvisor
import com.sidequests.app.domain.QuestStatistics
import com.sidequests.app.domain.similarQuests
import com.sidequests.app.domain.hasDestination
import com.sidequests.app.BuildConfig
import com.sidequests.app.telemetry.AppModule
import com.sidequests.app.telemetry.Bq1Telemetry
import com.sidequests.app.telemetry.TelemetryStoreBenchmark
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sidequests.app.ui.auth.PasswordChangeState
import com.sidequests.app.ui.auth.BiometricUi
import java.io.File

@Composable
fun QuestDetailScreen(
    quest: Quest,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = { viewModel.navigate(AppScreen.Explorer) },
    onRequestLocation: () -> Unit = {},
) {
    val deviceStatus by viewModel.deviceStatus.collectAsStateWithLifecycle()
    val appState by viewModel.uiState.collectAsStateWithLifecycle()
    val location by viewModel.contextState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("← Back") }
        }
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (quest.isNew) MetaPill("New", QuestAmber.copy(alpha = .25f))
                    if (quest.isSponsored) MetaPill(quest.sponsorName?.let { "Sponsored by $it" } ?: "Sponsored",
                        QuestAmber.copy(alpha = .28f))
                    if (quest.isGroup) MetaPill("Group quest")
                    Text(quest.category.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
                Text(quest.emoji, fontSize = 36.sp)
                Text(quest.title, style = MaterialTheme.typography.headlineSmall)
                Text(quest.description, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuestFact("⏱", "Duration", quest.duration, Modifier.weight(1f))
                QuestFact("💰", "Est. cost", quest.budget, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuestFact(if (quest.location == LocationMode.Gps) "📍" else "🏠", "Location",
                    quest.locationName ?: quest.distance, Modifier.weight(1f))
                QuestFact("👥", "Social", quest.socialLevel.name, Modifier.weight(1f))
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("Difficulty: ${quest.difficulty.name}", modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        if (quest.hasDestination() || location.context.location != null) item {
            QuestWeatherCard(quest, location.context, deviceStatus.isOnline, viewModel)
        }
        if (quest.hasDestination()) item { QuestCompass(quest, location.context.location, onRequestLocation) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("What you'll do (${quest.steps.size} steps)", style = MaterialTheme.typography.titleMedium)
                if (quest.steps.isEmpty()) {
                    Text("Quest steps are unavailable.")
                    TextButton(onClick = viewModel::refreshQuestCatalog) { Text("Refresh quests") }
                }
                quest.steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier.size(30.dp).clip(CircleShape).background(ExplorerIndigo.copy(alpha = .14f)),
                            contentAlignment = Alignment.Center,
                        ) { Text("${index + 1}", color = ExplorerIndigo, fontWeight = FontWeight.Black) }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(step.title, fontWeight = FontWeight.Bold)
                            Text(step.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                        }
                    }
                }
            }
        }
        item {
            // Same attempt as the Quest tab: an open attempt is continued, never accepted again.
            val openProgress = appState.attemptIdByQuest[quest.id]?.let { viewModel.openAttemptProgress(quest.id) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (openProgress != null) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = DiscoveryTeal.copy(alpha = .12f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("⚡ IN PROGRESS", color = DiscoveryTeal, fontSize = 11.sp, fontWeight = FontWeight.Black)
                            Text(
                                "${openProgress.completedSteps.size} of ${quest.steps.size} steps completed",
                                fontWeight = FontWeight.Bold,
                            )
                            LinearProgressIndicator(
                                progress = { openProgress.completedSteps.size.toFloat() / quest.steps.size.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                color = DiscoveryTeal,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }
                    PrimaryButton(
                        text = "Continue quest →",
                        onClick = { viewModel.resumeQuest(quest.id) },
                        containerColor = DiscoveryTeal,
                    )
                } else if (quest.steps.isEmpty()) {
                    PrimaryButton("Start quest", {}, enabled = false)
                } else if (viewModel.canAcceptQuest(quest)) {
                    if (!deviceStatus.isOnline) {
                        Text(
                            "You're offline. You can still start this quest — your progress is saved on this device and syncs when you reconnect.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    PrimaryButton(
                        text = if (quest.isGroup) "Join group quest →" else "Accept this quest →",
                        onClick = { viewModel.acceptQuest(quest.id) },
                    )
                } else {
                    Text(
                        if (quest.isGroup) "You're offline. Group quests need internet to join."
                        else "You're offline. Connect to the internet to accept this quest.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                appState.progressSyncError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun QuestFact(emoji: String, label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.width(9.dp))
            Column {
                Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
                Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ActiveQuestScreen(
    quest: Quest,
    progress: QuestProgress,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    walking: QuestWalkingUi? = null,
    onRequestLocation: () -> Unit = {},
) {
    val context = LocalContext.current
    val cameraService = remember(context) {
        CameraPhotoProofService(context.applicationContext)
    }
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPhotoStep by rememberSaveable { mutableIntStateOf(-1) }
    var pendingAttemptId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = pendingPhotoPath?.let(::File)
        val stepIndex = pendingPhotoStep
        val attemptId = pendingAttemptId
        val ownerId = pendingOwnerId
        pendingPhotoPath = null
        pendingPhotoStep = -1
        pendingAttemptId = null
        pendingOwnerId = null

        if (captured && file != null && file.length() > 0 && stepIndex >= 0) {
            if (attemptId != null && ownerId != null) {
                viewModel.markPhotoProofCaptured(stepIndex, file.absolutePath, attemptId, ownerId)
            } else cameraService.discardCapture(file)
        } else {
            if (file != null) cameraService.discardCapture(file)
            if (captured && stepIndex >= 0) {
                viewModel.reportPhotoProofError(
                    stepIndex,
                    "The camera did not produce a usable photo. Please try again.",
                )
            }
        }
    }
    val allDone = progress.completedSteps.size >= quest.steps.size
    val deviceStatus by viewModel.deviceStatus.collectAsStateWithLifecycle()
    val appState by viewModel.uiState.collectAsStateWithLifecycle()
    val researchAttempt = viewModel.bq11Attempt(quest)
    if (researchAttempt != null) ObserveBq11Sharing(researchAttempt, viewModel)

    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp).clip(CircleShape).clickable { viewModel.navigate(AppScreen.Explorer) },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ) { Box(contentAlignment = Alignment.Center) { Text("←", fontSize = 22.sp) } }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("ACTIVE QUEST", color = DiscoveryTeal, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    Text(quest.title, style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    "Exit",
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { viewModel.navigate(AppScreen.ExitFlow) }.padding(8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item {
            LinearProgressIndicator(
                progress = { progress.completedSteps.size.toFloat() / quest.steps.size.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = DiscoveryTeal,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        if (walking != null) item { QuestWalkingCard(walking) }
        val step = quest.steps.getOrNull(progress.currentStep)
        if (!allDone && step?.requiresLocation == true) item {
            Column {
                Text(
                    if (quest.hasDestination()) "Automatic location check-in"
                    else "This quest has no saved coordinates. You can complete the step manually.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (quest.hasDestination()) {
                    Text("Allow precise location to verify your arrival.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRequestLocation) { Text("Location access") }
                }
            }
        }
        if (appState.pendingProgressSync > 0) {
            item {
                Text(
                    if (deviceStatus.isOnline) "↻ Syncing your progress…"
                    else "☁️ Progress saved on this device · syncs automatically when you're back online",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                )
            }
        }
        researchAttempt?.let { attempt ->
            item(key = "bq11-research") { Bq11Sharing(attempt, viewModel) }
        }
        items(quest.steps.indices.toList()) { index ->
            val step = quest.steps[index]
            val completed = index in progress.completedSteps
            val active = index == progress.currentStep
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.jumpToStep(index) },
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        completed -> DiscoveryTeal.copy(alpha = .10f)
                        active -> ExplorerIndigo.copy(alpha = .11f)
                        else -> MaterialTheme.colorScheme.surface
                    }
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when {
                        completed -> DiscoveryTeal.copy(alpha = .5f)
                        active -> ExplorerIndigo.copy(alpha = .42f)
                        else -> MaterialTheme.colorScheme.outline
                    }
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier.size(34.dp).clip(CircleShape).background(
                                when {
                                    completed -> DiscoveryTeal
                                    active -> ExplorerIndigo
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (completed) "✓" else "${index + 1}", color = if (completed || active) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(step.title, fontWeight = FontWeight.ExtraBold)
                            Text(step.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f))
                        }
                    }
                    if (active && step.requiresPhoto) {
                        val hasPhotoProof = progress.hasPhotoProof(index)
                        val isUploading = index in progress.photoProofUploadingSteps
                        val isUploaded = index in progress.uploadedPhotoProofByStep
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isUploading) {
                                runCatching { cameraService.createCapture(quest.id, index) }
                                    .onSuccess { capture ->
                                        pendingPhotoPath = capture.file.absolutePath
                                        pendingPhotoStep = capture.stepIndex
                                        pendingAttemptId = viewModel.uiState.value.attemptIdByQuest[quest.id]
                                        pendingOwnerId = viewModel.uiState.value.accountUserId
                                        photoLauncher.launch(capture.uri)
                                    }
                                    .onFailure {
                                        viewModel.reportPhotoProofError(
                                            index,
                                            "Photo proof could not be prepared. Please try again."
                                        )
                                    }
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (hasPhotoProof) DiscoveryTeal.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                when {
                                    isUploading -> "↥ Uploading photo proof…"
                                    isUploaded -> "✓ Photo proof captured and uploaded"
                                    hasPhotoProof -> "✓ Photo proof captured locally"
                                    else -> "📷 Capture photo proof"
                                },
                                modifier = Modifier.padding(14.dp),
                                textAlign = TextAlign.Center,
                                color = if (hasPhotoProof) DiscoveryTeal else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        progress.photoProofErrorByStep[index]?.let { error ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Retry upload",
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clickable { viewModel.retryPhotoProofUpload(index) },
                                color = ExplorerIndigo,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (active && !completed) {
                        // Recomposed when photo uploads (progress) or connectivity change.
                        val decision = viewModel.stepCompletionDecision(index)
                        val finishesQuest = (progress.completedSteps + index).size >= quest.steps.size
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(
                            text = if (finishesQuest) "Complete final step ✓" else "Mark step complete ✓",
                            onClick = { viewModel.completeCurrentStep(appState.attemptIdByQuest[quest.id], index) },
                            enabled = decision.allowed,
                            containerColor = DiscoveryTeal,
                        )
                        // The capture prompt above already explains a missing photo.
                        if (!decision.allowed && (progress.hasPhotoProof(index) || !step.requiresPhoto)) {
                            decision.reason?.let { reason ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
                                )
                                if (deviceStatus.isOnline && progress.photoProofUploadingSteps.isEmpty()) {
                                    Text(
                                        "Retry upload",
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .clickable { viewModel.retryPendingPhotoUploads() },
                                        color = ExplorerIndigo,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (allDone) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = QuestAmber.copy(alpha = .15f)),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎉", fontSize = 38.sp)
                        Text("Quest complete!", style = MaterialTheme.typography.titleLarge)
                        Text("One tiny detour, one new memory.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton("Rate this quest →", { viewModel.navigate(AppScreen.Rating) })
                    }
                }
            }
        }
    }
}

@Composable
fun ExitFlowScreen(
    quest: Quest,
    progress: QuestProgress,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedReason by remember { mutableStateOf<AbandonmentReason?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("PAUSING ${quest.title.uppercase()}", color = ExplorerIndigo, fontSize = 10.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        Text("Life happens. What do you want to do?", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress.completedSteps.size / quest.steps.size.toFloat() },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            color = DiscoveryTeal,
        )
        Spacer(Modifier.height(22.dp))
        Text("If you're ending it, what got in the way?", fontWeight = FontWeight.Bold)
        Text(
            "Choose one reason to start another quest. Saving progress does not abandon this one.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AbandonmentReason.entries.forEach { reason ->
                ChoiceChip(
                    text = reason.label,
                    emoji = reason.emoji,
                    selected = selectedReason == reason,
                    onClick = { selectedReason = if (selectedReason == reason) null else reason },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Save progress & exit", viewModel::saveAndExit)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Keep going", { viewModel.navigate(AppScreen.ActiveQuest) }, containerColor = DiscoveryTeal)
        Spacer(Modifier.height(8.dp))
        Text(
            "Start another quest instead",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = selectedReason != null) {
                    selectedReason?.let(viewModel::startAnotherQuest)
                }
                .padding(12.dp),
            textAlign = TextAlign.Center,
            color = if (selectedReason == null) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = .35f)
            } else {
                ExplorerIndigo
            },
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatingScreen(
    quest: Quest,
    viewModel: AppViewModel,
    state: SidequestsUiState,
    historicalUnavailableIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    var stars by rememberSaveable { mutableIntStateOf(0) }
    var selectedTags by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet()) }
    val tags = listOf("Worth it", "Would do again", "Felt proud", "Relaxing", "Surprising", "A bit tough")
    val catalog = viewModel.allQuests()
    val unavailable = remember(catalog, historicalUnavailableIds, state.progressByQuest, state.attemptIdByQuest) {
        historicalUnavailableIds + catalog.filter { candidate ->
            val progress = state.progressByQuest[candidate.id]
            progress?.abandoned != true && (candidate.id in state.attemptIdByQuest ||
                (candidate.steps.isNotEmpty() && (progress?.completedSteps?.size ?: 0) >= candidate.steps.size))
        }.map { it.id }
    }
    val suggestions by produceState(emptyList<Quest>(), quest, catalog, unavailable, stars, state.availableTime) {
        value = withContext(Dispatchers.Default) {
            similarQuests(quest, catalog, unavailable, stars, state.availableTime)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(quest.emoji, fontSize = 64.sp)
        Text("Quest completed!", color = DiscoveryTeal, style = MaterialTheme.typography.labelLarge)
        Text(quest.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text("How was it?", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            (1..5).forEach { value ->
                IconButton(
                    onClick = { stars = value },
                    modifier = Modifier.weight(1f, fill = false).semantics {
                        contentDescription = if (value == 1) "Rate one star" else "Rate $value stars"
                    },
                ) {
                    Text(
                        if (value <= stars) "★" else "☆",
                        fontSize = 30.sp,
                        color = if (value <= stars) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Tell us more (optional)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Start))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                ChoiceChip(
                    text = tag,
                    selected = tag in selectedTags,
                    onClick = {
                        selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                    },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        if (suggestions.isNotEmpty()) {
            Text("You might also like", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
            suggestions.forEachIndexed { index, suggestion ->
                Spacer(Modifier.height(8.dp))
                Card(
                    onClick = { viewModel.openSimilarQuest(suggestion.id, index + 1) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(suggestion.emoji, fontSize = 24.sp)
                        Column {
                            Text(suggestion.title, style = MaterialTheme.typography.titleSmall)
                            Text("${suggestion.category} · ${suggestion.duration}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Save rating", { viewModel.submitRating(stars, selectedTags) }, enabled = stars > 0)
        TextButton(onClick = viewModel::skipRating) { Text("Skip") }
    }
}

@Composable
fun GroupQuestScreen(
    quest: Quest,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("←", modifier = Modifier.clip(CircleShape).clickable { viewModel.navigate(AppScreen.QuestDetail) }.padding(8.dp), fontSize = 24.sp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("GROUP QUEST", color = ExplorerIndigo, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    Text(quest.title, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = ExplorerIndigo),
                shape = RoundedCornerShape(26.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("📸", fontSize = 44.sp)
                    Text("Everyone gets the same prompts.", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("Meet up, split directions, shoot the prompts, then compare results.", color = Color.White.copy(alpha = .76f))
                }
            }
        }
        item {
            Text("YOUR CREW", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        }
        items(quest.groupMembers) { member ->
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(member.avatar, fontSize = 30.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(member.name, fontWeight = FontWeight.Bold)
                        Text("${member.completedSteps}/${quest.steps.size} prompts complete", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                    }
                    Text(if (member.completedSteps >= quest.steps.size) "✓" else "⚡", color = if (member.completedSteps >= quest.steps.size) DiscoveryTeal else QuestAmber, fontWeight = FontWeight.Black)
                }
            }
        }
        item {
            PrimaryButton("Start my prompts →", { viewModel.navigate(AppScreen.ActiveQuest) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    state: SidequestsUiState,
    viewModel: AppViewModel,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    accountEmail: String? = null,
    displayName: String? = null,
    address: String? = null,
    isOnline: Boolean = true,
    passwordState: PasswordChangeState = PasswordChangeState(),
    statistics: QuestStatistics? = null,
    biometric: BiometricUi? = null,
    biometricEnabled: Boolean = false,
    onDisableBiometrics: () -> Unit = {},
    onChangePassword: (newPassword: String, confirmation: String) -> Unit = { _, _ -> },
    onClearPasswordFeedback: () -> Unit = {},
) {
    val interests = state.preferences.interests
    var showPasswordDialog by rememberSaveable { mutableStateOf(false) }

    if (showPasswordDialog) {
        ChangePasswordDialog(
            state = passwordState,
            isOnline = isOnline,
            onSubmit = onChangePassword,
            onDismiss = {
                showPasswordDialog = false
                onClearPasswordFeedback()
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Text(displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "🧭", fontSize = 26.sp) }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(displayName ?: "Explorer", style = MaterialTheme.typography.titleLarge)
                    Text("📍 " + (address ?: "Location not available yet"), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                }
                Text(
                    if (state.darkMode) "☀️" else "🌙",
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = viewModel::toggleDarkMode).padding(9.dp),
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentStat("Completed", statistics?.completed?.toString() ?: "—", "⚡", ExplorerIndigo)
                AccentStat("Attempted", statistics?.attempts?.toString() ?: "—", "✓")
                AccentStat("Steps walked", statistics?.steps?.toString() ?: "—", "👟")
            }
            TextButton(onClick = { viewModel.navigate(AppScreen.Statistics) }) { Text("View my stats") }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("THEME", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                if (state.lightSensorAvailable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip(
                            text = "Auto (light sensor)",
                            selected = state.autoThemeEnabled,
                            onClick = viewModel::toggleAutoTheme,
                            emoji = "💡",
                        )
                    }
                    Text(
                        "Ambient light: " + (state.ambientLux?.let { "${it.toInt()} lux" } ?: "waiting for sensor…") +
                            " (dark below 15, light above 40)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
                    )
                    Text(
                        if (state.autoThemeEnabled) "Dark mode follows the ambient light around you." else "Tap the moon/sun to choose the theme manually.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
                    )
                } else {
                    Text(
                        "This device has no ambient light sensor, so the theme is manual.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("YOUR INTERESTS", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Outdoors", "Food", "Art", "Learning", "Social", "Movement", "Mindfulness").forEach { interest ->
                        ChoiceChip(
                            text = interest,
                            selected = interest in interests,
                            onClick = { viewModel.toggleInterest(interest) },
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("DIFFICULTY", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuestDifficulty.entries.forEach { difficulty ->
                        ChoiceChip(
                            text = difficulty.name,
                            selected = state.preferences.difficulty == difficulty,
                            onClick = { viewModel.setDifficulty(difficulty) },
                        )
                    }
                }
                val progress = viewModel.difficultyStreaks()
                Text(
                    "🧠 Smart difficulty (${state.preferences.difficulty.name} level): " +
                        "${progress.abandons}/${SmartDifficultyAdvisor.EASIER_AFTER_ABANDONS} left as too hard/long · " +
                        "${progress.completions}/${SmartDifficultyAdvisor.HARDER_AFTER_COMPLETIONS} completed in a row",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
                )
                val lastOutcome = state.difficultyOutcomes.lastOrNull()
                Text(
                    "Recorded attempts: ${state.difficultyOutcomes.size}" + (lastOutcome?.let { o ->
                        " · last: ${o.difficulty.name} " + if (o.completed) "completed" else "left (${o.reason?.label ?: "no reason"})"
                    } ?: ""),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f),
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("SOCIAL VIBE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(SocialLevel.Solo, SocialLevel.Social, SocialLevel.Group).forEach { level ->
                        ChoiceChip(
                            text = level.name,
                            selected = state.preferences.socialLevel == level,
                            onClick = { viewModel.setSocialLevel(level) },
                            selectedColor = DiscoveryTeal,
                        )
                    }
                }
            }
        }
        item {
            Text("ACCOUNT", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
            Text(
                accountEmail ?: "Authenticated user",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f),
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = "Change password",
                onClick = {
                    onClearPasswordFeedback()
                    showPasswordDialog = true
                },
            )
        }
        if (biometric != null && state.accountUserId != null) item {
            Column {
                Text("Biometric unlock", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        biometricEnabled -> "Enabled for this account on this device."
                        biometric.available -> "Use biometrics to open your saved session."
                        else -> "Set up a supported biometric in your device settings."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (biometric.available || biometricEnabled) {
                    TextButton(
                        onClick = {
                            if (biometricEnabled) onDisableBiometrics()
                            else biometric.authenticate(state.accountUserId, true)
                        },
                        enabled = !biometric.busy,
                    ) { Text(if (biometricEnabled) "Turn off biometric unlock" else "Enable biometric unlock") }
                }
                biometric.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("OFFLINE ACCESS", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                Text("Keep selected quests and their steps available when you lose your connection.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                TextButton(onClick = { viewModel.navigate(AppScreen.OfflinePack) }) {
                    Text("Manage Offline Pack  →")
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mission History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.navigate(AppScreen.MissionHistory) }) { Text("View history") }
            }
        }
        item {
            // BQ14 (#87): entry point to the Social comfort view.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Social comfort", style = MaterialTheme.typography.titleMedium)
                    Text("Level: ${state.socialComfort.level.label}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                }
                TextButton(onClick = { viewModel.navigate(AppScreen.SocialComfort) }) { Text("Adjust") }
            }
        }
        item {
            Text("BADGES", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("⚡", "First Quest", ExplorerIndigo),
                    Triple("🧭", "Explorer", DiscoveryTeal),
                    Triple("🔥", "7-Day Streak", QuestAmber),
                ).forEach { (emoji, label, color) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .12f)),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(emoji, fontSize = 26.sp)
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        if (BuildConfig.DEBUG) {
            item { Bq1DebugCard() }
        }
        item {
            PrimaryButton(
                text = "Sign out",
                onClick = onSignOut,
                containerColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Authentication feature: change the password of the signed-in account. */
@Composable
private fun ChangePasswordDialog(
    state: PasswordChangeState,
    isOnline: Boolean,
    onSubmit: (newPassword: String, confirmation: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val done = state.message != null

    AlertDialog(
        onDismissRequest = { if (!state.isBusy) onDismiss() },
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isOnline) {
                    Text(
                        "You're offline. You can type the new password, but it can only be saved once you're connected.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("New password") },
                    singleLine = true,
                    enabled = !state.isBusy && !done,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    enabled = !state.isBusy && !done,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                state.message?.let { Text(it, color = DiscoveryTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                TextButton(
                    onClick = { onSubmit(newPassword, confirmation) },
                    enabled = !state.isBusy,
                ) { Text(if (state.isBusy) "Saving…" else "Save") }
            }
        },
        dismissButton = {
            if (!done) {
                TextButton(onClick = onDismiss, enabled = !state.isBusy) { Text("Cancel") }
            }
        },
    )
}

/** Debug builds only: lets the team verify BQ1 telemetry without waiting 10 minutes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bq1DebugCard() {
    val pending by Bq1Telemetry.pendingCount.collectAsStateWithLifecycle()
    val lastResume by Bq1Telemetry.lastResume.collectAsStateWithLifecycle()
    val benchResult by TelemetryStoreBenchmark.lastResult.collectAsStateWithLifecycle()
    val benchRunning by TelemetryStoreBenchmark.running.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BQ1 TELEMETRY (DEBUG)", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        Text("Events waiting to upload: $pending", fontSize = 12.sp)
        Text(
            lastResume?.let { "Last long resume: ${it.backgroundMillis / 60_000} min in background · quest ${it.questId}" }
                ?: "No resume after >10 min yet",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(text = "Simulate 11-min resume", selected = false, onClick = { Bq1Telemetry.simulateLongResume() })
            ChoiceChip(
                text = "Simulate geocoding failure",
                selected = false,
                onClick = { Bq1Telemetry.report(AppModule.GEOCODING, IllegalStateException("Simulated geocoding failure"), null) },
            )
            ChoiceChip(
                text = "Simulate crash",
                selected = false,
                onClick = { throw IllegalStateException("Simulated BQ1 crash from Profile") },
                selectedColor = MaterialTheme.colorScheme.error,
            )
            ChoiceChip(
                text = if (benchRunning) "Benchmark running…" else "Run SQLite queue benchmark",
                selected = false,
                onClick = { if (!benchRunning) scope.launch { TelemetryStoreBenchmark.run(context) } },
            )
        }
        benchResult?.let {
            Text("Queue benchmark: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
    }
}
