package com.sidequests.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sidequests.app.domain.VarietyDiscovery
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.SidequestsUiState

/** A new Sprint 4 view, not another implementation of the BQ8 server experiment. */
@Composable
fun VarietyScreen(state: SidequestsUiState, viewModel: AppViewModel) {
    // Local discovery, independent of the shared server BQ8 experiment.
    val options = VarietyDiscovery.select(
        catalogue = viewModel.allQuests(),
        availableMinutes = state.availableTime,
        maxBudget = state.preferences.budgetMax,
        excludedIds = state.skippedQuestIds + state.attemptIdByQuest.keys,
    )
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "‹ Back to Explore",
            modifier = Modifier.clickable { viewModel.navigate(AppScreen.Explorer) },
            style = MaterialTheme.typography.labelLarge,
        )
        Text("Something Different", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Try a different category without exceeding your available time or budget.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Catalogue: ${state.catalogSource}", style = MaterialTheme.typography.labelSmall)
        Text(
            "${options.map { it.category }.distinct().size} categories in your current shortlist",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "This is a local discovery view; the BQ8 control/diverse comparison uses the server Smart Picks.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (options.isEmpty()) {
            Text("No matching quests. Adjust your filters in Explore.")
        }
        options.forEach { quest ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${quest.emoji} ${quest.title}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${quest.category} · ${quest.durationMinutes} min · ${quest.difficulty.name}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PrimaryButton("View quest") {
                        viewModel.openQuestDetail(quest.id)
                    }
                }
            }
        }
    }
}
