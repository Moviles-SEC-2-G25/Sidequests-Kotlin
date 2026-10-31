package com.sidequests.app.ui

import androidx.lifecycle.ViewModelStore
import com.sidequests.app.context.ContextManager
import com.sidequests.app.context.LocationData
import com.sidequests.app.context.LocationProvider
import com.sidequests.app.context.MockWeatherProvider
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.data.analytics.AnalyticsRepository
import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.model.Quest
import com.sidequests.app.model.UserPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class Bq9OfflineRecommendationFlowTest {
    @get:Rule val folder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        models.clear()
        Dispatchers.resetMain()
    }

    @Test fun `skipping a recommendation queues event before any network send`() = runTest(dispatcher) {
        val store = LexLocalStore(File(folder.root, "queue.json"))
        var directSends = 0
        var schedules = 0
        val analytics = object : AnalyticsRepository {
            override suspend fun track(
                sessionId: String,
                eventType: String,
                availableMinutes: Int,
                preferences: UserPreferences,
                quest: Quest?,
                metadata: JsonObject,
            ): Result<Unit> {
                directSends++
                return Result.failure(IllegalStateException("Offline"))
            }
        }
        val locationProvider = object : LocationProvider {
            override suspend fun getCurrentLocation(): LocationData? = null
        }
        val vm = AppViewModel(
            contextManager = ContextManager(locationProvider, MockWeatherProvider()),
            repository = InMemorySidequestsRepository(),
            progressRepository = null,
            analyticsRepository = analytics,
            recommendationRepository = null,
            photoProofRepository = null,
            localStore = store,
            localIoDispatcher = dispatcher,
            scheduleLocalSync = { schedules++ },
        )
        models.put("bq9", vm)
        vm.attachAccount("alice")
        advanceUntilIdle()
        val before = schedules

        vm.skipQuest("botanical-garden")
        advanceUntilIdle()
        val events = LexLocalStore(File(folder.root, "queue.json")).read().events
        val skipped = events.single { it.eventType == "recommendation_skipped" }
        assertEquals("alice", skipped.userId)
        assertEquals("botanical-garden", skipped.questId)
        assertEquals(0, directSends)
        assertTrue("WorkManager scheduling is requested", schedules > before)
    }
}
