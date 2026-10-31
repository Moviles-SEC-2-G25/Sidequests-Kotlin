package com.sidequests.app.ui

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.sidequests.app.context.*
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.data.analytics.AnalyticsRepository
import com.sidequests.app.data.progress.QuestProgressRepository
import com.sidequests.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuestAlternativesFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val viewModelJobs = mutableListOf<Job>()
    private val progress = RecordingProgress()
    private val analytics = RecordingAnalytics()
    private val context = UserContext(timeOfDay = TimeOfDay.AFTERNOON, weather = WeatherData(20.0, WeatherCondition.CLEAR))

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

    private fun viewModel(reader: suspend () -> UserContext = { context }, simulated: Boolean = false): AppViewModel {
        val location = object : LocationProvider { override suspend fun getCurrentLocation(): LocationData? = null }
        return AppViewModel(
            contextManager = ContextManager(location, MockWeatherProvider()),
            repository = InMemorySidequestsRepository(),
            progressRepository = progress,
            analyticsRepository = analytics,
            recommendationRepository = null,
            photoProofRepository = null,
            alternativeContextReader = reader,
            alternativeContextSimulated = simulated,
        ).also {
            models.put("vm-${viewModelJobs.size}", it)
            viewModelJobs += requireNotNull(it.viewModelScope.coroutineContext[Job])
        }
    }

    @Test fun `abandonment is recorded once and suggestions never automatically accept a quest`() = runTest {
        val vm = viewModel()
        vm.acceptQuest("botanical-garden")
        advanceUntilIdle()
        val sourceAttempt = vm.uiState.value.attemptIdByQuest.getValue("botanical-garden")
        vm.startAnotherQuest(AbandonmentReason.RanOutOfTime)
        vm.startAnotherQuest(AbandonmentReason.TooFar)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(AppScreen.QuestAlternatives, state.screen)
        assertEquals(listOf(sourceAttempt to "ran_out_of_time"), progress.abandonments)
        assertTrue(state.attemptIdByQuest.isEmpty())
        assertEquals(1, progress.accepted.size)
        assertEquals(1, analytics.events.count { it.type == "quest_abandoned" })
        assertEquals(sourceAttempt, analytics.events.single { it.type == "quest_abandoned" }.metadata["attempt_id"]?.jsonPrimitive?.content)
        assertTrue(state.progressByQuest.getValue("botanical-garden").abandoned)
        val suggestions = requireNotNull(state.questAlternatives).suggestions
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.all { it.quest.id != "botanical-garden" && it.quest.durationMinutes < 30 })

        val alternativeId = suggestions.first().quest.id
        vm.openQuestAlternative(alternativeId)
        advanceUntilIdle()
        assertEquals(AppScreen.QuestDetail, vm.uiState.value.screen)
        assertEquals(1, progress.accepted.size)
        vm.acceptQuest(alternativeId)
        advanceUntilIdle()
        assertEquals(2, progress.accepted.size)
        assertNotEquals(sourceAttempt, vm.uiState.value.attemptIdByQuest[alternativeId])
        assertEquals(1, analytics.events.count { it.type == "quest_alternative_selected" })
    }

    @Test fun `offline acceptance cannot create an unconfirmed attempt or move to quest`() = runTest {
        val vm = viewModel()
        vm.onDeviceStatusChanged(DeviceStatus(isOnline = false))
        val before = vm.uiState.value.screen
        vm.acceptQuest("botanical-garden")
        advanceUntilIdle()
        assertEquals(before, vm.uiState.value.screen)
        assertTrue(vm.uiState.value.attemptIdByQuest.isEmpty())
        assertTrue(progress.accepted.isEmpty())
        assertTrue(vm.uiState.value.progressSyncError.orEmpty().contains("internet"))
        assertFalse(analytics.events.any { it.type == "quest_accepted" })
    }

    @Test fun `failed remote acceptance is not counted as a successful analytics event`() = runTest {
        progress.failNextAccept = true
        val vm = viewModel()
        vm.acceptQuest("botanical-garden")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.progressSyncError)
        assertFalse(analytics.events.any {
            it.type == "quest_accepted" || it.type == "recommendation_accepted"
        })
    }

    @Test fun `confirmed remote acceptance produces a quest accepted event`() = runTest {
        val vm = viewModel()
        vm.acceptQuest("botanical-garden")
        advanceUntilIdle()
        assertNull(vm.uiState.value.progressSyncError)
        assertEquals(1, analytics.events.count { it.type == "quest_accepted" })
    }

    @Test fun `save progress keeps the attempt and does not request alternatives or record abandonment`() = runTest {
        var reads = 0
        val vm = viewModel(reader = { reads++; context })
        vm.acceptQuest("library-deep-dive")
        advanceUntilIdle()
        val attempt = vm.uiState.value.attemptIdByQuest.getValue("library-deep-dive")
        vm.saveAndExit()
        advanceUntilIdle()
        assertEquals(0, reads)
        assertEquals(AppScreen.Explorer, vm.uiState.value.screen)
        assertNull(vm.uiState.value.questAlternatives)
        assertEquals(attempt, vm.uiState.value.attemptIdByQuest["library-deep-dive"])
        assertEquals(listOf(attempt), progress.saved)
        assertTrue(progress.abandonments.isEmpty())
        assertFalse(analytics.events.any { it.type == "quest_abandoned" || it.type.startsWith("quest_alternative_") })
    }

    @Test fun `missing weather falls back to current time and marks no fake weather`() = runTest {
        val vm = viewModel(reader = { error("Weather service unavailable") })
        vm.acceptQuest("botanical-garden")
        advanceUntilIdle()
        vm.startAnotherQuest(AbandonmentReason.RanOutOfTime)
        advanceUntilIdle()
        val result = vm.uiState.value.questAlternatives!!
        assertFalse(result.loading)
        assertNull(result.context!!.weather)
        assertTrue(result.suggestions.isNotEmpty())
        assertTrue(analytics.events.filter { it.type == "quest_alternative_shown" }
            .all { it.metadata["weather_condition"]?.jsonPrimitive?.content == "unknown" })
    }

    @Test fun `leaving while context loads cancels late suggestions`() = runTest {
        val vm = viewModel(reader = { delay(1_000); context })
        vm.acceptQuest("botanical-garden")
        advanceUntilIdle()
        vm.startAnotherQuest(AbandonmentReason.RanOutOfTime)
        runCurrent()
        assertTrue(vm.uiState.value.questAlternatives!!.loading)
        vm.dismissQuestAlternatives()
        advanceUntilIdle()
        assertEquals(AppScreen.Explorer, vm.uiState.value.screen)
        assertNull(vm.uiState.value.questAlternatives)
        assertFalse(analytics.events.any { it.type == "quest_alternative_shown" })
    }

    @Test fun `slow context times out to usable time-only suggestions`() = runTest {
        val vm = viewModel(reader = { delay(50_000); context })
        vm.acceptQuest("botanical-garden")
        advanceUntilIdle()
        vm.startAnotherQuest(AbandonmentReason.RanOutOfTime)
        advanceUntilIdle()
        val result = vm.uiState.value.questAlternatives!!
        assertFalse(result.loading)
        assertNull(result.context!!.weather)
        assertTrue(result.suggestions.isNotEmpty())
        assertTrue(testScheduler.currentTime <= 8_000)
    }

    @Test fun `debug suggestions are explicitly distinguished in analytics`() = runTest {
        val vm = viewModel(simulated = true)
        vm.acceptQuest("botanical-garden")
        advanceUntilIdle()
        vm.startAnotherQuest(AbandonmentReason.RanOutOfTime)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.questAlternatives!!.simulatedContext)
        val shown = analytics.events.filter { it.type == "quest_alternative_shown" }
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.all { it.metadata["context_source"]?.jsonPrimitive?.content == "simulated_debug" })
    }

    private data class Event(val type: String, val metadata: JsonObject)
    private class RecordingAnalytics : AnalyticsRepository {
        val events = mutableListOf<Event>()
        override suspend fun track(sessionId: String, eventType: String, availableMinutes: Int,
            preferences: UserPreferences, quest: Quest?, metadata: JsonObject): Result<Unit> {
            events += Event(eventType, metadata)
            return Result.success(Unit)
        }
    }
    private class RecordingProgress : QuestProgressRepository {
        var failNextAccept = false
        val accepted = mutableListOf<Pair<String, String>>()
        val abandonments = mutableListOf<Pair<String, String?>>()
        val saved = mutableListOf<String>()
        override suspend fun acceptQuest(questId: String, attemptId: String): Result<Unit> {
            if (failNextAccept) {
                failNextAccept = false
                return Result.failure(IllegalStateException("Supabase is unavailable"))
            }
            accepted += questId to attemptId
            return Result.success(Unit)
        }
        override suspend fun abandonQuest(attemptId: String, currentStep: Int, completedSteps: Set<Int>, reason: String?): Result<Unit> {
            abandonments += attemptId to reason
            return Result.success(Unit)
        }
        override suspend fun saveProgress(attemptId: String, currentStep: Int, completedSteps: Set<Int>, reason: String?): Result<Unit> {
            saved += attemptId
            return Result.success(Unit)
        }
        override suspend fun updateProgress(attemptId: String, currentStep: Int, completedSteps: Set<Int>, completed: Boolean) = Result.success(Unit)
        override suspend fun rateQuest(attemptId: String, stars: Int, tags: Set<String>) = Result.success(Unit)
    }
}
