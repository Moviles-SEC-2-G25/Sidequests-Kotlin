package com.sidequests.app.data

import com.sidequests.app.data.remote.QuestDto
import com.sidequests.app.data.remote.QuestStepDto
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * A saved copy of the public quest catalogue only.
 *
 * Do not store accounts, tokens, progress, or photo evidence here.
 */
@Serializable
data class QuestCatalogueSnapshot(
    val quests: List<QuestDto>,
    val steps: List<QuestStepDto>,
)

/**
 * Stores the most recently downloaded public catalogue in the app's private files.
 * Writes to a temporary file first so a failed update preserves the previous snapshot.
 *
 * Call read/write on Dispatchers.IO.
 */
class LocalQuestCatalogueStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(): QuestCatalogueSnapshot? {
        if (!file.isFile) return null
        return runCatching {
            val decoded = json.decodeFromString<QuestCatalogueSnapshot>(file.readText(Charsets.UTF_8))
            decoded.takeIf { it.quests.isNotEmpty() }
        }.getOrNull()
    }

    fun save(snapshot: QuestCatalogueSnapshot) {
        require(snapshot.quests.isNotEmpty()) { "Cannot replace the catalogue with an empty snapshot." }
        val parent = requireNotNull(file.parentFile) { "The catalogue needs a private parent directory." }
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create private catalogue directory." }

        val temporaryFile = File(parent, "${file.name}.tmp")
        try {
            val bytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
            FileOutputStream(temporaryFile).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporaryFile.delete()
        }
    }
}
