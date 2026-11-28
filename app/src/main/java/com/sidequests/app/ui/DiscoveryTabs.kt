package com.sidequests.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sidequests.app.context.ContextUiState
import com.sidequests.app.domain.nearbyQuests
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.SidequestsUiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    state: SidequestsUiState,
    context: ContextUiState,
    viewModel: AppViewModel,
    requestLocation: () -> Unit,
) {
    val catalog = viewModel.allQuests()
    val position = context.context.location
    val quests = remember(catalog, position) { nearbyQuests(catalog, position) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    PullToRefreshBox(isRefreshing = state.catalogLoading, onRefresh = viewModel::refreshQuestCatalog) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("LOCATION QUESTS", color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text("Where are you headed today?", style = MaterialTheme.typography.headlineSmall)
                    Text("${quests.size} places to explore", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                    listOf(90.dp, 65.dp, 40.dp).forEach { radius ->
                        drawCircle(color.copy(alpha = .16f), radius.toPx(), style = Stroke(1.dp.toPx()))
                    }
                    drawCircle(color, 10.dp.toPx())
                }
            }
            if (position == null) item {
                PrimaryButton(
                    if (requested && context.loading) "Getting your location…" else "Turn on location to see distances",
                    onClick = {
                        requested = true
                        requestLocation()
                    },
                    enabled = !requested || !context.loading,
                )
                if (requested && !context.loading) {
                    Text("Could not get your location. Check that location is on and Sidequests has permission.",
                        modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.catalogError != null) item {
                Text("Could not refresh quests. Showing the available catalogue.")
                TextButton(onClick = viewModel::refreshQuestCatalog) { Text("Try again") }
            }
            item { Text("AVAILABLE · ${quests.size}", style = MaterialTheme.typography.labelMedium) }
            if (quests.isEmpty()) item {
                if (state.catalogLoading) CircularProgressIndicator()
                else Text("No location quests are available right now.")
            }
            items(quests, key = { it.quest.id }) { entry ->
                val quest = entry.quest
                Card(
                    onClick = { viewModel.openQuestDetail(quest.id, origin = AppScreen.Nearby) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(quest.emoji, style = MaterialTheme.typography.titleLarge)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(quest.title, style = MaterialTheme.typography.titleSmall)
                            Text(quest.locationName ?: "Location not specified", style = MaterialTheme.typography.bodySmall)
                            Text("${quest.difficulty.name} · ${quest.duration}", style = MaterialTheme.typography.bodySmall)
                            if (quest.isSponsored) MetaPill("Sponsored")
                        }
                        val distance = entry.distanceKm?.let { String.format(Locale.US, "%.1f km", it) }
                        Text(distance ?: "Distance unavailable", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SocialScreen(onGroupChallenges: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Quest photos", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The social feed is not available yet", style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center)
                    Text("You can still take on a quest together in Group Challenges.",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
        }
        item { PrimaryButton("Group Challenges", onGroupChallenges) }
    }
}

@Composable
fun QuestHomeScreen(onExplore: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No active quest", style = MaterialTheme.typography.headlineSmall)
            Text("Pick a quest in Explore to get started.", style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center)
            PrimaryButton("Explore quests", onExplore)
        }
    }
}
