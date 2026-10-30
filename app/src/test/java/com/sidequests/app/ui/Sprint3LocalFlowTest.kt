package com.sidequests.app.ui

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.sidequests.app.context.*
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.AbandonmentReason
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class Sprint3LocalFlowTest {
    @get:Rule val folder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val viewModelJobs = mutableListOf<Job>()
    private val scheduled = Channel<Unit>(Channel.UNLIMITED)
    private var count = 0
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
            repository = InMemorySidequestsRepository(), progressRepository = null,
            analyticsRepository = null, recommendationRepository = null, photoProofRepository = null,
            localStore = store(), scheduleLocalSync = { scheduled.trySend(Unit) },
            localIoDispatcher = dispatcher,
        ).also {
            models.put("vm-${count++}", it)
            viewModelJobs += requireNotNull(it.viewModelScope.coroutineContext[Job])
        }
    }

    @Test fun `real view model actions produce linked first recommendation events`() = runTest {
        val vm = vm()
        vm.attachAccount("alice")
        scheduled.receive()
        repeat(4) { vm.nextOnboardingStep(); scheduled.receive() }
        assertEquals(AppScreen.Explorer, vm.uiState.value.screen)
        val quest = vm.recommendations().first()
        vm.firstOnboardingRecommendationVisible(quest.id)
        scheduled.receive()
        vm.openQuestDetail(quest.id, fromSmartPicks = true)
        vm.acceptQuest(quest.id)
        scheduled.receive()
        val journey = store().read().journeys.getValue("alice")
        assertTrue(journey.firstAccepted)
        assertEquals(quest.id, journey.firstQuestId)
        assertEquals(4, store().read().events.count { it.eventType == "onboarding_step_completed" })
    }

    @Test fun `Explorer time and location survive a process restart for the same account`() = runTest {
        val vm = vm()
        vm.attachAccount("alice")
        scheduled.receive()
        vm.skipOnboarding()
        scheduled.receive()
        vm.setAvailableTime(45)
        vm.setLocationMode("gps")
        advanceUntilIdle()

        assertEquals(45, store().read().journeys.getValue("alice").preferences.typicalTime)
        assertEquals("gps", store().read().journeys.getValue("alice").preferences.locationMode)

        models.clear()
        val restored = vm()
        restored.attachAccount("alice")
        advanceUntilIdle()
        assertEquals(45, restored.uiState.value.availableTime)
        assertEquals(45, restored.uiState.value.preferences.typicalTime)
        assertEquals("gps", restored.uiState.value.preferences.locationMode)
    }

    @Test fun `capture queue and step evidence survive view model recreation`() = runTest {
        val vm = vm()
        vm.attachAccount("alice")
        scheduled.receive()
        vm.skipOnboarding()
        scheduled.receive()
        vm.acceptQuest("botanical-garden")
        scheduled.receive()
        val photoStep = vm.activeQuest().steps.indexOfFirst { it.requiresPhoto }
        vm.jumpToStep(photoStep)
        val photo = File(folder.root, "capture.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        vm.markPhotoProofCaptured(photoStep, photo.absolutePath)
        vm.uiState.first { it.pendingPhotoUploads == 1 }
        models.clear()
        val restored = vm()
        restored.attachAccount("alice")
        restored.uiState.first { it.accountUserId == "alice" && it.pendingPhotoUploads == 1 }
        assertEquals(photo.absolutePath, restored.activeProgress().localPhotoProofByStep[photoStep])
        assertEquals(photoStep, restored.activeProgress().currentStep)
        assertEquals("botanical-garden", restored.uiState.value.activeQuestId)
        assertTrue(restored.hasActiveQuest())
    }

    @Test fun `switching accounts hides the previous owners proof and active attempt`() = runTest {
        val vm = vm()
        vm.attachAccount("alice")
        scheduled.receive()
        vm.skipOnboarding()
        scheduled.receive()
        vm.acceptQuest("botanical-garden")
        scheduled.receive()
        val photo = File(folder.root, "capture.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        vm.markPhotoProofCaptured(vm.activeQuest().steps.indexOfFirst { it.requiresPhoto }, photo.absolutePath)
        vm.uiState.first { it.pendingPhotoUploads == 1 }
        vm.attachAccount("bob")
        vm.uiState.first { it.accountUserId == "bob" }
        assertEquals(0, vm.uiState.value.pendingPhotoUploads)
        assertFalse(vm.hasActiveQuest())
        assertTrue(vm.activeProgress().localPhotoProofByStep.isEmpty())
        assertEquals("alice", store().read().photos.single().userId)
    }

    @Test fun `abandonment during capture persistence cannot restore the old attempt as active`() = runTest {
        val vm = vm()
        vm.attachAccount("alice")
        scheduled.receive()
        vm.skipOnboarding()
        scheduled.receive()
        vm.acceptQuest("botanical-garden")
        scheduled.receive()
        val photo = File(folder.root, "capture.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        vm.markPhotoProofCaptured(vm.activeQuest().steps.indexOfFirst { it.requiresPhoto }, photo.absolutePath)
        vm.startAnotherQuest(AbandonmentReason.RanOutOfTime)
        advanceUntilIdle()
        assertTrue(store().read().photoQuests.values.single().closed)
        assertEquals("pending", store().read().photos.single().status)
        models.clear()
        val restored = vm()
        restored.attachAccount("alice")
        restored.uiState.first { it.accountUserId == "alice" }
        assertFalse(restored.hasActiveQuest())
    }

    @Test fun `signing back in resumes the same setup and saved choices`() = runTest {
        val vm = vm()
        vm.attachAccount("alice")
        scheduled.receive()
        vm.toggleInterest("Art")
        advanceUntilIdle()
        vm.nextOnboardingStep()
        scheduled.receive()
        val journeyId = store().read().journeys.getValue("alice").id
        vm.detachAccount()
        assertNull(vm.uiState.value.accountUserId)
        vm.attachAccount("alice")
        scheduled.receive()
        assertEquals(1, vm.uiState.value.onboardingStep)
        assertEquals(setOf("Art"), vm.uiState.value.preferences.interests)
        assertEquals(journeyId, store().read().journeys.getValue("alice").id)
        assertEquals(1, store().read().events.count { it.eventType == "onboarding_started" })
        assertEquals(1, store().read().events.count { it.eventType == "onboarding_resumed" })
    }
}
