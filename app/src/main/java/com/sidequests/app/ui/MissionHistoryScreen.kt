package com.sidequests.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sidequests.app.domain.HistoryEntry
import com.sidequests.app.domain.HistoryFilter
import com.sidequests.app.domain.HistoryMonth
import com.sidequests.app.domain.historyCategoryLabel
import com.sidequests.app.domain.normalizeHistorySearch
import com.sidequests.app.model.AbandonmentReason
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.SidequestsUiState
import com.sidequests.app.ui.theme.DiscoveryTeal
import com.sidequests.app.ui.theme.ExplorerIndigo
import com.sidequests.app.ui.theme.QuestAmber
import java.text.NumberFormat
import java.util.Locale

private val monthNames = listOf("January","February","March","April","May","June","July","August","September","October","November","December")
private val monthAbbreviations = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
private fun thousands(n: Int) = NumberFormat.getIntegerInstance(Locale.US).format(n)
private fun count(n: Int, one: String, many: String) = "${thousands(n)} ${if (n == 1) one else many}"
private val reasons = AbandonmentReason.entries.associate { it.code to "${it.emoji} ${it.label}" }

@Composable
fun MissionHistoryRoute(
    state: SidequestsUiState,
    viewModel: AppViewModel,
    online: Boolean,
    controller: MissionHistoryController,
    onRequestLocation: () -> Unit,
) {
    val userId = state.accountUserId ?: return
    val historyState by controller.state.collectAsStateWithLifecycle()
    var evidenceEntry by remember(userId) { mutableStateOf<HistoryEntry?>(null) }
    val thumbnails = remember(userId) { EvidenceThumbnails() }
    val selectedEvidence = evidenceEntry?.takeIf { it.attempt.userId == userId }
    val evidenceRepository = viewModel.attemptEvidenceRepository
    if (selectedEvidence != null && evidenceRepository != null) {
        val back = { evidenceEntry = null }
        BackHandler(onBack = back)
        key(userId, selectedEvidence.attempt.id) {
            AttemptEvidenceScreen(
                selectedEvidence, viewModel.evidenceQuestSteps(selectedEvidence.attempt.questId),
                evidenceRepository, thumbnails, online, onBack = back,
            )
        }
    } else if (state.screen == AppScreen.QuestDetail) {
        BackHandler { viewModel.navigate(AppScreen.MissionHistory) }
        QuestDetailScreen(viewModel.selectedQuest(), viewModel,
            onBack = { viewModel.navigate(AppScreen.MissionHistory) }, onRequestLocation = onRequestLocation)
    } else {
        val back = { viewModel.navigate(AppScreen.Profile) }
        BackHandler(onBack = back)
        MissionHistoryScreen(historyState.copy(offline = !online), controller::setQuery, controller::setFilter,
            controller::refresh, back, viewModel::openQuestFromHistory,
            onOpenEvidence = if (evidenceRepository != null) ({ evidenceEntry = it }) else null)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MissionHistoryScreen(
    state: MissionHistoryUiState,
    onQuery: (String) -> Unit,
    onFilter: (HistoryFilter) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Boolean,
    onOpenEvidence: ((HistoryEntry) -> Unit)?,
) {
    var detailError by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 24.sp) }
            Text("Mission History", style = MaterialTheme.typography.titleLarge)
        }
        val historyNotice = when {
            state.offline -> null // The app-wide banner already reports connectivity.
            state.fromCache && (state.refreshing || state.reconnecting) -> "Refreshing your mission history…"
            state.fromCache -> "Could not refresh your mission history. Showing saved data. Pull down to retry."
            else -> null
        }
        if (historyNotice != null || state.history.pendingSyncCount > 0) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    listOfNotNull(historyNotice, state.history.pendingSyncCount.takeIf { it > 0 }?.let {
                        count(it, "quest with changes pending sync.", "quests with changes pending sync.")
                    }).joinToString("\n"),
                    modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        when {
            !state.initialized -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !state.hasSnapshot -> HistoryMessage(
                state.error ?: "Could not load your mission history.", "Retry", onRefresh)
            state.history.totalAttempts == 0 && !state.fromCache -> HistoryMessage(
                "Your mission history is empty. Accept a quest in Explore to get started!", "Back", onBack)
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = state.query, onValueChange = onQuery,
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                placeholder = { Text("Search quests, places or categories", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Text("⌕", fontSize = 24.sp) },
                                trailingIcon = { if (state.query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text("✕") } },
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(HistoryFilter.All to "All", HistoryFilter.Completed to "Completed",
                                    HistoryFilter.Abandoned to "Abandoned").forEach { (filter,label) ->
                                    FilterChip(selected = state.filter == filter, onClick = { onFilter(filter) },
                                        label = { Text(label) }, shape = RoundedCornerShape(50),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                                }
                            }
                            val total = state.history.totalAttempts
                            val label = if (state.filter != HistoryFilter.All || normalizeHistorySearch(state.query).isNotEmpty())
                                "Showing ${thousands(state.history.matchCount)} of ${count(total,"quest","quests")}"
                                else count(total,"quest","quests") + " in your history"
                            Text(label, style = MaterialTheme.typography.bodySmall)
                            Box(Modifier.fillMaxWidth().height(2.dp)) {
                                if (state.updating || state.refreshing || state.reconnecting)
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (detailError) Text("This quest's details are not available in the current catalogue.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (state.history.months.isEmpty()) item(key = "no-matches") {
                        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("⌕", fontSize = 32.sp)
                                Text(if (state.history.totalAttempts == 0) "No quests in the saved history."
                                    else "No matching quests", style = MaterialTheme.typography.titleSmall)
                                Text(if (state.history.totalAttempts == 0) "Your history will refresh when the connection returns."
                                    else "Try a different search or change the filter.", style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center)
                            }
                        }
                    }
                    state.history.months.forEach { month ->
                        item(key = "month-${month.year}-${month.month}") { MissionMonthHeader(month) }
                        items(month.entries, key = { "attempt-${it.attempt.id}" }) { entry ->
                            MissionHistoryCard(
                                entry,
                                onClick = { detailError = !onOpen(entry.attempt.questId) },
                                onOpenEvidence = onOpenEvidence?.let { open -> { open(entry) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HistoryMessage(message: String, button: String, action: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("📖", fontSize = 42.sp)
            Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            PrimaryButton(button, onClick = action)
        }
    }
}

@Composable private fun MissionMonthHeader(month: HistoryMonth) {
    val summary = buildList {
        if (month.completedCount > 0) add("${thousands(month.completedCount)} completed")
        if (month.abandonedCount > 0) add("${thousands(month.abandonedCount)} abandoned")
        if (month.inProgressCount > 0) add("${thousands(month.inProgressCount)} in progress")
        if (month.completedMinutes > 0) add("${month.completedMinutes} min")
        month.steps?.let { add(count(it,"step","steps")) }
    }.joinToString(" · ")
    Column(Modifier.padding(top = 12.dp)) {
        Text("${monthNames[month.month-1]} ${month.year}", style = MaterialTheme.typography.titleMedium)
        if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun MissionHistoryCard(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onOpenEvidence: (() -> Unit)?,
) {
    val quest = entry.quest
    val attempt = entry.attempt
    val details = buildList {
        add("${monthAbbreviations[entry.date.monthValue-1]} ${entry.date.dayOfMonth}")
        quest?.let { add(historyCategoryLabel(it.category)); add("${it.durationMinutes} min") }
    }.joinToString(" · ")
    val (label, color) = when (attempt.status) {
        "completed" -> "Completed" to DiscoveryTeal
        "abandoned" -> "Abandoned" to Color(0xFFE05252)
        else -> "In progress" to MaterialTheme.colorScheme.primary
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = quest != null, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(quest?.emoji ?: "🎯", fontSize = 22.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(details, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    if (attempt.status == "completed") {
                        val rating = attempt.rating?.coerceIn(1,5)
                        if (rating != null) Text("★".repeat(rating) + "☆".repeat(5-rating), color = QuestAmber)
                        else Text("Not rated", style = MaterialTheme.typography.bodySmall)
                    } else if (attempt.status == "abandoned") {
                        reasons[attempt.abandonReason]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                entry.steps?.let { Text("🚶 " + count(it,"step","steps"), style = MaterialTheme.typography.bodySmall) }
                if (entry.pending) Text("☁ Pending sync", style = MaterialTheme.typography.bodySmall)
                if (onOpenEvidence != null && (attempt.status == "completed" || attempt.status == "abandoned")) {
                    TextButton(onClick = onOpenEvidence) { Text("View evidence") }
                }
            }
            if (quest != null) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
        }
    }
}
