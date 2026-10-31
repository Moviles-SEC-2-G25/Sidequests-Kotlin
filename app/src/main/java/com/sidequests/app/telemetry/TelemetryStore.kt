package com.sidequests.app.telemetry

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PendingTelemetryEvent(
    val id: Long,
    val eventType: String,
    val sessionId: String,
    val metadataJson: String,
    val createdAtMillis: Long,
)

/**
 * Local storage (Sprint 3): SQLite queue of BQ1 telemetry events waiting to be uploaded.
 *
 * Events are written here first, so nothing is lost while offline or when the app crashes; the
 * [TelemetryUploadWorker] uploads and deletes them when there is a connection.
 * One shared instance per process (SQLiteOpenHelper is thread-safe with a single instance).
 */
class TelemetryStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                session_id TEXT NOT NULL,
                metadata TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(eventType: String, sessionId: String, metadataJson: String, createdAtMillis: Long): Long {
        val values = ContentValues().apply {
            put("event_type", eventType)
            put("session_id", sessionId)
            put("metadata", metadataJson)
            put("created_at", createdAtMillis)
        }
        val id = writableDatabase.insert(TABLE, null, values)
        trimTo(MAX_ROWS)
        return id
    }

    fun oldest(limit: Int): List<PendingTelemetryEvent> =
        readableDatabase.query(
            TABLE,
            arrayOf("id", "event_type", "session_id", "metadata", "created_at"),
            null, null, null, null,
            "id ASC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PendingTelemetryEvent(
                            id = cursor.getLong(0),
                            eventType = cursor.getString(1),
                            sessionId = cursor.getString(2),
                            metadataJson = cursor.getString(3),
                            createdAtMillis = cursor.getLong(4),
                        )
                    )
                }
            }
        }

    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        writableDatabase.delete(TABLE, "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
    }

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, TABLE)

    /** Keeps the queue bounded if the device stays offline for a very long time. */
    private fun trimTo(maxRows: Int) {
        writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE id NOT IN (SELECT id FROM $TABLE ORDER BY id DESC LIMIT $maxRows)"
        )
    }

    companion object {
        private const val DATABASE_NAME = "sidequests_telemetry.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE = "pending_events"
        private const val MAX_ROWS = 500

        @Volatile private var instance: TelemetryStore? = null

        fun getInstance(context: Context): TelemetryStore =
            instance ?: synchronized(this) {
                instance ?: TelemetryStore(context.applicationContext).also { instance = it }
            }
    }
}
