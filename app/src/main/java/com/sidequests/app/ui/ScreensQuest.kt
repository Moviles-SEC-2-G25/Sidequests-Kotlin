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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.sidequests.app.BuildConfig
import com.sidequests.app.telemetry.AppModule
import com.sidequests.app.telemetry.Bq1Telemetry
import com.sidequests.app.ui.auth.PasswordChangeState
import java.io.File

@Composable
fun QuestDetailScreen(
    quest: Quest,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val deviceStatus by viewModel.deviceStatus.collectAsStateWithLifecycle()
    val appState by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp).clip(CircleShape).clickable { viewModel.navigate(AppScreen.Explorer) },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ) { Box(contentAlignment = Alignment.Center) { Text("←", fontSize = 22.sp) } }
                Spacer(Modifier.width(12.dp))
                Text("Quest details", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = ExplorerIndigo),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(22.dp)) {
                    Text(quest.emoji, fontSize = 52.sp)
                    Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (quest.isNew) MetaPill("NEW", QuestAmber.copy(alpha = .25f))
                        if (quest.isSponsored) MetaPill("SPONSORED", QuestAmber.copy(alpha = .28f))
                        if (quest.isGroup) MetaPill("GROUP QUEST", Color.White.copy(alpha = .18f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(quest.title, color = Color.White, style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(quest.description, color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.bodyMedium)
                }
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
                QuestFact(if (quest.location == LocationMode.Gps) "📍" else "🏠", "Location", quest.distance, Modifier.weight(1f))
                QuestFact("👥", "Vibe", quest.socialLevel.name, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuestFact("🎯", "Difficulty", quest.difficulty.name, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("WHAT YOU'LL DO", fontSize = 11.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
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
            if (!deviceStatus.isOnline) {
                Text(
                    "You're offline. You can browse this quest, but accepting it requires internet.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                PrimaryButton(
                    text = if (quest.isGroup) "Join group quest →" else "Accept this quest →",
                    onClick = { viewModel.acceptQuest(quest.id) },
                )
                appState.progressSyncError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
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
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(
                            text = if (index == quest.steps.lastIndex) "Complete final step ✓" else "Mark step complete ✓",
                            onClick = viewModel::completeCurrentStep,
                            enabled = !step.requiresPhoto || progress.hasPhotoProof(index),
                            containerColor = DiscoveryTeal,
                        )
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
    modifier: Modifier = Modifier,
) {
    var stars by remember { mutableIntStateOf(4) }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    val moods = listOf("😐", "🙂", "😊", "😄", "🤩")
    val tags = listOf("Worth it", "Would do again", "Felt proud", "Relaxing", "Surprising", "A bit tough")

    Column(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(22.dp))
        Text("QUEST COMPLETE", color = DiscoveryTeal, fontSize = 10.sp, fontWeight = FontWeight.Black)
        Text("How did it feel?", style = MaterialTheme.typography.headlineMedium)
        Text(quest.title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            moods.forEachIndexed { index, mood ->
                val value = index + 1
                Text(
                    mood,
                    modifier = Modifier.size(if (stars == value) 52.dp else 44.dp).clip(CircleShape).background(if (stars == value) QuestAmber.copy(alpha = .2f) else Color.Transparent).clickable { stars = value }.padding(7.dp),
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(26.dp))
        Text("WHAT STOOD OUT?", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                ChoiceChip(
                    text = tag,
                    selected = tag in selectedTags,
                    onClick = {
                        selectedTags = selectedTags.toMutableSet().apply {
                            if (!add(tag)) remove(tag)
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = DiscoveryTeal.copy(alpha = .11f)),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(
                "Your feedback improves future recommendations. This is the smart-feature feedback loop from the Figma prototype.",
                modifier = Modifier.padding(16.dp),
                color = DiscoveryTeal,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Submit & Explore More →", { viewModel.submitRating(stars, selectedTags) })
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
    address: String? = null,
    isOnline: Boolean = true,
    passwordState: PasswordChangeState = PasswordChangeState(),
    onChangePassword: (newPassword: String, confirmation: String) -> Unit = { _, _ -> },
    onClearPasswordFeedback: () -> Unit = {},
) {
    val completed = state.progressByQuest.values.count { it.completedSteps.size >= 4 }
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
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(ExplorerIndigo),
                    contentAlignment = Alignment.Center,
                ) { Text("🧭", fontSize = 34.sp) }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Explorer", style = MaterialTheme.typography.titleLarge)
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
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                AccentStat("Completed", "$completed", "⚡", ExplorerIndigo)
                AccentStat("Day streak", "7", "🔥", QuestAmber)
                AccentStat("Categories", "${interests.size}", "🗺️", DiscoveryTeal)
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
        }
    }
}
