package com.sidequests.app.ui

import com.sidequests.app.data.history.HistoryLoad
import com.sidequests.app.data.history.HistorySource
import com.sidequests.app.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class MissionHistoryControllerTest {
    private val data = HistorySnapshot("alice", listOf(
        HistoryAttempt("1","alice","cafe","completed","2026-11-01T12:00:00Z"),
        HistoryAttempt("2","alice","yoga","abandoned","2026-11-02T12:00:00Z")
    ), listOf(HistoryQuest("cafe","Café","food",45), HistoryQuest("yoga","Yoga","mindfulness",20)))

    @Test fun typingDebouncesAndFilterUsesLatestQueryImmediately() = runTest {
        var builds = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val c = MissionHistoryController("alice", HistorySource { _, _ -> HistoryLoad(data,true) },
            backgroundScope, dispatcher) { d, f, q -> builds++; buildMissionHistory(d,f,q) }
        c.connectivityChanged(true)
        runCurrent()
        assertEquals(1,builds)
        c.setQuery("c")
        advanceTimeBy(100)
        c.setQuery("cafe")
        advanceTimeBy(299)
        runCurrent()
        assertEquals(1,builds)
        c.setFilter(HistoryFilter.Completed)
        runCurrent()
        assertEquals(2,builds)
        assertEquals(1,c.state.value.history.matchCount)
        assertEquals("1",c.state.value.history.months.single().entries.single().attempt.id)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2,builds)
        c.close()
    }

    @Test fun offlineSearchAndThreeSecondReconnectDoNotDuplicateRequests() = runTest {
        val calls = mutableListOf<Boolean>()
        val c = MissionHistoryController("alice", HistorySource { _, online ->
            calls += online
            HistoryLoad(data,online)
        }, backgroundScope, StandardTestDispatcher(testScheduler))
        c.connectivityChanged(false); runCurrent()
        assertTrue(c.state.value.fromCache)
        c.setQuery("yoga"); advanceTimeBy(300); runCurrent()
        assertEquals(1,c.state.value.history.matchCount)
        assertEquals(listOf(false),calls)
        c.connectivityChanged(true); advanceTimeBy(2_900)
        c.connectivityChanged(false); advanceTimeBy(500); runCurrent()
        assertEquals(listOf(false),calls)
        c.connectivityChanged(true); advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf(false,true),calls)
        assertFalse(c.state.value.fromCache)
        c.close()
    }

    @Test fun oldBuildCannotOverwriteNewFilterAndCloseCancelsWork() = runTest {
        val block = CompletableDeferred<Unit>()
        val c = MissionHistoryController("alice", HistorySource { _, _ -> HistoryLoad(data,true) },
            backgroundScope, StandardTestDispatcher(testScheduler)) { d, f, q ->
            if (q == "cafe" && f == HistoryFilter.All) block.await()
            buildMissionHistory(d,f,q)
        }
        c.connectivityChanged(true); runCurrent()
        c.setQuery("cafe"); advanceTimeBy(300); runCurrent()
        c.setFilter(HistoryFilter.Abandoned); runCurrent()
        assertEquals(0,c.state.value.history.matchCount)
        block.complete(Unit); runCurrent()
        assertEquals(0,c.state.value.history.matchCount)
        c.close()
    }

    @Test fun computationRunsOnDefaultDispatcherOffCallingThread() = kotlinx.coroutines.runBlocking {
        val callingThread = Thread.currentThread()
        val actualThread = CompletableDeferred<Thread>()
        val c = MissionHistoryController("alice", HistorySource { _, _ -> HistoryLoad(data,true) },
            this, build = { d, f, q ->
                actualThread.complete(Thread.currentThread())
                buildMissionHistory(d,f,q)
            })
        try {
            c.connectivityChanged(true)
            val thread = kotlinx.coroutines.withTimeout(5_000) { actualThread.await() }
            assertNotSame(callingThread, thread)
        } finally { c.close() }
    }
}
