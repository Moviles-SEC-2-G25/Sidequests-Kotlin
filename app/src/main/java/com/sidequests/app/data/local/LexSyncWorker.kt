package com.sidequests.app.data.local

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sidequests.app.BuildConfig
import com.sidequests.app.data.photo.PhotoProofSyncEngine
import com.sidequests.app.data.photo.SupabasePhotoProofRepository
import com.sidequests.app.data.remote.SupabaseProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LexSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!SupabaseProvider.isConfigured) return@withContext Result.success()
        val userId = inputData.getString("user_id") ?: return@withContext Result.failure()
        val client = SupabaseProvider.client
        withTimeoutOrNull(30_000) { client.auth.sessionStatus.first { it !is SessionStatus.Initializing } }
            ?: return@withContext Result.retry()
        if (client.auth.currentUserOrNull()?.id != userId) return@withContext Result.success()
        try {
            val store = store(applicationContext)
            val remote = SupabasePhotoProofRepository(client, BuildConfig.SUPABASE_QUEST_PROOFS_BUCKET)
            val retryPhoto = PhotoProofSyncEngine(store, { client.auth.currentUserOrNull()?.id }) { entry ->
                remote.upload(entry.attemptId, entry.questId, entry.stepIndex, File(entry.localPath), entry.id, entry.userId)
            }.drain(userId)
            for (event in store.read().events.filter { it.userId == userId }) {
                if (client.auth.currentUserOrNull()?.id != userId) return@withContext Result.success()
                client.from("analytics_events").insert(buildJsonObject {
                    put("user_id", userId)
                    put("session_id", event.journeyId)
                    put("event_type", event.eventType)
                    put("occurred_at", event.occurredAt)
                    event.questId?.let { put("quest_id", it) }
                    event.category?.let { put("category", it) }
                    event.availableMinutes?.let { put("available_minutes", it) }
                    event.socialLevel?.let { put("social_level", it) }
                    event.locationMode?.let { put("location_mode", it) }
                    event.questDurationMinutes?.let { put("quest_duration_minutes", it) }
                    event.questDifficulty?.let { put("quest_difficulty", it) }
                    event.estimatedCost?.let { put("estimated_cost", it) }
                    event.distanceMeters?.let { put("distance_meters", it) }
                    put("metadata", event.metadata)
                })
                store.update { state -> state.copy(events = state.events.filterNot { it.id == event.id }) }
            }
            if (retryPhoto) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { Result.retry() }
    }

    companion object {
        fun store(context: Context) = LexLocalStore(File(context.noBackupFilesDir, "lex_outbox_v1.json"))

        fun schedule(context: Context, userId: String, retryNow: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<LexSyncWorker>()
                .setInputData(workDataOf("user_id" to userId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // Appending avoids losing an event added just as an existing worker finishes.
            val policy = if (retryNow) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
            WorkManager.getInstance(context).enqueueUniqueWork("lex-sync-$userId", policy, request)
        }
    }
}
