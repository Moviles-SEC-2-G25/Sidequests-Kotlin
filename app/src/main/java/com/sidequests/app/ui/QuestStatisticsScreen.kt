package com.sidequests.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sidequests.app.domain.historyCategoryLabel
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestStatisticsScreen(state: MissionHistoryUiState, onBack: () -> Unit, onRefresh: () -> Unit) {
    BackHandler(onBack = onBack)
    val statistics = state.statistics
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("My Statistics", style = MaterialTheme.typography.headlineSmall)
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (statistics == null) {
                    item {
                        if (!state.initialized) {
                            CircularProgressIndicator()
                        } else {
                            Text(if (state.offline) "No quest statistics are saved on this device."
                                else "Could not load your statistics.")
                            if (!state.offline) TextButton(onClick = onRefresh) { Text("Try again") }
                        }
                    }
                } else {
                    if (state.fromCache) item { Text("Showing saved statistics", style = MaterialTheme.typography.bodySmall) }
                    item {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Quest activity", style = MaterialTheme.typography.titleMedium)
                                Text("${statistics.completed} completed · ${statistics.attempts} attempted")
                                val steps = statistics.steps?.let { NumberFormat.getIntegerInstance(Locale.US).format(it) }
                                Text(if (steps == null) "No walking data recorded" else "$steps steps walked")
                            }
                        }
                    }
                    item { Text("Completion by category", style = MaterialTheme.typography.titleMedium) }
                    if (statistics.categories.isEmpty()) item { Text("Category statistics appear after you attempt a quest.") }
                    items(statistics.categories.values.sortedWith(
                        compareByDescending<com.sidequests.app.domain.CategoryStatistics> { it.attempts }.thenBy { it.category },
                    ), key = { it.category }) { category ->
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(historyCategoryLabel(category.category), style = MaterialTheme.typography.titleSmall)
                                Text("${category.completed} of ${category.attempts} completed")
                                Text(category.likelihood.label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
