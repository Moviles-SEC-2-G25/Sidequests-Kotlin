package com.sidequests.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryRetentionTest {

    @Test
    fun `nothing is deleted while the queue is below the cap`() {
        assertNull(TelemetryRetention.deleteUpToId(newId = 1, maxRows = 500))
        assertNull(TelemetryRetention.deleteUpToId(newId = 500, maxRows = 500))
    }

    @Test
    fun `the oldest row goes once the cap is passed`() {
        assertEquals(1L, TelemetryRetention.deleteUpToId(newId = 501, maxRows = 500))
        assertEquals(500L, TelemetryRetention.deleteUpToId(newId = 1_000, maxRows = 500))
    }

    @Test
    fun `at most maxRows ids survive`() {
        val newId = 1_234L
        val maxRows = 500
        val deleteUpTo = TelemetryRetention.deleteUpToId(newId, maxRows)!!
        assertEquals(maxRows.toLong(), newId - deleteUpTo)
    }

    @Test
    fun `a failed insert never deletes`() {
        assertNull(TelemetryRetention.deleteUpToId(newId = -1, maxRows = 500))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cap must be positive`() {
        TelemetryRetention.deleteUpToId(newId = 10, maxRows = 0)
    }
}
