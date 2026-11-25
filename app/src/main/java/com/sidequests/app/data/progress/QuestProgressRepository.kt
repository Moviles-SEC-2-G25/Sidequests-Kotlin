package com.sidequests.app.data.progress

import com.sidequests.app.data.local.PendingProgressOp
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface QuestProgressRepository {
    suspend fun acceptQuest(questId: String, attemptId: String): Result<Unit>

    suspend fun updateProgress(
        attemptId: String,
        currentStep: Int,
        completedSteps: Set<Int>,
        completed: Boolean,
    ): Result<Unit>

    suspend fun saveProgress(
        attemptId: String,
        currentStep: Int,
        completedSteps: Set<Int>,
        reason: String?,
    ): Result<Unit>

    suspend fun abandonQuest(
        attemptId: String,
        currentStep: Int,
        completedSteps: Set<Int>,
        reason: String?,
    ): Result<Unit>

    suspend fun rateQuest(
        attemptId: String,
        stars: Int,
        tags: Set<String>,
    ): Result<Unit>

    /**
     * Eventual connectivity: replays an operation saved while offline. The default maps it onto
     * the calls above; the Supabase implementation also keeps the original timestamps and treats
     * a duplicated acceptance (lost acknowledgement) as already applied.
     */
    suspend fun apply(op: PendingProgressOp): Result<Unit> = when (op.type) {
        PendingProgressOp.ACCEPT -> acceptQuest(op.questId, op.attemptId)
        PendingProgressOp.PROGRESS -> updateProgress(op.attemptId, op.currentStep, op.completedSteps, op.completed)
        PendingProgressOp.SAVE -> saveProgress(op.attemptId, op.currentStep, op.completedSteps, op.reason)
        PendingProgressOp.ABANDON -> abandonQuest(op.attemptId, op.currentStep, op.completedSteps, op.reason)
        PendingProgressOp.RATE -> rateQuest(op.attemptId, op.stars ?: 0, op.tags)
        else -> Result.failure(IllegalArgumentException("Unknown quest progress operation: ${op.type}"))
    }
}

@Serializable
private data class ExistingAttempt(val id: String)

@Serializable
private data class UserQuestInsertDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("quest_id")
    val questId: String,
    val status: String = "accepted",
    @SerialName("current_step")
    val currentStep: Int = 0,
    @SerialName("completed_steps")
    val completedSteps: List<Int> = emptyList(),
)

class SupabaseQuestProgressRepository(
    private val client: SupabaseClient,
) : QuestProgressRepository {

    override suspend fun acceptQuest(
        questId: String,
        attemptId: String,
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()

            client.from("user_quests").insert(
                UserQuestInsertDto(
                    id = attemptId,
                    userId = userId,
                    questId = questId,
                )
            )
        }
    }

    override suspend fun updateProgress(
        attemptId: String,
        currentStep: Int,
        completedSteps: Set<Int>,
        completed: Boolean,
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            client.from("user_quests").update({
                set("status", if (completed) "completed" else "in_progress")
                set("current_step", currentStep)
                set("completed_steps", completedSteps.sorted())
            }) {
                filter {
                    eq("id", attemptId)
                }
            }
        }
    }

    override suspend fun saveProgress(
        attemptId: String,
        currentStep: Int,
        completedSteps: Set<Int>,
        reason: String?,
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            client.from("user_quests").update({
                set("status", "in_progress")
                set("current_step", currentStep)
                set("completed_steps", completedSteps.sorted())
            }) {
                filter {
                    eq("id", attemptId)
                }
            }
        }
    }

    override suspend fun abandonQuest(
        attemptId: String,
        currentStep: Int,
        completedSteps: Set<Int>,
        reason: String?,
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            client.from("user_quests").update({
                set("status", "abandoned")
                set("current_step", currentStep)
                set("completed_steps", completedSteps.sorted())
                set("abandon_reason", reason)
            }) {
                filter {
                    eq("id", attemptId)
                }
            }
        }
    }

    override suspend fun rateQuest(
        attemptId: String,
        stars: Int,
        tags: Set<String>,
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            client.from("user_quests").update({
                set("rating", stars)
                set("feedback_tags", tags.sorted())
            }) {
                filter {
                    eq("id", attemptId)
                }
            }
        }
    }

    override suspend fun apply(op: PendingProgressOp): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            check(userId == op.userId) { "Sign in to the account that made this change." }
            when (op.type) {
                PendingProgressOp.ACCEPT -> {
                    try {
                        client.from("user_quests").insert(buildJsonObject {
                            put("id", op.attemptId)
                            put("user_id", userId)
                            put("quest_id", op.questId)
                            put("status", "accepted")
                            put("accepted_at", op.occurredAt)
                        })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (insertError: Exception) {
                        // A retry after a lost acknowledgement finds the row already created.
                        if (!attemptExists(op.attemptId)) throw insertError
                    }
                }
                PendingProgressOp.PROGRESS, PendingProgressOp.SAVE, PendingProgressOp.ABANDON -> {
                    val status = when {
                        op.type == PendingProgressOp.ABANDON -> "abandoned"
                        op.completed -> "completed"
                        else -> "in_progress"
                    }
                    client.from("user_quests").update({
                        set("status", status)
                        set("current_step", op.currentStep)
                        set("completed_steps", op.completedSteps.sorted())
                        // Postgres only fills these when null, so the original time is kept.
                        if (status == "completed") set("completed_at", op.occurredAt)
                        if (status == "abandoned") {
                            set("abandon_reason", op.reason)
                            set("abandoned_at", op.occurredAt)
                        }
                    }) {
                        filter { eq("id", op.attemptId) }
                    }
                }
                PendingProgressOp.RATE -> client.from("user_quests").update({
                    set("rating", op.stars)
                    set("feedback_tags", op.tags.sorted())
                }) {
                    filter { eq("id", op.attemptId) }
                }
                else -> error("Unknown quest progress operation: ${op.type}")
            }
            Unit
        }
    }

    private suspend fun attemptExists(attemptId: String): Boolean =
        client.from("user_quests").select(columns = Columns.list("id")) {
            filter { eq("id", attemptId) }
        }.decodeList<ExistingAttempt>().isNotEmpty()

    private fun requireUserId(): String =
        client.auth.currentUserOrNull()?.id
            ?: error("No authenticated Supabase user is available.")
}
