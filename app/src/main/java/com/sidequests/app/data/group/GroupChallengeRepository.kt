package com.sidequests.app.data.group

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class GroupChallenge(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("quest_id") val questId: String,
    val title: String,
    val participants: Int,
    @SerialName("my_progress") val myProgress: Int? = null,
    @SerialName("total_steps") val totalSteps: Int,
    @SerialName("completed_participants") val completedParticipants: Int,
)

@Serializable
data class GroupActionResult(
    val ok: Boolean,
    val message: String,
    @SerialName("challenge_id") val challengeId: String? = null,
)

data class GroupAction(
    val operation: String,
    val requestId: String,
    val challengeId: String? = null,
    val questId: String? = null,
    val progress: Int? = null,
)

interface GroupChallengeGateway {
    suspend fun list(mine: Boolean): List<GroupChallenge>
    suspend fun act(action: GroupAction): GroupActionResult
}

class SupabaseGroupChallengeGateway(private val client: SupabaseClient) : GroupChallengeGateway {
    override suspend fun list(mine: Boolean): List<GroupChallenge> = withContext(Dispatchers.IO) {
        client.postgrest.rpc("group_challenge_list", buildJsonObject {
            put("p_mine", mine)
        }).decodeList<GroupChallenge>()
    }

    override suspend fun act(action: GroupAction): GroupActionResult = withContext(Dispatchers.IO) {
        client.postgrest.rpc("group_challenge_act", buildJsonObject {
            put("p_action", action.operation)
            put("p_request_id", action.requestId)
            action.challengeId?.let { put("p_challenge_id", it) }
            action.questId?.let { put("p_quest_id", it) }
            action.progress?.let { put("p_progress", it) }
        }).decodeAs<GroupActionResult>()
    }
}

data class GroupLists(
    val mine: Result<List<GroupChallenge>>,
    val available: Result<List<GroupChallenge>>,
)

/** Independent network reads overlap; cancellation propagates, a failed read does not erase its sibling. */
class GroupChallengeLoader(
    private val gateway: GroupChallengeGateway,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private suspend fun fetch(mine: Boolean): Result<List<GroupChallenge>> =
        try { Result.success(gateway.list(mine)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }

    suspend fun load(): GroupLists = coroutineScope {
        val mine = async(io) { fetch(true) }
        val available = async(io) { fetch(false) }
        GroupLists(mine.await(), available.await())
    }
}
