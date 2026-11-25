package com.sidequests.app.ui

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.sidequests.app.context.*
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.data.analytics.AnalyticsRepository
import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.local.PendingProgressOp
import com.sidequests.app.data.progress.QuestProgressRepository
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.Quest
import com.sidequests.app.model.UserPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Eventual connectivity: quests can be accepted and played offline through the durable queue. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineQuestQueueFlowTest {
    @get:Rule val folder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val viewModelJobs = mutableListOf<Job>()
    private val progress = RecordingProgress()
    private val analytics = RecordingAnalytics()
    private val retries = mutableListOf<String>()
    private fun store() = LexLocalStore(File(folder.root, "outbox.json"))

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        try {
            runTest(dispatcher) {
                models.clear()
                viewModelJobs.joinAll()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun vm(): AppViewModel {
        val location = object : LocationProvider { override suspend fun getCurrentLocation(): LocationData? = null }
        return AppViewModel(
            contextManager = ContextManager(location, MockWeatherProvider()),
            repository = InMemorySidequestsRepository(), progressRepository = progress,
            analyticsRepository = analytics, recommendationRepository = null, photoProofRepository = null,
            localStore = store(), scheduleLocalSync = {}, retryLocalSync = { retries += it },
            localIoDispatcher = dispatcher,
        ).also {
            models.put("vm-${viewModelJobs.size}", it)
            viewModelJobs += requireNotNull(it.viewModelScope.coroutineContext[Job])
        }
    }

    private fun TestScope.signedInOffline(): AppViewModel {
        val vm = vm()
        vm.attachAccount("alice")
        advanceUntilIdle()
        vm.skipOnboarding()
        advanceUntilIdle()
        vm.onDeviceStatusChanged(DeviceStatus(isOnline = false))
        return vm
    }

    @Test fun `offline acceptance opens the quest and queues it with its confirmation event`() = runTest(dispatcher) {
        val vm = signedInOffline()
        vm.acceptQuest("library-deep-dive")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(AppScreen.ActiveQuest, state.screen)
        assertNull(state.progressSyncError)
        val attempt = state.attemptIdByQuest.getValue("library-deep-dive")
        val op = store().read().progressOps.single()
        assertEquals(PendingProgressOp.ACCEPT, op.type)
        assertEquals(attempt, op.attemptId)
        assertEquals("alice", op.userId)
        assertEquals(listOf("quest_accepted"), op.confirmedEvents.map { it.eventType })
        // Nothing is reported as accepted until Supabase confirms it.
        assertTrue(progress.accepted.isEmpty())
        assertFalse(analytics.events.contains("quest_accepted"))
        assertEquals(1, vm.uiState.value.pendingProgressSync)
        assertTrue(store().read().photoQuests.getValue(attempt).let { !it.closed })
    }

    @Test fun `offline step progress is queued after the acceptance of the same attempt`() = runTest(dispatcher) {
        val vm = signedInOffline()
        vm.acceptQuest("library-deep-dive")
        advanceUntilIdle()
        vm.completeCurrentStep()
        advanceUntilIdle()

        val ops = store().read().progressOps
        assertEquals(listOf(PendingProgressOp.ACCEPT, PendingProgressOp.PROGRESS), ops.map { it.type })
        assertEquals(setOf(0), ops.last().completedSteps)
        assertEquals(1, ops.map { it.attemptId }.distinct().size)
        assertNull(vm.uiState.value.progressSyncError)
    }

    @Test fun `group quests still need internet to join`() = runTest(dispatcher) {
        val vm = signedInOffline()
        val group = vm.allQuests().first { it.isGroup }
        assertFalse(vm.canAcceptQuest(group))
        vm.acceptQuest(group.id)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.attemptIdByQuest.isEmpty())
        assertTrue(store().read().progressOps.isEmpty())
        assertTrue(vm.uiState.value.progressSyncError.orEmpty().contains("internet"))
    }

    @Test fun `Explore continues the open attempt instead of accepting it again`() = runTest(dispatcher) {
        val vm = signedInOffline()
        vm.acceptQuest("library-deep-dive")
        advanceUntilIdle()
        vm.completeCurrentStep()
        advanceUntilIdle()
        val attempt = vm.uiState.value.attemptIdByQuest.getValue("library-deep-dive")
        vm.navigate(AppScreen.Explorer)
        vm.openQuestDetail("library-deep-dive")

        assertEquals(setOf(0), vm.openAttemptProgress("library-deep-dive")?.completedSteps)
        vm.resumeQuest("library-deep-dive")
        advanceUntilIdle()
        assertEquals(AppScreen.ActiveQuest, vm.uiState.value.screen)
        assertEquals(attempt, vm.uiState.value.attemptIdByQuest["library-deep-dive"])
        assertEquals(1, store().read().progressOps.count { it.type == PendingProgressOp.ACCEPT })
        assertNull(vm.openAttemptProgress("botanical-garden"))
    }

    @Test fun `reconnecting clears stale errors and retries the queue immediately`() = runTest(dispatcher) {
        val vm = signedInOffline()
        vm.acceptQuest(vm.allQuests().first { it.isGroup }.id)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.progressSyncError)

        vm.onDeviceStatusChanged(DeviceStatus(isOnline = true))
        advanceUntilIdle()
        assertNull(vm.uiState.value.progressSyncError)
        assertEquals(listOf("alice"), retries)
    }

    private class RecordingAnalytics : AnalyticsRepository {
        val events = mutableListOf<String>()
        override suspend fun track(sessionId: String, eventType: String, availableMinutes: Int,
            preferences: UserPreferences, quest: Quest?, metadata: JsonObject): Result<Unit> {
            events += eventType
            return Result.success(Unit)
        }
    }

    private class RecordingProgress : QuestProgressRepository {
        val accepted = mutableListOf<String>()
        override suspend fun acceptQuest(questId: String, attemptId: String): Result<Unit> {
            accepted += attemptId
            return Result.success(Unit)
        }
        override suspend fun updateProgress(attemptId: String, currentStep: Int, completedSteps: Set<Int>, completed: Boolean) = Result.success(Unit)
        override suspend fun saveProgress(attemptId: String, currentStep: Int, completedSteps: Set<Int>, reason: String?) = Result.success(Unit)
        override suspend fun abandonQuest(attemptId: String, currentStep: Int, completedSteps: Set<Int>, reason: String?) = Result.success(Unit)
        override suspend fun rateQuest(attemptId: String, stars: Int, tags: Set<String>) = Result.success(Unit)
    }
}
