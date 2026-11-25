package com.sidequests.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sidequests.app.data.group.GroupAction
import com.sidequests.app.data.group.GroupChallenge
import com.sidequests.app.data.group.GroupChallengeLoader
import com.sidequests.app.data.group.SupabaseGroupChallengeGateway
import com.sidequests.app.data.remote.SupabaseProvider
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.Quest
import com.sidequests.app.model.SidequestsUiState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun GroupChallengesScreen(state: SidequestsUiState, viewModel: AppViewModel, online: Boolean) {
    val account = state.accountUserId
    val gateway = remember(account) {
        if (SupabaseProvider.isConfigured && account != null)
            SupabaseGroupChallengeGateway(SupabaseProvider.client) else null
    }
    val loader = remember(gateway) { gateway?.let { GroupChallengeLoader(it) } }
    val scope = rememberCoroutineScope()
    var mine by remember(account) { mutableStateOf<List<GroupChallenge>>(emptyList()) }
    var available by remember(account) { mutableStateOf<List<GroupChallenge>>(emptyList()) }
    var busy by remember(account) { mutableStateOf(false) }
    var loaded by remember(account) { mutableStateOf(false) }
    var message by remember(account) { mutableStateOf<String?>(null) }
    var pending by remember(account) { mutableStateOf<GroupAction?>(null) }

    suspend fun refresh() {
        val source = loader ?: return
        val result = source.load()
        result.mine.onSuccess { mine = it }
        result.available.onSuccess { available = it }
        loaded = result.mine.isSuccess && result.available.isSuccess
        if (!loaded) message = "Some lists could not refresh. Showing any previously loaded data."
    }

    fun reload() {
        if (!online || busy || loader == null) return
        busy = true
        message = null
        scope.launch {
            try { refresh() } finally { busy = false }
        }
    }

    fun submit(action: GroupAction) {
        val service = gateway ?: return
        if (!online || busy) return
        busy = true
        pending = action
        message = null
        scope.launch {
            try {
                val response = service.act(action)
                pending = null
                message = response.message
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                viewModel.recordGroupChallengeUnconfirmed(account, action)
                message = "The result is unknown. Reconnect and retry the same request safely."
            } finally { busy = false }
        }
    }

    LaunchedEffect(account, online) {
        if (online) reload()
    }

    val catalogue = viewModel.allQuests()
    val groupQuests = creatableGroupQuests(catalogue, mine)
    val canWrite = online && !busy && loaded && gateway != null && pending == null
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.navigate(AppScreen.Explorer) }) { Text("← Explore") }
            TextButton(onClick = { reload() }, enabled = online && !busy && gateway != null) { Text("↻ Refresh") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Group Challenges", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Small adventures, better together.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loaded || mine.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChallengeMetric("👥", mine.size.toString(), if (mine.size == 1) "Joined challenge" else "Joined challenges", Modifier.weight(1f))
                ChallengeMetric("✓", mine.sumOf { it.myProgress ?: 0 }.toString(), "Steps completed", Modifier.weight(1f))
            }
        }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text("Shared for seven days with signed-in users. Your steps are self-reported and separate from individual quest history. Only group counts are shared.",
                modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (gateway == null) ChallengeNotice("Sign in with a configured backend to use shared challenges.")
        if (!online) ChallengeNotice("Offline · Previously loaded challenges stay visible. Reconnect to make changes.")
        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Updating challenges…", style = MaterialTheme.typography.labelMedium)
        }
        message?.let { ChallengeNotice(it) }
        pending?.let { action ->
            PrimaryButton("Retry pending request", onClick = { submit(action) }, enabled = online && !busy)
        }

        ChallengeSection("My challenges", mine.size)
        if (mine.isEmpty() && loaded) ChallengeEmpty("👥", "No joined challenges yet", "Join an open challenge or start one below.")
        mine.forEach { challenge ->
            val quest = catalogue.firstOrNull { it.id == challenge.questId }
            val progress = challenge.myProgress ?: 0
            val fraction = if (challenge.totalSteps > 0) (progress.toFloat() / challenge.totalSteps).coerceIn(0f, 1f) else 0f
            ChallengeCard {
                ChallengeHeading(challenge.title, quest?.emoji ?: "👥", quest?.let { "${it.category} · ${it.durationMinutes} min" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChallengePill("👥 ${participantLabel(challenge.participants)}")
                    ChallengePill("✓ ${challenge.completedParticipants} finished")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Your progress", style = MaterialTheme.typography.titleSmall)
                    Text("${(fraction * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("$progress of ${challenge.totalSteps} steps completed",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                quest?.steps?.getOrNull(progress)?.let { step ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("NEXT STEP · ${progress + 1}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                            Text(step.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(step.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                PrimaryButton(
                    text = if (progress >= challenge.totalSteps) "Completed" else "Mark next step complete",
                    onClick = {
                        submit(GroupAction("progress", UUID.randomUUID().toString(),
                            challengeId = challenge.challengeId, progress = progress + 1))
                    },
                    enabled = canWrite && progress < challenge.totalSteps,
                )
            }
        }

        ChallengeSection("Available challenges", available.size)
        if (available.isEmpty() && loaded) ChallengeEmpty("🌐", "No other open challenges", "You can create a challenge below.")
        available.forEach { challenge ->
            val quest = catalogue.firstOrNull { it.id == challenge.questId }
            ChallengeCard {
                ChallengeHeading(challenge.title, quest?.emoji ?: "👥", quest?.let { "${it.category} · ${it.durationMinutes} min" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChallengePill("👥 ${participantLabel(challenge.participants)}")
                    ChallengePill("${challenge.totalSteps} steps")
                }
                PrimaryButton("Join challenge", onClick = {
                    submit(GroupAction("join", UUID.randomUUID().toString(), challengeId = challenge.challengeId))
                }, enabled = canWrite)
            }
        }

        ChallengeSection("Create a challenge", groupQuests.size)
        if (groupQuests.isEmpty()) ChallengeEmpty("＋", "No new group quests to create", "Your joined challenges remain in My challenges.")
        groupQuests.forEach { quest ->
            ChallengeCard {
                ChallengeHeading(quest.title, quest.emoji, "${quest.category} · ${quest.durationMinutes} min")
                PrimaryButton("Create challenge", onClick = {
                    submit(GroupAction("create", UUID.randomUUID().toString(), questId = quest.id))
                }, enabled = canWrite)
            }
        }
    }
}

/** The server lists only unexpired memberships; keep their quest templates out of Create. */
internal fun creatableGroupQuests(catalogue: List<Quest>, mine: List<GroupChallenge>): List<Quest> {
    val joinedQuestIds = mine.mapTo(hashSetOf()) { it.questId }
    return catalogue.filter { it.isGroup && it.id !in joinedQuestIds }
}

internal fun participantLabel(count: Int): String =
    "$count ${if (count == 1) "participant" else "participants"}"

@Composable
private fun ChallengeCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
private fun ChallengeHeading(title: String, emoji: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Text(emoji, style = MaterialTheme.typography.headlineSmall)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ChallengeMetric(icon: String, value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(icon, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ChallengePill(label: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChallengeSection(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ChallengePill(count.toString())
    }
}

@Composable
private fun ChallengeEmpty(icon: String, title: String, description: String) {
    ChallengeCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChallengeNotice(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
