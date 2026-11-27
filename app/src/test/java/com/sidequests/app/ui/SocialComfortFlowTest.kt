package com.sidequests.app.ui

import androidx.lifecycle.ViewModelStore
import com.sidequests.app.context.ContextManager
import com.sidequests.app.context.LocationData
import com.sidequests.app.context.LocationProvider
import com.sidequests.app.context.MockWeatherProvider
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.data.recommendation.RecommendationRepository
import com.sidequests.app.data.recommendation.RecommendationResult
import com.sidequests.app.data.socialcomfort.SocialComfortStore
import com.sidequests.app.domain.ComfortLevel
import com.sidequests.app.domain.SocialComfortSettings
import com.sidequests.app.model.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SocialComfortFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()

    private val created = mutableListOf<AppViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        // AppViewModel reads the context on the real Dispatchers.IO when it is created. Wait for
        // that read to come back to Main before resetting it; otherwise the late resume throws
        // outside this test and is reported as an uncaught exception in the next test class.
        created.forEach { vm ->
            repeat(300) {
                dispatcher.scheduler.advanceUntilIdle()
                if (!vm.contextState.value.loading) return@forEach
                Thread.sleep(10)
            }
        }
        models.clear()
        Dispatchers.resetMain()
    }

    private class MemoryStore : SocialComfortStore {
        val saved = mutableMapOf<String, SocialComfortSettings>()
        override suspend fun load(userId: String) = saved[userId] ?: SocialComfortSettings()
        override suspend fun save(userId: String, settings: SocialComfortSettings) { saved[userId] = settings }
    }

    private fun viewModel(
        store: SocialComfortStore,
        events: MutableList<Pair<String, JsonObject>>,
        exclusions: MutableList<Set<String>>,
    ): AppViewModel {
        val catalogue = InMemorySidequestsRepository()
        val serverOrder = listOf("group-photo-walk", "botanical-garden", "ramen-spot")
        val remote = object : RecommendationRepository {
            override suspend fun recommend(
                availableMinutes: Int,
                preferences: UserPreferences,
                excludedQuestIds: Set<String>,
                limit: Int,
            ): Result<List<RecommendationResult>> {
                exclusions += excludedQuestIds
                return Result.success(serverOrder.mapIndexed { i, id -> RecommendationResult(id, 10.0 - i, rankPosition = i + 1) })
            }
        }
        val location = object : LocationProvider { override suspend fun getCurrentLocation(): LocationData? = null }
        return AppViewModel(
            contextManager = ContextManager(location, MockWeatherProvider()),
            repository = catalogue,
            progressRepository = null,
            analyticsRepository = null,
            recommendationRepository = remote,
            photoProofRepository = null,
            socialComfortStore = store,
            socialComfortEvents = { type, metadata -> events += type to metadata },
        ).also {
            models.put("vm-${System.nanoTime()}", it)
            created += it
        }
    }

    @Test fun lowComfortHidesGroupQuestsOnServerAndClientAndRecordsEvents() = runTest(dispatcher) {
        val store = MemoryStore()
        val events = mutableListOf<Pair<String, JsonObject>>()
        val exclusions = mutableListOf<Set<String>>()
        val vm = viewModel(store, events, exclusions)

        vm.attachAccount("alice")
        advanceUntilIdle()
        assertEquals("bq14_social_comfort_session", events.single().first)

        vm.setSocialComfort(ComfortLevel.Low)
        vm.setSocialAdaptation(true)
        advanceUntilIdle()

        assertTrue("group-photo-walk" in exclusions.last())
        val picks = vm.recommendations().map { it.id }
        assertFalse("group-photo-walk" in picks)
        assertEquals(listOf("botanical-garden", "ramen-spot"), picks) // server order kept
        assertEquals(SocialComfortSettings(ComfortLevel.Low, true), store.saved["alice"])

        val changed = events.filter { it.first == "bq14_social_comfort_changed" }
        assertEquals(2, changed.size)
        val last = changed.last().second
        assertEquals("BQ14", last.getValue("bq").jsonPrimitive.content)
        assertEquals("low", last.getValue("comfort").jsonPrimitive.content)
        assertEquals("true", last.getValue("adaptation_active").jsonPrimitive.content)
        assertEquals("user", last.getValue("adaptation_source").jsonPrimitive.content)
        assertEquals("alice", last.getValue("account_user_id").jsonPrimitive.content)
    }

    @Test fun settingsAreRestoredPerAccountAndNeverMixed() = runTest(dispatcher) {
        val store = MemoryStore().apply { saved["alice"] = SocialComfortSettings(ComfortLevel.Medium, false) }
        val vm = viewModel(store, mutableListOf(), mutableListOf())

        vm.attachAccount("alice")
        advanceUntilIdle()
        assertEquals(SocialComfortSettings(ComfortLevel.Medium, false), vm.uiState.value.socialComfort)

        vm.detachAccount()
        vm.attachAccount("bob")
        advanceUntilIdle()
        assertEquals(SocialComfortSettings(), vm.uiState.value.socialComfort)
        assertFalse("bob" in store.saved)
    }
}
