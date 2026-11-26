package com.sidequests.app.ui

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.sidequests.app.context.ContextManager
import com.sidequests.app.context.LocationData
import com.sidequests.app.context.LocationProvider
import com.sidequests.app.context.MockWeatherProvider
import com.sidequests.app.context.TimeOfDay
import com.sidequests.app.context.UserContext
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.model.AbandonmentReason
import com.sidequests.app.model.QuestDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/** End-to-end check of the smart difficulty (BQ13) flow through the real ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartDifficultyFlowTest {
    private val dispatcher = StandardTestDispatcher()

    private val models = ViewModelStore()
    private val viewModelJobs = mutableListOf<Job>()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        // Wait for cancelled ViewModel work (e.g. context reads on Dispatchers.IO) before
        // restoring Main, or a late resume leaks into the next test class.
        try {
            runTest(dispatcher) {
                models.clear()
                viewModelJobs.joinAll()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(): AppViewModel {
        val location = object : LocationProvider { override suspend fun getCurrentLocation(): LocationData? = null }
        return AppViewModel(
            contextManager = ContextManager(location, MockWeatherProvider()),
            repository = InMemorySidequestsRepository(),
            progressRepository = null,
            analyticsRepository = null,
            recommendationRepository = null,
            photoProofRepository = null,
            alternativeContextReader = { UserContext(timeOfDay = TimeOfDay.AFTERNOON) },
        ).also {
            models.put("vm-${viewModelJobs.size}", it)
            viewModelJobs += requireNotNull(it.viewModelScope.coroutineContext[Job])
        }
    }

    @Test fun `two too-difficult abandons at Medium level suggest Easy`() = runTest {
        val vm = viewModel()
        vm.setDifficulty(QuestDifficulty.Medium)

        listOf("botanical-garden", "library-deep-dive").forEachIndexed { index, questId ->
            vm.acceptQuest(questId)
            advanceUntilIdle()
            vm.startAnotherQuest(AbandonmentReason.TooDifficult)
            advanceUntilIdle()
            assertEquals(index + 1, vm.uiState.value.difficultyOutcomes.size)
            assertEquals(index + 1, vm.difficultyStreaks().abandons)
        }

        assertNotNull(vm.difficultySuggestion())
        val suggestion = vm.difficultySuggestion()!!
        assertEquals(QuestDifficulty.Easy, suggestion.suggested)
        vm.acceptDifficultySuggestion()
        assertEquals(QuestDifficulty.Easy, vm.uiState.value.preferences.difficulty)
    }
}
