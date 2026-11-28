package com.sidequests.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sidequests.app.domain.AdaptationSource
import com.sidequests.app.domain.ComfortLevel
import com.sidequests.app.domain.ExposureOutcome
import com.sidequests.app.domain.SocialExposure
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.SidequestsUiState
import com.sidequests.app.telemetry.Bq1Telemetry

/**
 * BQ14 (Sprint 4, #87): Social comfort view.
 *
 * The user picks how social their quests can be, decides whether Smart Picks adapt to it, and
 * sees what their own bitácora says about solo, social and group quests.
 * Eventual connectivity: choices are saved on the phone and apply at once; the history falls back
 * to the saved copy; the view shows how many analytics events are waiting for internet.
 */
@Composable
fun SocialComfortScreen(state: SidequestsUiState, viewModel: AppViewModel, online: Boolean) {
    val view by viewModel.socialComfortView.collectAsStateWithLifecycle()
    val pendingEvents by Bq1Telemetry.pendingCount.collectAsStateWithLifecycle()
    val back = { viewModel.navigate(AppScreen.Profile) }
    BackHandler(onBack = back)

    // Reload when the screen opens and every time connectivity comes back.
    LaunchedEffect(state.accountUserId, online) { viewModel.refreshSocialComfortInsights() }

    val settings = state.socialComfort
    val adaptation = viewModel.socialAdaptation(state)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back) { Text("←", fontSize = 24.sp) }
                Text("Social comfort", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (!online) {
            item {
                Notice("You're offline. Your choices are saved on this phone and apply right away.")
            }
        }
        item {
            Text(
                "How social can your quests be?",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        items(ComfortLevel.entries) { level ->
            LevelCard(level, selected = settings.level == level) { viewModel.setSocialComfort(level) }
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Adapt my Smart Picks", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                settings.level == ComfortLevel.High -> "At High every quest fits, so nothing is hidden."
                                adaptation.source == AdaptationSource.User && adaptation.active -> "On · your choice. Quests above your level are hidden."
                                adaptation.source == AdaptationSource.User -> "Off · your choice. You'll see every quest."
                                adaptation.active -> "On by default for your account. You can turn it off."
                                else -> "Off by default for your account. Turn it on to hide quests above your level."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f),
                        )
                    }
                    Switch(
                        checked = adaptation.active,
                        enabled = settings.level != ComfortLevel.High,
                        onCheckedChange = viewModel::setSocialAdaptation,
                    )
                }
            }
        }
        item {
            Text(
                "What your history says",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
        }
        item { HistorySection(view, online, onRetry = viewModel::refreshSocialComfortInsights, onApply = viewModel::setSocialComfort, current = settings.level) }
        if (pendingEvents > 0) {
            item {
                Text(
                    "$pendingEvents analytics ${if (pendingEvents == 1L) "event is" else "events are"} waiting for internet.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LevelCard(level: ComfortLevel, selected: Boolean, onSelect: () -> Unit) {
    val emoji = when (level) {
        ComfortLevel.Low -> "🎧"
        ComfortLevel.Medium -> "💬"
        ComfortLevel.High -> "🎉"
    }
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 24.sp)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(level.label + if (selected) " · selected" else "", fontWeight = FontWeight.Bold)
                Text(level.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
            }
        }
    }
}

@Composable
private fun HistorySection(
    view: SocialComfortViewState,
    online: Boolean,
    onRetry: () -> Unit,
    onApply: (ComfortLevel) -> Unit,
    current: ComfortLevel,
) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val report = view.report
        when {
            view.unavailable -> Text("History is not available in this build.", fontSize = 13.sp)
            view.loading && report == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp).height(20.dp))
                Text("Reading your history…", fontSize = 13.sp)
            }
            report == null || !report.hasHistory -> {
                Text(
                    if (!online) "No saved history on this phone yet. Connect once to load it."
                    else view.error ?: "No history yet. Finish or leave a quest to see your pattern here.",
                    fontSize = 13.sp,
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            else -> {
                if (!report.fresh) {
                    Notice("Showing your saved history.", padded = false)
                }
                report.insights.outcomes.forEach { OutcomeRow(it) }
                if (report.insights.unknownAttempts > 0) {
                    Text(
                        "${report.insights.unknownAttempts} attempts are for quests no longer in the catalogue.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
                    )
                }
                if (report.insights.totalClosed == 0) {
                    Text("No finished or left quests yet.", fontSize = 13.sp)
                }
                val suggestion = report.insights.suggestion
                if (suggestion != null && suggestion.maxExposure < current.maxExposure) {
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("You often leave quests above ${suggestion.label}.", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            TextButton(onClick = { onApply(suggestion) }) { Text("Switch to ${suggestion.label}") }
                        }
                    }
                }
                if (view.error != null) {
                    Text(view.error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun OutcomeRow(outcome: ExposureOutcome) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(SocialExposure.label(outcome.exposure), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${outcome.finished} finished · ${outcome.left} left", fontSize = 13.sp)
    }
}

@Composable
private fun Notice(text: String, padded: Boolean = true) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(if (padded) Modifier.padding(horizontal = 20.dp) else Modifier),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(text, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}
