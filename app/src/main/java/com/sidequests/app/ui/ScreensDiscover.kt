package com.sidequests.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.sidequests.app.model.AppScreen
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidequests.app.model.QuestDifficulty
import com.sidequests.app.context.ContextUiState
import com.sidequests.app.context.TimeOfDay
import com.sidequests.app.context.label
import com.sidequests.app.domain.CompletionLikelihood
import com.sidequests.app.domain.QuestStatistics
import com.sidequests.app.domain.exploreAdaptationMessage
import com.sidequests.app.model.Quest
import com.sidequests.app.model.SidequestsUiState
import com.sidequests.app.model.SocialLevel
import com.sidequests.app.ui.theme.DiscoveryTeal
import com.sidequests.app.ui.theme.ExplorerIndigo
import com.sidequests.app.ui.theme.QuestAmber

private val interestOptions = listOf("Outdoors", "Food", "Art", "Learning", "Social", "Movement", "Mindfulness")
private val timeOptions = listOf(10, 20, 30, 45, 60)
private val budgetOptions = listOf(0, 5, 15, 30)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    state: SidequestsUiState,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val step = state.onboardingStep
    val title = when (step) {
        0 -> "What do you love doing?"
        1 -> "How adventurous are you feeling?"
        2 -> "How much time do you usually have?"
        else -> "Budget & vibe?"
    }
    val subtitle = when (step) {
        0 -> "Pick as many as you like — or skip."
        1 -> "This shapes the challenge level of your quests."
        2 -> "We'll match quests to your typical free slot."
        else -> "Last one — promise."
    }

    Column(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 24.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { index ->
                    LinearProgressIndicator(
                        progress = { if (index <= step) 1f else 0f },
                        modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
                        color = ExplorerIndigo,
                        trackColor = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = "STEP ${step + 1} OF 4",
                color = ExplorerIndigo,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.3.sp,
            )
            Spacer(Modifier.height(7.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(7.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
        }

        Spacer(Modifier.height(26.dp))
        Box(modifier = Modifier.weight(1f).padding(horizontal = 24.dp)) {
            when (step) {
                0 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    interestOptions.forEach { interest ->
                        ChoiceChip(
                            text = interest,
                            selected = interest in state.preferences.interests,
                            onClick = { viewModel.toggleInterest(interest) },
                        )
                    }
                }

                1 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DifficultyOption("🌱", QuestDifficulty.Easy, "Low-effort, zero planning, instant fun", state, viewModel)
                    DifficultyOption("⚡", QuestDifficulty.Medium, "A bit of effort, noticeably rewarding", state, viewModel)
                    DifficultyOption("🔥", QuestDifficulty.Hard, "Takes commitment — worth it every time", state, viewModel)
                }

                2 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    timeOptions.forEach { minutes ->
                        ChoiceChip(
                            text = if (minutes == 60) "1 hr+" else "$minutes min",
                            selected = state.preferences.typicalTime == minutes,
                            selectedColor = QuestAmber,
                            onClick = { viewModel.setTypicalTime(minutes) },
                        )
                    }
                }

                else -> Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("MAX SPEND PER QUEST", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            budgetOptions.forEach { amount ->
                                val label = when (amount) {
                                    0 -> "Free only"
                                    30 -> "\$30+"
                                    else -> "\$$amount"
                                }
                                ChoiceChip(
                                    text = label,
                                    selected = state.preferences.budgetMax == amount,
                                    selectedColor = DiscoveryTeal,
                                    onClick = { viewModel.setBudget(amount) },
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("SOCIAL VIBE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                Triple(SocialLevel.Solo, "🎧", "Solo"),
                                Triple(SocialLevel.Social, "👥", "Social"),
                                Triple(SocialLevel.Group, "🎉", "Group"),
                            ).forEach { (level, emoji, label) ->
                                Card(
                                    modifier = Modifier.weight(1f).clickable { viewModel.setSocialLevel(level) },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (state.preferences.socialLevel == level) ExplorerIndigo else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(emoji, fontSize = 24.sp)
                                        Text(
                                            label,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = if (state.preferences.socialLevel == level) Color.White else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = if (step < 3) "Continue →" else "Start Exploring →",
                onClick = viewModel::nextOnboardingStep,
                enabled = !state.onboardingSaving,
            )
            Text(
                text = "Skip setup — I'll explore freely",
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !state.onboardingSaving, onClick = viewModel::skipOnboarding).padding(10.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun DifficultyOption(
    emoji: String,
    difficulty: QuestDifficulty,
    description: String,
    state: SidequestsUiState,
    viewModel: AppViewModel,
) {
    val selected = state.preferences.difficulty == difficulty
    Card(
        modifier = Modifier.fillMaxWidth().clickable { viewModel.setDifficulty(difficulty) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) ExplorerIndigo else MaterialTheme.colorScheme.surface),
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 30.sp)
            Spacer(Modifier.size(14.dp))
            Column {
                Text(difficulty.name, fontWeight = FontWeight.ExtraBold, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
                Text(description, fontSize = 12.sp, color = if (selected) Color.White.copy(alpha = .74f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ExplorerScreen(
    state: SidequestsUiState,
    viewModel: AppViewModel,
    contextState: ContextUiState,
    onRequestLocation: () -> Unit,
    modifier: Modifier = Modifier,
    statistics: QuestStatistics? = null,
    displayName: String? = null,
) {
    val recommendations = viewModel.recommendations()
    // Re-evaluated on every recomposition triggered by a new state.
    val difficultySuggestion = viewModel.difficultySuggestion()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.applySmartAvailableTime()
        viewModel.refreshContextIfStale()
    }
    val filtered = viewModel.exploreQuests()
    val adaptationMessage = contextState.context.exploreAdaptationMessage()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    fun surprise() {
        val message = viewModel.openSurpriseQuest(statistics?.completedQuestIds.orEmpty())
        if (message == null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        else scope.launch { snackbars.showSnackbar(message) }
    }
    val shakingAvailable = rememberSurpriseQuestGesture(::surprise)

    fun progressLabel(questId: String): String? = viewModel.openAttemptProgress(questId)?.let { progress ->
        val total = viewModel.allQuests().firstOrNull { it.id == questId }?.steps?.size ?: return@let null
        "In progress · ${progress.completedSteps.size}/$total"
    }
    fun completionLabel(quest: Quest): String? = statistics?.let {
        (it.categories[quest.category.trim().lowercase()]?.likelihood ?: CompletionLikelihood.NewForYou).label
    }
    val activeQuest = if (viewModel.hasActiveQuest()) viewModel.activeQuest() else null
    val activeProgress = activeQuest?.let { viewModel.openAttemptProgress(it.id) }

    val explorerListState = rememberLazyListState()
    // Do not count a recommendation as an impression just because the RPC returned.
    // LazyColumn may precompose offscreen content; use its actual visible item keys.
    LaunchedEffect(explorerListState, state.bq8FreshBatchId, state.remoteRecommendationIds) {
        snapshotFlow {
            explorerListState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                (item.key as? String)
                    ?.takeIf { it.startsWith("rec-") }
                    ?.removePrefix("rec-")
            }
        }.collect { visibleIds ->
            visibleIds.forEach(viewModel::smartPickVisible)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = explorerListState,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (activeQuest != null && activeProgress != null) {
                item(key = "active-quest") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp)
                            .clickable { viewModel.resumeQuest(activeQuest.id) },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = DiscoveryTeal.copy(alpha = .14f)),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(activeQuest.emoji, fontSize = 30.sp)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("YOUR ACTIVE QUEST", color = DiscoveryTeal, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                Text(activeQuest.title, fontWeight = FontWeight.Bold)
                                LinearProgressIndicator(
                                    progress = { activeProgress.completedSteps.size.toFloat() / activeQuest.steps.size.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                                    color = DiscoveryTeal,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                Text(
                                    "${activeProgress.completedSteps.size} of ${activeQuest.steps.size} steps · tap to continue",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                                )
                            }
                        }
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            val greeting = when (contextState.context.timeOfDay) {
                                TimeOfDay.MORNING -> "Good morning"
                                TimeOfDay.AFTERNOON -> "Good afternoon"
                                TimeOfDay.EVENING, TimeOfDay.NIGHT -> "Good evening"
                            }
                            Text(displayName?.let { "$greeting, $it" } ?: greeting, style = MaterialTheme.typography.bodyMedium)
                            Text("What do you feel like today?", style = MaterialTheme.typography.headlineSmall)
                        }
                        Surface(
                            modifier = Modifier.size(42.dp).clip(CircleShape).clickable(onClick = viewModel::toggleDarkMode),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape,
                        ) {
                            Box(contentAlignment = Alignment.Center) { Text(if (state.darkMode) "☀️" else "🌙", fontSize = 18.sp) }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("I HAVE...", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(15, 20, 30, 45, 60)) { minutes ->
                            ChoiceChip(
                                text = "$minutes min",
                                selected = state.availableTime == minutes,
                                onClick = { viewModel.setAvailableTime(minutes) },
                            )
                        }
                    }
                    state.smartTimeHint?.let { hint ->
                        Spacer(Modifier.height(6.dp))
                        Text(hint, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = QuestAmber)
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChoiceChip("🌐 All", state.preferences.locationMode == "all", { viewModel.setLocationMode("all") })
                        ChoiceChip("📍 Nearby", state.preferences.locationMode == "gps", { viewModel.setLocationMode("gps") }, selectedColor = DiscoveryTeal)
                        ChoiceChip("🏠 Anywhere", state.preferences.locationMode == "anywhere", { viewModel.setLocationMode("anywhere") }, selectedColor = DiscoveryTeal)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Social vibe for this session", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            ChoiceChip(
                                "Profile · ${state.preferences.socialLevel.name}",
                                state.sessionSocialLevel == null,
                                { viewModel.setSessionSocialLevel(null) },
                            )
                        }
                        items(SocialLevel.entries) { level ->
                            ChoiceChip(level.name, state.sessionSocialLevel == level, { viewModel.setSessionSocialLevel(level) })
                        }
                    }
                }
            }

            item {
                ContextAwareCard(
                    contextState = contextState,
                    locationMode = state.preferences.locationMode,
                    onRequestLocation = onRequestLocation,
                    onRefresh = { viewModel.refreshContext(force = true) },
                )
            }

            item(key = "discover-more") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Discover more", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DiscoverShortcut("🔀", "Try variety", "Explore a new category",
                            Modifier.weight(1f)) { viewModel.navigate(com.sidequests.app.model.AppScreen.VarietyDiscovery) }
                        DiscoverShortcut("👥", "Group challenges", "Explore together",
                            Modifier.weight(1f)) { viewModel.navigate(com.sidequests.app.model.AppScreen.GroupChallenges) }
                    }
                    TextButton(onClick = ::surprise) { Text("Surprise me") }
                    if (shakingAvailable) Text("Shake your phone for a surprise quest.", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (adaptationMessage != null) item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(adaptationMessage, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            difficultySuggestion?.let { suggestion ->
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = QuestAmber.copy(alpha = .16f)),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("SMART DIFFICULTY", fontSize = 10.sp, fontWeight = FontWeight.Black, color = QuestAmber, letterSpacing = 1.1.sp)
                            Text(suggestion.explanation, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChoiceChip(
                                    text = "Switch to ${suggestion.suggested.name}",
                                    selected = true,
                                    onClick = viewModel::acceptDifficultySuggestion,
                                    selectedColor = QuestAmber,
                                )
                                ChoiceChip(
                                    text = "Keep ${suggestion.current.name}",
                                    selected = false,
                                    onClick = viewModel::dismissDifficultySuggestion,
                                )
                            }
                        }
                    }
                }
            }

            // Keep the catalogue visible during an outage, but let users retry
            // as soon as internet comes back without restarting Sidequests.
            if (state.catalogError != null && !state.catalogLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Couldn't update quests. You can try again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::refreshQuestCatalog) {
                            Text("Try again")
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ExplorerIndigo),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("SMART PICKS FOR YOU", color = QuestAmber, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Matched to your time, budget and vibe", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = when {
                                state.catalogLoading -> "Syncing quest catalogue…"
                                state.catalogError != null -> "${state.catalogSource} · remote refresh unavailable"
                                else -> state.catalogSource
                            },
                            color = Color.White.copy(alpha = .78f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when {
                                state.recommendationLoading -> "Refreshing shared BQ5 recommendations…"
                                state.recommendationError != null -> "Recommendation fallback · ${state.recommendationError}"
                                else -> state.recommendationSource
                            },
                            color = Color.White.copy(alpha = .78f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        // BQ14 (#87): tells the user Smart Picks are filtered and opens the view.
                        val adaptation = viewModel.socialAdaptation(state)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (adaptation.active) {
                                "Adapted to your social comfort (${state.socialComfort.level.label}) · Change"
                            } else {
                                "Social comfort: ${state.socialComfort.level.label} · Adjust"
                            },
                            color = QuestAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { viewModel.navigate(AppScreen.SocialComfort) },
                        )
                    }
                }
            }

            item {
                if (com.sidequests.app.BuildConfig.DEBUG && state.bq8FreshBatchId != null) {
                    Text(
                        text = "BQ8 test · server arm: ${state.bq8Variant ?: "unassigned"}",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            items(if (state.catalogLoading || state.recommendationLoading) emptyList() else recommendations, key = { "rec-${it.id}" }) { quest ->
                if (quest.id == recommendations.firstOrNull()?.id && !state.catalogLoading && !state.recommendationLoading) {
                    LaunchedEffect(quest.id) { viewModel.firstOnboardingRecommendationVisible(quest.id) }
                }
                QuestCard(
                    quest = quest,
                    onOpen = { viewModel.openQuestDetail(quest.id, fromSmartPicks = true) },
                    onSkip = { viewModel.skipQuest(quest.id) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                    progressLabel = progressLabel(quest.id),
                    onStartNow = { viewModel.startInstantPlan(quest.id) },
                    startNowEnabled = quest.steps.isNotEmpty() &&
                        (viewModel.canAcceptQuest(quest) || viewModel.openAttemptProgress(quest.id) != null),
                    completionLabel = completionLabel(quest),
                )
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text("EXPLORE BY CATEGORY", fontSize = 11.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(viewModel.categories) { category ->
                            ChoiceChip(
                                text = category,
                                selected = state.selectedCategory == category,
                                onClick = { viewModel.setCategory(category) },
                                selectedColor = if (category == "All") ExplorerIndigo else DiscoveryTeal,
                            )
                        }
                    }
                }
            }

            items(filtered, key = { "all-${it.id}" }) { quest ->
                QuestCard(
                    quest = quest,
                    onOpen = { viewModel.openQuestDetail(quest.id) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                    progressLabel = progressLabel(quest.id),
                    completionLabel = completionLabel(quest),
                )
            }
            if (filtered.isEmpty() && !state.catalogLoading) item {
                Text("No quests match your filters.", modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        SnackbarHost(snackbars, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ContextAwareCard(
    contextState: ContextUiState,
    locationMode: String,
    onRequestLocation: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .35f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("LIVE CONTEXT", fontSize = 10.sp, fontWeight = FontWeight.Black, color = DiscoveryTeal, letterSpacing = 1.1.sp)
                    Text(
                        text = if (contextState.loading) "Updating context…" else "${contextState.context.timeOfDay.label()} • ${contextState.context.weather?.condition?.label() ?: "Weather unavailable"}",
                        fontWeight = FontWeight.Bold,
                    )
                    val locationText = when {
                        contextState.address != null -> "📍 Near: ${contextState.address}"
                        contextState.context.location != null -> "📍 " + (contextState.addressError ?: "Location available")
                        else -> "📍 Location not available"
                    }
                    Text(locationText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                }
                Text(
                    text = when (locationMode) {
                        "gps" -> "Nearby"
                        "anywhere" -> "Anywhere"
                        else -> "All"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (contextState.context.weather != null) {
                Text(
                    "${contextState.context.weather.temperatureCelsius.toInt()}°C" +
                        if (contextState.context.weather.isStale) " · Last known weather" else "",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
                )
            }
            if (contextState.context.location == null) {
                Text(
                    "Enable location to use Nearby quests. Anywhere works without GPS.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onRequestLocation),
                    color = DiscoveryTeal,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Enable location", modifier = Modifier.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            } else {
                Text(
                    "Refresh context",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onRefresh).padding(vertical = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = DiscoveryTeal,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
            if (contextState.batterySaverActive) {
                Text(
                    "🔋 Battery saver: reusing the last context (refreshes every 15 min). Tap Refresh to force it.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
                )
            }
            contextState.error?.let {
                Text("Context fallback: $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DiscoverShortcut(
    emoji: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(emoji, style = MaterialTheme.typography.headlineSmall)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
