package com.sidequests.app.data.history

import com.sidequests.app.domain.HistoryAttempt
import com.sidequests.app.domain.HistoryQuest
import com.sidequests.app.domain.HistorySnapshot
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class HistoryLoad(val snapshot: HistorySnapshot?, val fresh: Boolean)
fun interface HistorySource {
    suspend fun load(userId: String, online: Boolean): HistoryLoad
}
fun interface HistoryRemote {
    suspend fun fetch(userId: String): HistorySnapshot
}

/** Private account-keyed snapshots; a successful empty history is different from no saved data. */
class MissionHistoryStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun file(userId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".json")
    }
    @Synchronized fun read(userId: String): HistorySnapshot? = runCatching {
        val path = file(userId)
        if (!path.isFile) return null
        json.decodeFromString<HistorySnapshot>(path.readText()).takeIf {
            it.userId == userId && it.attempts.all { row -> row.userId == userId }
        }
    }.getOrNull()

    @Synchronized fun save(snapshot: HistorySnapshot) {
        require(snapshot.attempts.all { it.userId == snapshot.userId })
        check(directory.isDirectory || directory.mkdirs())
        val destination = file(snapshot.userId)
        val tmp = File.createTempFile("history-", ".tmp", directory)
        try {
            FileOutputStream(tmp).use { stream ->
                stream.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { tmp.delete() }
    }
}

class MissionHistoryRepository(
    private val store: MissionHistoryStore,
    private val remote: HistoryRemote,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 5_000,
) : HistorySource {
    override suspend fun load(userId: String, online: Boolean): HistoryLoad = withContext(io) {
        val saved = store.read(userId)
        if (!online) return@withContext HistoryLoad(saved, false)
        val fresh = try {
            withTimeoutOrNull(timeoutMillis) {
                remote.fetch(userId).also { result ->
                    check(result.userId == userId && result.attempts.all { it.userId == userId })
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        if (fresh == null) return@withContext HistoryLoad(saved, false)
        // A failed disk write must not hide fresh data; retain the previous valid cache.
        runCatching { store.save(fresh) }
        HistoryLoad(fresh, true)
    }
}

/** Existing RLS-protected tables only; no migrations or service credentials. */
class SupabaseHistoryRemote(private val client: SupabaseClient) : HistoryRemote {
    override suspend fun fetch(userId: String): HistorySnapshot = coroutineScope {
        check(client.auth.currentUserOrNull()?.id == userId)
        val attempts = async {
            val rows = mutableListOf<HistoryAttempt>()
            var offset = 0L
            while (true) {
                check(client.auth.currentUserOrNull()?.id == userId)
                val page = client.from("user_quests").select(
                    columns = Columns.list("id","user_id","quest_id","status","accepted_at",
                        "started_at","completed_at","abandoned_at","rating","abandon_reason"),
                ) {
                    filter { eq("user_id", userId) }
                    order("id", Order.ASCENDING)
                    range(offset..offset + 199)
                }.decodeList<HistoryAttempt>()
                rows.addAll(page)
                if (page.size < 200) break
                offset += page.size
            }
            rows.distinctBy { it.id }
        }
        val catalog = async {
            val rows = mutableListOf<HistoryQuest>()
            var offset = 0L
            while (true) {
                val page = client.from("quests").select(
                    columns = Columns.list("id","title","category","duration_minutes","location_name","emoji"),
                ) {
                    filter { eq("is_active", true) }
                    order("id", Order.ASCENDING)
                    range(offset..offset + 199)
                }.decodeList<HistoryQuest>()
                rows.addAll(page)
                if (page.size < 200) break
                offset += page.size
            }
            rows.distinctBy { it.id }
        }
        val snapshot = HistorySnapshot(userId, attempts.await(), catalog.await())
        check(client.auth.currentUserOrNull()?.id == userId)
        // Kotlin currently has no walked-step store or durable progress-write queue.
        // Leave unavailable step totals and pending IDs empty, never fabricate either.
        snapshot
    }
}
