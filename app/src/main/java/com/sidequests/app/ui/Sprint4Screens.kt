package com.sidequests.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.Quest
import com.sidequests.app.model.SidequestsUiState
import com.sidequests.app.model.SocialLevel

/** Four brand-new Sprint 4 views, separate from Sprint 2 and 3 screens. */
@Composable
fun Sprint4EntryPoints(onNavigate: (AppScreen) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DISCOVER MORE", style = MaterialTheme.typography.titleMedium)
            Text("Four ways to find your next quest", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sprint4Link("Quick plan", Modifier.weight(1f)) { onNavigate(AppScreen.InstantPlan) }
                Sprint4Link("In a group", Modifier.weight(1f)) { onNavigate(AppScreen.GroupDiscovery) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sprint4Link("Try variety", Modifier.weight(1f)) { onNavigate(AppScreen.VarietyDiscovery) }
                Sprint4Link("My comfort", Modifier.weight(1f)) { onNavigate(AppScreen.SocialComfort) }
            }
        }
    }
}

@Composable
private fun Sprint4Link(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable(onClick = onClick)) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Sprint4Page(
    title: String,
    subtitle: String,
    catalogueSource: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("‹ Back to Explore", modifier = Modifier.clickable(onClick = onBack),
            style = MaterialTheme.typography.labelLarge)
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Text("Source: $catalogueSource", style = MaterialTheme.typography.labelSmall)
        content()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Sprint4QuestOption(quest: Quest, action: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${quest.emoji} ${quest.title}", style = MaterialTheme.typography.titleMedium)
            Text("${quest.category} · ${quest.durationMinutes} min · ${quest.difficulty.name} · ${quest.socialLevel.name}",
                style = MaterialTheme.typography.bodySmall)
            if (quest.isGroup) Text("Group-labelled activity · participation is not verified",
                style = MaterialTheme.typography.labelSmall)
            PrimaryButton(action, onClick)
        }
    }
}

@Composable
fun Sprint4InstantPlanScreen(state: SidequestsUiState, viewModel: AppViewModel) {
    val candidate = viewModel.sprint4InstantPlan()
    Sprint4Page(
        title = "Instant plan",
        subtitle = "A single feasible choice for your time and budget. Start directly or save it on this device.",
        catalogueSource = state.catalogSource,
        onBack = { viewModel.navigate(AppScreen.Explorer) },
    ) {
        if (candidate == null) {
            Text("No quest fits the current time and budget. Adjust your Explore preferences.")
        } else {
            Sprint4QuestOption(candidate, "Start now · one tap") {
                viewModel.sprint4StartInstant(candidate.id)
            }
            PrimaryButton(
                if (state.sprint4PinnedQuestId == candidate.id) "Remove saved plan" else "Save this plan",
                onClick = { viewModel.sprint4Pin(if (state.sprint4PinnedQuestId == candidate.id) null else candidate.id) },
            )
            if (state.sprint4PinnedQuestId == candidate.id) {
                Text("Saved on this device for your account.", style = MaterialTheme.typography.bodySmall)
            }
        }
        state.progressSyncError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Starting a new quest requires an online server confirmation. Your saved choice works offline.",
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun Sprint4GroupDiscoveryScreen(state: SidequestsUiState, viewModel: AppViewModel) {
    val quests = viewModel.sprint4GroupOptions()
    Sprint4Page(
        title = "Explore together",
        subtitle = "Find group-labelled quests that fit your current time and budget.",
        catalogueSource = state.catalogSource,
        onBack = { viewModel.navigate(AppScreen.Explorer) },
    ) {
        Text("${quests.size} matching group-labelled activities", style = MaterialTheme.typography.titleMedium)
        Text("These labels do not establish actual multi-person participation. BQ7 adoption and completion are measured from stored quest attempts.",
            style = MaterialTheme.typography.bodySmall)
        if (quests.isEmpty()) Text("No matching group quests. Try more time or a larger budget.")
        quests.forEach { quest ->
            Sprint4QuestOption(quest, "View this group quest") {
                viewModel.sprint4OpenDetail(quest.id, "group")
            }
        }
    }
}

@Composable
fun Sprint4VarietyDiscoveryScreen(state: SidequestsUiState, viewModel: AppViewModel) {
    val quests = viewModel.sprint4DiverseOptions()
    LaunchedEffect(quests.map { it.id }) {
        viewModel.sprint4RecordShown("variety", quests)
    }
    Sprint4Page(
        title = "Something different",
        subtitle = "A locally curated shortlist with different categories before repeating one.",
        catalogueSource = state.catalogSource,
        onBack = { viewModel.navigate(AppScreen.Explorer) },
    ) {
        Text("${quests.map { it.category }.distinct().size} different categories in this shortlist",
            style = MaterialTheme.typography.titleMedium)
        Text("This opt-in shortlist is separate from the backend BQ8 A/B experiment.",
            style = MaterialTheme.typography.bodySmall)
        if (quests.isEmpty()) Text("No quests fit your time and budget.")
        quests.forEach { quest ->
            Sprint4QuestOption(quest, "Discover this quest") {
                viewModel.sprint4OpenDetail(quest.id, "variety")
            }
        }
    }
}

@Composable
fun Sprint4SocialComfortScreen(state: SidequestsUiState, viewModel: AppViewModel) {
    var chosen by remember(state.accountUserId) { mutableStateOf(state.preferences.socialLevel) }
    val quests = viewModel.sprint4ComfortOptions(chosen)
    LaunchedEffect(chosen, quests.map { it.id }) {
        viewModel.sprint4RecordShown("social_comfort", quests, chosen)
    }
    Sprint4Page(
        title = "Your comfort, your choice",
        subtitle = "Choose how social you feel right now without changing your permanent profile.",
        catalogueSource = state.catalogSource,
        onBack = { viewModel.navigate(AppScreen.Explorer) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SocialLevel.entries.forEach { level ->
                ChoiceChip(
                    text = level.name,
                    selected = chosen == level,
                    onClick = { chosen = level },
                )
            }
        }
        Text("Showing ${quests.size} compatible quests. This is an opt-in preference filter, not proof that personalization causes higher completion or retention.",
            style = MaterialTheme.typography.bodySmall)
        if (quests.isEmpty()) Text("No matching quests. Choose another comfort level or adjust your filters.")
        quests.take(6).forEach { quest ->
            Sprint4QuestOption(quest, "Review this quest") {
                viewModel.sprint4OpenDetail(quest.id, "social_comfort", chosen)
            }
        }
    }
}
