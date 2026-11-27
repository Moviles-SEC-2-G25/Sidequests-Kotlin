package com.sidequests.app.telemetry

/**
 * Retention rule of the telemetry queue: keep at most the [maxRows] newest events.
 *
 * Ids are AUTOINCREMENT, so they only grow and are never reused. After inserting [newId], every
 * row with id <= newId - maxRows is older than the newest [maxRows] events and can be deleted
 * with an index lookup on the primary key, without sorting the table.
 */
object TelemetryRetention {
    /** Highest id to delete, or null when there is nothing old enough (or the insert failed). */
    fun deleteUpToId(newId: Long, maxRows: Int): Long? {
        require(maxRows > 0) { "maxRows must be positive" }
        if (newId <= 0) return null
        val threshold = newId - maxRows
        return threshold.takeIf { it > 0 }
    }
}
