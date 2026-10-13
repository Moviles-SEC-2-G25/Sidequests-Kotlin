package com.sidequests.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sidequests.app.context.WeatherCondition
import com.sidequests.app.context.label
import com.sidequests.app.model.SidequestsUiState
import com.sidequests.app.ui.theme.DiscoveryTeal

@Composable
fun QuestAlternativesScreen(
    state: SidequestsUiState,
    viewModel: AppViewModel,
    onRequestLocation: () -> Unit,
) {
    val result = state.questAlternatives ?: return
    BackHandler { viewModel.dismissQuestAlternatives() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("A better fit for now", style = MaterialTheme.typography.headlineMedium)
            Text("You left because: ${result.reason.label}", style = MaterialTheme.typography.bodyMedium)
            Text("Choose an alternative, or return to Explore.", style = MaterialTheme.typography.bodyMedium)
        }
        if (result.simulatedContext) {
            item { Text("Demo context: simulated weather and time", color = MaterialTheme.colorScheme.tertiary) }
        }
        if (result.loading) {
            item {
                Text("Checking your current context…")
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else {
            item {
                val weather = result.context?.weather?.condition ?: WeatherCondition.UNKNOWN
                Text("${result.context?.timeOfDay?.label() ?: "Current time"} · ${weather.label()}")
                if (weather == WeatherCondition.UNKNOWN) {
                    Text("Weather unavailable. Suggestions use your reason and current time.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (result.context?.location == null && !result.simulatedContext) {
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Enable location for local weather", onRequestLocation)
                }
            }
            if (result.suggestions.isEmpty()) {
                item {
                    Text("No alternative fits this reason and your time and budget limits right now.")
                    Text("You can adjust your preferences in Explore.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(result.suggestions, key = { it.quest.id }) { alternative ->
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${alternative.quest.emoji} ${alternative.quest.title}", style = MaterialTheme.typography.titleLarge)
                        Text("${alternative.quest.duration} · ${alternative.quest.budget} · ${alternative.quest.distance}")
                        Text("Why this fits", color = DiscoveryTeal, style = MaterialTheme.typography.titleSmall)
                        alternative.explanations.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        PrimaryButton("View quest", { viewModel.openQuestAlternative(alternative.quest.id) })
                    }
                }
            }
            item { PrimaryButton("Refresh suggestions", viewModel::refreshQuestAlternatives) }
        }
        if (state.progressSyncing) {
            item { Text("Saving your previous quest…", style = MaterialTheme.typography.bodySmall) }
        }
        state.progressSyncError?.let {
            item { Text("Your previous quest could not be synced. $it", color = MaterialTheme.colorScheme.error) }
        }
        item { PrimaryButton("Back to Explore", viewModel::dismissQuestAlternatives) }
    }
}
