package com.sidequests.app.data.history

import com.sidequests.app.domain.HistoryAttempt
import com.sidequests.app.domain.HistorySnapshot
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MissionHistoryRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val alice = HistorySnapshot("alice", listOf(
        HistoryAttempt("a","alice","quest","completed","2026-11-01T12:00:00Z")
    ), emptyList())

    @Test fun offlineUsesAccountScopedPersistedSnapshotWithoutNetwork() = runTest {
        val store = MissionHistoryStore(temporary.newFolder())
        store.save(alice)
        var calls = 0
        val repository = MissionHistoryRepository(store, HistoryRemote { calls++; error("network") },
            StandardTestDispatcher(testScheduler))
        assertEquals(alice, repository.load("alice",false).snapshot)
        assertNull(repository.load("bob",false).snapshot)
        assertEquals(0,calls)
    }

    @Test fun serverFailureRetainsCacheAndSuccessfulEmptyIsPersisted() = runTest {
        val store = MissionHistoryStore(temporary.newFolder())
        store.save(alice)
        val failing = MissionHistoryRepository(store, HistoryRemote { error("offline") }, StandardTestDispatcher(testScheduler))
        assertEquals(HistoryLoad(alice,false), failing.load("alice",true))
        val empty = alice.copy(attempts=emptyList())
        val online = MissionHistoryRepository(store, HistoryRemote { empty }, StandardTestDispatcher(testScheduler))
        assertEquals(HistoryLoad(empty,true),online.load("alice",true))
        assertEquals(empty,store.read("alice"))
    }

    @Test fun foreignAccountResponseCannotReplaceSnapshot() = runTest {
        val store = MissionHistoryStore(temporary.newFolder())
        val wrong = MissionHistoryRepository(store, HistoryRemote { alice }, StandardTestDispatcher(testScheduler))
        assertNull(wrong.load("bob",true).snapshot)
        assertNull(store.read("bob"))
    }
}
