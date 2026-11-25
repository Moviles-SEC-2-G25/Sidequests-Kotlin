package com.sidequests.app.data.progress

import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.local.PendingProgressOp
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Eventual connectivity: replays queued quest lifecycle changes in the order the user made them.
 * Called serially by WorkManager on IO, before photo uploads, because a photo proof can only be
 * registered once its attempt exists in Supabase. A failed operation stays queued and blocks only
 * the later operations of the same attempt; other attempts keep syncing.
 */
class QuestProgressSyncEngine(
    private val store: LexLocalStore,
    private val activeUserId: () -> String?,
    private val apply: suspend (PendingProgressOp) -> Result<Unit>,
) {
    /** Returns true when an operation failed and should be retried later. */
    suspend fun drain(userId: String): Boolean =
        ownerLocks.getOrPut(userId) { Mutex() }.withLock { drainSerially(userId) }

    private suspend fun drainSerially(userId: String): Boolean {
        var retryNeeded = false
        val blockedAttempts = mutableSetOf<String>()
        val pending = store.read().progressOps.filter { it.userId == userId }
        for (op in pending) {
            if (activeUserId() != userId) break
            if (op.attemptId in blockedAttempts) continue
            val result = try { apply(op) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure == null) {
                // Removing the operation and releasing its analytics events is one atomic write.
                store.update { state ->
                    state.copy(
                        progressOps = state.progressOps.filterNot { it.id == op.id },
                        events = state.events + op.confirmedEvents,
                    )
                }
            } else {
                retryNeeded = true
                blockedAttempts += op.attemptId
                store.update { state ->
                    state.copy(progressOps = state.progressOps.map {
                        if (it.id == op.id) it.copy(attempts = it.attempts + 1) else it
                    })
                }
            }
        }
        return retryNeeded
    }

    companion object {
        private val ownerLocks = ConcurrentHashMap<String, Mutex>()
    }
}
