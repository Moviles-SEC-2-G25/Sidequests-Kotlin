package com.sidequests.app.ui

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
    val selected = entries.associateBy { it.questId }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Offline Pack", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${entries.count { it.status == OfflinePackStatus.READY }} of ${entries.size} quests ready offline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onBack) { Text("Done") }
        }
        if (!isOnline && entries.any { it.status != OfflinePackStatus.READY }) {
            Text(
                "You're offline. Selected quests are saved and will be prepared when connectivity returns.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (entries.any { it.status == OfflinePackStatus.FAILED || it.status == OfflinePackStatus.PENDING_DOWNLOAD }) {
            OutlinedButton(onClick = onRetry, enabled = isOnline, modifier = Modifier.fillMaxWidth()) {
                Text("Retry pending downloads")
            }
        }
        Text("YOUR OFFLINE PACK", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (entries.isEmpty()) {
            Text("No quests selected yet. Add quests below to keep them available without internet.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = true),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.questId }) { entry ->
                    val quest = quests.firstOrNull { it.id == entry.questId }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(quest?.let { "${it.emoji} ${it.title}" } ?: entry.questId, fontWeight = FontWeight.SemiBold)
                                Text(statusLabel(entry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                entry.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            }
                            TextButton(onClick = { onRemove(entry.questId) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
        Text("AVAILABLE QUESTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        LazyColumn(
            modifier = Modifier.weight(1f, fill = true),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(quests.filter { it.id !in selected }, key = { it.id }) { quest ->
                Card {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${quest.emoji} ${quest.title}", fontWeight = FontWeight.SemiBold)
                            Text("${quest.duration} · ${quest.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { onAdd(quest.id) }) { Text("Add") }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

private fun statusLabel(entry: OfflinePackEntry): String = when (entry.status) {
    OfflinePackStatus.READY -> "✓ Ready for offline use"
    OfflinePackStatus.PENDING_DOWNLOAD -> "Waiting for connection"
    OfflinePackStatus.DOWNLOADING -> "Preparing quest content…"
    OfflinePackStatus.FAILED -> "Download or validation failed"
}
