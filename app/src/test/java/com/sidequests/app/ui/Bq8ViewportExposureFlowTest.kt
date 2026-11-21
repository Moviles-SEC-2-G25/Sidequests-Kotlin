package com.sidequests.app.ui

import androidx.lifecycle.ViewModelStore
import com.sidequests.app.context.ContextManager
import com.sidequests.app.context.LocationData
import com.sidequests.app.context.LocationProvider
import com.sidequests.app.context.MockWeatherProvider
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.recommendation.RecommendationRepository
import com.sidequests.app.data.recommendation.RecommendationResult
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.UserPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Verifies that a successful RPC response alone is not an impression. */
@OptIn(ExperimentalCoroutinesApi::class)
class Bq8ViewportExposureFlowTest {
    @get:Rule val folder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        models.clear()
        Dispatchers.resetMain()
    }

    @Test fun visibleRpcCardsAreRecordedOnceWithMatchingVariantBatchAndRank() = runTest(dispatcher) {
        val catalogue = InMemorySidequestsRepository()
        val selected = catalogue.allQuests().take(2)
        val response = selected.mapIndexed { index, quest ->
            RecommendationResult(
                questId = quest.id,
                score = 10.0 - index,
                variant = "diverse",
                rankPosition = index + 1,
                category = quest.category,
            )
        }
        val remote = object : RecommendationRepository {
            override suspend fun recommend(
                availableMinutes: Int,
                preferences: UserPreferences,
                excludedQuestIds: Set<String>,
                limit: Int,
            ): Result<List<RecommendationResult>> = Result.success(response)
        }
        val store = LexLocalStore(File(folder.root, "bq8-outbox.json"))
        val location = object : LocationProvider {
            override suspend fun getCurrentLocation(): LocationData? = null
        }
        val vm = AppViewModel(
            contextManager = ContextManager(location, MockWeatherProvider()),
            repository = catalogue,
            progressRepository = null,
            analyticsRepository = null,
            recommendationRepository = remote,
            photoProofRepository = null,
            localStore = store,
            localIoDispatcher = dispatcher,
            scheduleLocalSync = {},
        )
        models.put("bq8", vm)

        vm.attachAccount("alice")
        advanceUntilIdle()
        vm.navigate(AppScreen.Explorer)
        vm.refreshRecommendations()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.bq8FreshBatchId)
        assertEquals("diverse", state.bq8Variant)
        assertEquals(selected.map { it.id }, vm.recommendations().map { it.id })
        assertTrue(store.read().events.none { it.eventType == "recommendation_shown" })

        // Only these two cards were actually visible, so only two events exist.
        vm.bq8RecommendationVisible(selected[0].id)
        vm.bq8RecommendationVisible(selected[0].id)
        vm.bq8RecommendationVisible(selected[1].id)
        advanceUntilIdle()

        val shown = store.read().events.filter { it.eventType == "recommendation_shown" }
        assertEquals(2, shown.size)
        assertEquals(setOf(selected[0].id, selected[1].id), shown.map { it.questId }.toSet())
        assertEquals(1, shown.map { it.metadata.getValue("batch_id").jsonPrimitive.content }.toSet().size)
        assertEquals(setOf("diverse"), shown.map { it.metadata.getValue("variant").jsonPrimitive.content }.toSet())
        assertEquals(setOf("1", "2"), shown.map { it.metadata.getValue("rank").jsonPrimitive.content }.toSet())
        assertEquals(setOf("rpc_viewport"), shown.map { it.metadata.getValue("source").jsonPrimitive.content }.toSet())
    }
}
