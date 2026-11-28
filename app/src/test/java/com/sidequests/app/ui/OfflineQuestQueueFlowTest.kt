package com.sidequests.app.ui

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.sidequests.app.context.*
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.data.SidequestsRepository
import com.sidequests.app.data.analytics.AnalyticsRepository
import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.local.PendingProgressOp
import com.sidequests.app.data.progress.QuestProgressRepository
import com.sidequests.app.data.progress.QuestProgressSyncEngine
import com.sidequests.app.domain.HistoryAttempt
import com.sidequests.app.domain.HistorySnapshot
import com.sidequests.app.domain.completionSuggestions
import com.sidequests.app.domain.summarizeQuestStatistics
import com.sidequests.app.data.recommendation.RecommendationRepository
import com.sidequests.app.data.recommendation.RecommendationResult
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.AbandonmentReason
import com.sidequests.app.model.Quest
import com.sidequests.app.model.SocialLevel
import com.sidequests.app.model.UserPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private fun vm(
        recommendations: RecommendationRepository? = null,
        catalogue: SidequestsRepository = InMemorySidequestsRepository(),
    ): AppViewModel {
        val location = object : LocationProvider { override suspend fun getCurrentLocation(): LocationData? = null }
        return AppViewModel(
            contextManager = ContextManager(location, MockWeatherProvider()),
            repository = catalogue, progressRepository = progress,
            analyticsRepository = analytics, recommendationRepository = recommendations, photoProofRepository = null,
            localStore = store(), scheduleLocalSync = {}, retryLocalSync = { retries += it },
            localIoDispatcher = dispatcher,
        ).also {
            models.put("vm-${viewModelJobs.size}", it)
            viewModelJobs += requireNotNull(it.viewModelScope.coroutineContext[Job])
        }
    }

    private fun TestScope.signedInOffline(
        recommendations: RecommendationRepository? = null,
        catalogue: SidequestsRepository = InMemorySidequestsRepository(),
    ): AppViewModel {
        val vm = vm(recommendations, catalogue)
        vm.attachAccount("alice")
        advanceUntilIdle()
        vm.skipOnboarding()
        advanceUntilIdle()
        vm.onDeviceStatusChanged(DeviceStatus(isOnline = false))
        return vm
    }

    @Test fun `precise check-in advances the matching attempt once and queues offline progress`() = runTest(dispatcher) {
        val repository = InMemorySidequestsRepository()
        val quest = repository.questById("library-deep-dive").run {
            copy(latitude = 4.60, longitude = -74.0,
                steps = listOf(steps.first().copy(requiresPhoto = false, requiresLocation = true)))
        }
        val catalogue = object : SidequestsRepository by repository {
            override fun questById(id: String) = if (id == quest.id) quest else repository.questById(id)
        }
        val vm = signedInOffline(catalogue = catalogue)
        vm.acceptQuest(quest.id)
        val attempt = vm.uiState.value.attemptIdByQuest.getValue(quest.id)
        val position = LocationData(4.60, -74.0)

        vm.checkInAtLocation("bob", attempt, 0, position, 20f)
        vm.checkInAtLocation("alice", attempt, 0, position, 100f)
        vm.checkInAtLocation("alice", attempt, 0, LocationData(4.61, -74.0), 20f)
        assertTrue(vm.activeProgress().completedSteps.isEmpty())

        vm.saveAndExit()
        advanceUntilIdle()
        assertTrue(store().read().photoQuests.getValue(attempt).paused)
        vm.checkInAtLocation("alice", attempt, 0, position, 20f)
        assertTrue(vm.activeProgress().completedSteps.isEmpty())
        vm.resumeQuest(quest.id)
        vm.checkInAtLocation("alice", attempt, 0, position, 20f)
        vm.checkInAtLocation("alice", attempt, 0, position, 20f)
        advanceUntilIdle()

        assertEquals(setOf(0), vm.activeProgress().completedSteps)
        val changes = store().read().progressOps.filter { it.type == PendingProgressOp.PROGRESS }
        assertEquals(1, changes.size)
        assertTrue(changes.single().completed)
        assertEquals(attempt, changes.single().attemptId)
    }

    @Test fun `GPS completion during Exit keeps the attempt closed despite a stale save`() = runTest(dispatcher) {
        val repository = InMemorySidequestsRepository()
        val source = repository.questById("library-deep-dive")
        val quest = source.copy(latitude = 4.60, longitude = -74.0,
            steps = listOf(source.steps.first().copy(requiresPhoto = false, requiresLocation = true)))
        val catalogue = object : SidequestsRepository by repository {
            override fun questById(id: String) = if (id == quest.id) quest else repository.questById(id)
        }
        val vm = signedInOffline(catalogue = catalogue)
        vm.acceptQuest(quest.id)
        val attempt = vm.uiState.value.attemptIdByQuest.getValue(quest.id)
        vm.navigate(AppScreen.ExitFlow)
        vm.checkInAtLocation("alice", attempt, 0, LocationData(4.60, -74.0), 20f)
        vm.saveAndExit(attempt)
        vm.jumpToStep(0)
        advanceUntilIdle()

        assertEquals(AppScreen.Rating, vm.uiState.value.screen)
        assertTrue(store().read().photoQuests.getValue(attempt).closed)
        val operations = store().read().progressOps
        assertEquals(listOf(PendingProgressOp.ACCEPT, PendingProgressOp.PROGRESS), operations.map { it.type })
        assertTrue(operations.last().completed)
    }

    @Test fun `offline completion ranks eligible quests and preserves the rated attempt through detail and sync`() = runTest(dispatcher) {
        val repository = InMemorySidequestsRepository()
        val seed = repository.questById("library-deep-dive")
        val source = seed.copy(steps = listOf(seed.steps.first().copy(requiresPhoto = false)), durationMinutes = 20)
        val same = source.copy(id = "same")
        val other = source.copy(id = "other", category = "Outdoor")
        val extra = source.copy(id = "extra", category = "Food", tags = emptyList())
        val fourth = source.copy(id = "fourth", category = "Food", tags = emptyList())
        val long = source.copy(id = "long", durationMinutes = 45)
        val active = source.copy(id = "active")
        val completed = source.copy(id = "completed")
        val catalog = listOf(source, same, other, extra, fourth, long, active, completed)
        val catalogue = object : SidequestsRepository by repository {
            override fun allQuests() = catalog
            override fun questById(id: String) = catalog.firstOrNull { it.id == id } ?: repository.questById(id)
        }
        val vm = signedInOffline(catalogue = catalogue)
        vm.setAvailableTime(30)
        vm.acceptQuest(same.id)
        val abandoned = vm.uiState.value.attemptIdByQuest.getValue(same.id)
        vm.startAnotherQuest(AbandonmentReason.entries.first())
        vm.saveAndExit(abandoned)
        vm.acceptQuest(active.id)
        vm.acceptQuest(source.id)
        val attempt = vm.uiState.value.attemptIdByQuest.getValue(source.id)
        vm.completeCurrentStep(attempt, 0)
        assertEquals(AppScreen.Rating, vm.uiState.value.screen)

        val acceptedAt = "2026-11-01T12:00:00Z"
        val staleHistory = summarizeQuestStatistics(HistorySnapshot("alice", listOf(
            HistoryAttempt(abandoned, "alice", same.id, "in_progress", acceptedAt),
            HistoryAttempt("done", "alice", completed.id, "completed", acceptedAt),
        ), emptyList()))
        fun suggestions(stars: Int) = completionSuggestions(
            source, vm.allQuests(), vm.uiState.value, staleHistory, stars,
        ).map { it.id }
        assertEquals(listOf("same", "other", "extra"), suggestions(5))
        assertEquals("other", suggestions(1).first())
        assertEquals(listOf("same", "other", "extra"), suggestions(5))
        vm.openSimilarQuest(same.id, 1)
        assertEquals(same.id, vm.selectedQuest().id)
        vm.closeQuestDetail()
        assertEquals(AppScreen.Rating, vm.uiState.value.screen)
        assertEquals(source.id, vm.activeQuest().id)
        vm.submitRating(5, setOf("Worth it"))
        assertEquals(AppScreen.Rating, vm.uiState.value.screen)
        vm.submitRating(1, emptySet())
        advanceUntilIdle()
        assertEquals(AppScreen.Explorer, vm.uiState.value.screen)

        val saved = store().read()
        assertTrue(saved.photoQuests.getValue(attempt).closed)
        assertTrue(saved.photoQuests.getValue(abandoned).closed)
        assertTrue(saved.progressOps.none { it.type == PendingProgressOp.SAVE })
        val lifecycle = saved.progressOps.filter { it.attemptId == attempt }
        assertEquals(listOf(PendingProgressOp.ACCEPT, PendingProgressOp.PROGRESS, PendingProgressOp.RATE), lifecycle.map { it.type })
        assertTrue(lifecycle[1].completed)
        assertEquals(5, lifecycle.last().stars)
        assertEquals(setOf("Worth it"), lifecycle.last().tags)
        val opened = saved.events.single { it.eventType == "similar_quest_opened" }
        assertEquals(same.id, opened.questId)
        assertEquals(source.id, opened.metadata.getValue("source_quest_id").jsonPrimitive.content)
        assertEquals("1", opened.metadata.getValue("rank").jsonPrimitive.content)
        assertEquals(0L, vm.uiState.value.historyRevision)
        QuestProgressSyncEngine(store(), { "alice" }) { Result.success(Unit) }.drain("alice")
        advanceUntilIdle()
        assertEquals(saved.progressOps.count { it.type != PendingProgressOp.RATE }.toLong(), vm.uiState.value.historyRevision)
        assertTrue(store().read().progressOps.isEmpty())
    }

    @Test fun `late taps cannot advance a different step or complete an attempt twice`() = runTest(dispatcher) {
        val repository = InMemorySidequestsRepository()
        val quest = repository.questById("library-deep-dive").run {
            copy(steps = steps.take(2).map { it.copy(requiresPhoto = false) })
        }
        val catalogue = object : SidequestsRepository by repository {
            override fun questById(id: String) = if (id == quest.id) quest else repository.questById(id)
        }
        val vm = signedInOffline(catalogue = catalogue)
        vm.acceptQuest(quest.id)
        val attempt = vm.uiState.value.attemptIdByQuest.getValue(quest.id)

        vm.completeCurrentStep(attempt, 0)
        vm.completeCurrentStep(attempt, 0)
        vm.completeCurrentStep("different-attempt", 1)
        assertEquals(setOf(0), vm.activeProgress().completedSteps)

        vm.completeCurrentStep(attempt, 1)
        vm.completeCurrentStep(attempt, 1)
        vm.detachAccount()
        vm.attachAccount("bob")
        advanceUntilIdle()

        val operations = store().read().progressOps.filter { it.type == PendingProgressOp.PROGRESS }
        assertEquals(listOf(false, true), operations.map { it.completed })
        assertEquals(1, analytics.events.count { it == "quest_started" })
        assertEquals(1, analytics.events.count { it == "quest_completed" })
        assertEquals(listOf("alice", "alice"), analytics.lifecycleOwners)
    }

    @Test fun `Instant Plan and detail acceptance each queue a single BQ4 measurement`() = runTest(dispatcher) {
        val quickQuest = "library-deep-dive"
        val detailQuest = "botanical-garden"
        val recommendations = object : RecommendationRepository {
            override suspend fun recommend(
                availableMinutes: Int,
                preferences: UserPreferences,
                excludedQuestIds: Set<String>,
                limit: Int,
            ) = Result.success(listOf(RecommendationResult(quickQuest, 1.0), RecommendationResult(detailQuest, 0.5)))
        }
        val vm = signedInOffline(recommendations)
        vm.refreshRecommendations()
        advanceUntilIdle()
        val batch = vm.uiState.value.bq8FreshBatchId
        assertNotNull(batch)

        vm.smartPickVisible(quickQuest)
        vm.startInstantPlan(quickQuest)
        assertEquals(AppScreen.ActiveQuest, vm.uiState.value.screen)
        val originalAttempt = vm.uiState.value.attemptIdByQuest.getValue(quickQuest)
        vm.navigate(AppScreen.Explorer)
        vm.startInstantPlan(quickQuest)
        assertEquals(originalAttempt, vm.uiState.value.attemptIdByQuest.getValue(quickQuest))

        vm.navigate(AppScreen.Explorer)
        vm.smartPickVisible(detailQuest)
        vm.openQuestDetail(detailQuest, fromSmartPicks = true)
        vm.acceptQuest(detailQuest)
        advanceUntilIdle()

        val operations = store().read().progressOps.filter { it.type == PendingProgressOp.ACCEPT }
        assertEquals(listOf(quickQuest, detailQuest), operations.map { it.questId })
        for ((index, operation) in operations.withIndex()) {
            assertEquals(listOf("quest_accepted", "recommendation_accepted"), operation.confirmedEvents.map { it.eventType })
            val metadata = operation.confirmedEvents.last().metadata
            assertEquals(batch, metadata.getValue("batch_id").jsonPrimitive.content)
            assertEquals(listOf("instant_plan", "standard")[index], metadata.getValue("start_path").jsonPrimitive.content)
            assertEquals(index.toString(), metadata.getValue("interactions_to_start").jsonPrimitive.content)
            assertTrue(metadata.getValue("seconds_to_start").jsonPrimitive.content.toDouble() >= 0.0)
        }
        assertFalse(analytics.events.contains("recommendation_accepted"))
    }

    @Test fun `session social level updates requests while profile and next account keep their defaults`() = runTest(dispatcher) {
        val requestedLevels = mutableListOf<SocialLevel>()
        val recommendationSource = object : RecommendationRepository {
            override suspend fun recommend(
                availableMinutes: Int, preferences: UserPreferences, excludedQuestIds: Set<String>, limit: Int,
            ): Result<List<RecommendationResult>> {
                requestedLevels += preferences.socialLevel
                return Result.success(emptyList())
            }
        }
        val vm = signedInOffline(recommendationSource)
        val profileLevel = vm.uiState.value.preferences.socialLevel
        vm.setSessionSocialLevel(SocialLevel.Group)
        advanceUntilIdle()
        assertEquals(SocialLevel.Group, requestedLevels.last())
        assertEquals(profileLevel, vm.uiState.value.preferences.socialLevel)
        vm.detachAccount()
        vm.attachAccount("bob")
        advanceUntilIdle()
        assertNull(vm.uiState.value.sessionSocialLevel)
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
        val lifecycleOwners = mutableListOf<String?>()
        override suspend fun track(expectedUserId: String?, sessionId: String, eventType: String, availableMinutes: Int,
            preferences: UserPreferences, quest: Quest?, metadata: JsonObject): Result<Unit> {
            events += eventType
            if (eventType == "quest_started" || eventType == "quest_completed") lifecycleOwners += expectedUserId
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
