package com.sidequests.app.ui

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sidequests.app.data.group.GroupAction
import com.sidequests.app.data.group.GroupChallenge
import com.sidequests.app.data.group.GroupChallengeLoader
import com.sidequests.app.data.group.SupabaseGroupChallengeGateway
import com.sidequests.app.data.remote.SupabaseProvider
import com.sidequests.app.model.AppScreen
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

    val groupQuests = viewModel.allQuests().filter { it.isGroup }
    val canWrite = online && !busy && loaded && gateway != null && pending == null
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = { viewModel.navigate(AppScreen.Explorer) }) { Text("Back to Explore") }
        Text("Group Challenges", style = MaterialTheme.typography.headlineMedium)
        Text("Create a shared challenge, join others and report your own completed steps.")
        Text("Group progress is self-reported and separate from your individual quest history.", style = MaterialTheme.typography.bodySmall)
        Text("Challenges are visible to signed-in users for seven days. Only aggregate participant counts are shown.",
            style = MaterialTheme.typography.bodySmall)
        if (gateway == null) Text("Sign in with a configured backend to use shared challenges.")
        if (!online) Text("Offline: showing previously loaded lists. Reconnect before making changes.")
        if (busy) Text("Updating challenges…")
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = { reload() }, enabled = online && !busy && gateway != null) { Text("Refresh") }
        pending?.let { action ->
            PrimaryButton("Retry pending request", onClick = { submit(action) }, enabled = online && !busy)
        }

        Text("My challenges", style = MaterialTheme.typography.titleLarge)
        if (mine.isEmpty() && loaded) Text("No joined challenges yet.")
        mine.forEach { challenge ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(challenge.title, style = MaterialTheme.typography.titleMedium)
                    Text("${challenge.participants} participants · ${challenge.completedParticipants} completed")
                    val progress = challenge.myProgress ?: 0
                    Text("Your progress: $progress / ${challenge.totalSteps} steps")
                    viewModel.allQuests().firstOrNull { it.id == challenge.questId }?.steps?.getOrNull(progress)?.let { step ->
                        Text("Next: ${step.title}", style = MaterialTheme.typography.titleSmall)
                        Text(step.description, style = MaterialTheme.typography.bodySmall)
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
        }

        Text("Available challenges", style = MaterialTheme.typography.titleLarge)
        if (available.isEmpty() && loaded) Text("No other open challenges.")
        available.forEach { challenge ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(challenge.title, style = MaterialTheme.typography.titleMedium)
                    Text("${challenge.participants} participants · ${challenge.totalSteps} steps")
                    PrimaryButton("Join challenge", onClick = {
                        submit(GroupAction("join", UUID.randomUUID().toString(), challengeId = challenge.challengeId))
                    }, enabled = canWrite)
                }
            }
        }

        Text("Create a challenge", style = MaterialTheme.typography.titleLarge)
        if (groupQuests.isEmpty()) Text("No group quests in the available catalogue.")
        groupQuests.forEach { quest ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(quest.title, style = MaterialTheme.typography.titleMedium)
                    Text("${quest.durationMinutes} min · ${quest.category}")
                    PrimaryButton("Create challenge", onClick = {
                        submit(GroupAction("create", UUID.randomUUID().toString(), questId = quest.id))
                    }, enabled = canWrite && loaded)
                }
            }
        }
    }
}
