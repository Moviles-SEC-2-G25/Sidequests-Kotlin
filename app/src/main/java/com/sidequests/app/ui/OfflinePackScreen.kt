package com.sidequests.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sidequests.app.data.offline.OfflinePackEntry
import com.sidequests.app.data.offline.OfflinePackStatus
import com.sidequests.app.model.Quest

@Composable
fun OfflinePackScreen(
    quests: List<Quest>,
    entries: List<OfflinePackEntry>,
    isOnline: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val selectedIds = entries.mapTo(mutableSetOf()) { it.questId }
    val availableQuests = quests.filterNot { it.id in selectedIds }
    val readyCount = entries.count { it.status == OfflinePackStatus.READY }
    val hasPendingWork = entries.any {
        it.status == OfflinePackStatus.PENDING_DOWNLOAD ||
            it.status == OfflinePackStatus.FAILED
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 18.dp,
            vertical = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Offline Pack",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "$readyCount of ${entries.size} quests ready offline",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onBack) { Text("Done") }
            }
        }

        if (!isOnline && entries.any { it.status != OfflinePackStatus.READY }) {
            item {
                Text(
                    "You're offline. Your selections are saved and will be prepared when connectivity returns.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (hasPendingWork) {
            item {
                OutlinedButton(
                    onClick = onRetry,
                    enabled = isOnline,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isOnline) "Retry pending downloads" else "Retry when connected")
                }
            }
        }

        item {
            Text(
                "YOUR OFFLINE PACK",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    "No quests selected yet. Add quests below to keep their steps available without internet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(entries, key = { "selected-${it.questId}" }) { entry ->
                val quest = quests.firstOrNull { it.id == entry.questId }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                quest?.let { "${it.emoji} ${it.title}" } ?: entry.questId,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                statusLabel(entry),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            entry.message?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        TextButton(onClick = { onRemove(entry.questId) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }

        item {
            Text(
                "AVAILABLE QUESTS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (availableQuests.isEmpty()) {
            item {
                Text(
                    "All available quests are already in your offline pack.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(availableQuests, key = { "available-${it.id}" }) { quest ->
                Card {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${quest.emoji} ${quest.title}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${quest.duration} · ${quest.category}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { onAdd(quest.id) }) { Text("Add") }
                    }
                }
            }
        }
    }
}

private fun statusLabel(entry: OfflinePackEntry): String = when (entry.status) {
    OfflinePackStatus.READY -> "✓ Ready for offline use"
    OfflinePackStatus.PENDING_DOWNLOAD -> "Waiting for connection"
    OfflinePackStatus.DOWNLOADING -> "Preparing quest content…"
    OfflinePackStatus.FAILED -> "Download or validation failed"
}
