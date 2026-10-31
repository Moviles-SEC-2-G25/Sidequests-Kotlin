package com.sidequests.app.telemetry

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sidequests.app.data.remote.SupabaseProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Multi-threading (Sprint 3): WorkManager background worker that uploads the BQ1 telemetry queue.
 *
 * It only runs with a network connection (constraint set in [Bq1Telemetry.scheduleUpload]), works on
 * Dispatchers.IO and retries with exponential backoff, so the UI thread is never blocked and events
 * survive offline periods, app restarts and crashes (eventual connectivity).
 */
class TelemetryUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!SupabaseProvider.isConfigured) return Result.success()
        val store = TelemetryStore.getInstance(applicationContext)
        val sender = SupabaseTelemetrySender(SupabaseProvider.client)

        return try {
            withContext(Dispatchers.IO) {
                while (true) {
                    val batch = store.oldest(BATCH_SIZE)
                    if (batch.isEmpty()) break
                    sender.send(batch)
                    store.delete(batch.map { it.id })
                }
            }
            Bq1Telemetry.refreshPendingCount()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    private companion object {
        const val BATCH_SIZE = 50
        const val MAX_ATTEMPTS = 8
    }
}

@Serializable
private data class TelemetryEventDto(
    @SerialName("user_id") val userId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("event_type") val eventType: String,
    val metadata: JsonObject,
)

/** Sends queued events to the shared `analytics_events` table used by the analytics pipeline. */
class SupabaseTelemetrySender(private val client: SupabaseClient) {

    suspend fun send(events: List<PendingTelemetryEvent>) {
        // After a cold start the auth plugin restores the saved session asynchronously.
        withTimeout(10_000) { client.auth.sessionStatus.first { it !is SessionStatus.Initializing } }
        val userId = client.auth.currentUserOrNull()?.id
            ?: error("No signed-in user yet; the events stay queued.")

        client.from("analytics_events").insert(
            events.map { event ->
                val metadata = runCatching { Json.parseToJsonElement(event.metadataJson).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
                TelemetryEventDto(
                    userId = userId,
                    sessionId = event.sessionId,
                    eventType = event.eventType,
                    metadata = JsonObject(metadata + ("occurred_at_millis" to JsonPrimitive(event.createdAtMillis))),
                )
            }
        )
    }
}
