package com.sidequests.app.data.group

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupChallengeLoaderTest {
    @Test fun bothReadsStartBeforeEitherCompletes() = runTest {
        val mineStarted = CompletableDeferred<Unit>()
        val availableStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gateway = object : GroupChallengeGateway {
            override suspend fun list(mine: Boolean): List<GroupChallenge> {
                if (mine) mineStarted.complete(Unit) else availableStarted.complete(Unit)
                release.await()
                return listOf(GroupChallenge(if (mine) "mine" else "available", "quest",
                    "Test", 1, if (mine) 0 else null, 2, 0))
            }
            override suspend fun act(action: GroupAction): GroupActionResult = error("not used")
        }
        val result = async { GroupChallengeLoader(gateway, StandardTestDispatcher(testScheduler)).load() }
        runCurrent()
        assertTrue(mineStarted.isCompleted)
        assertTrue(availableStarted.isCompleted)
        assertTrue(!result.isCompleted)
        release.complete(Unit)
        val lists = result.await()
        assertEquals("mine", lists.mine.getOrThrow().single().challengeId)
        assertEquals("available", lists.available.getOrThrow().single().challengeId)
    }

    @Test fun independentFailureKeepsSuccessfulList() = runTest {
        val gateway = object : GroupChallengeGateway {
            override suspend fun list(mine: Boolean): List<GroupChallenge> {
                if (mine) error("unavailable")
                return emptyList()
            }
            override suspend fun act(action: GroupAction): GroupActionResult = error("not used")
        }
        val result = GroupChallengeLoader(gateway, StandardTestDispatcher(testScheduler)).load()
        assertTrue(result.mine.isFailure)
        assertTrue(result.available.isSuccess)
    }
}
