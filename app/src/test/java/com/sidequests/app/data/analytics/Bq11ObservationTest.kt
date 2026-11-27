package com.sidequests.app.data.analytics

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Bq11ObservationTest {
    private val owner = Bq11Attempt("owner", "attempt-1")
    private val start = Instant.parse("2026-11-29T01:00:00Z") // Saturday in Bogotá.
    private val block = "paloquemao-market"

    @Test
    fun thresholdIsStrictAndWeekendsUseBogotaTime() {
        fun observe(intervals: Int): List<Bq11Dwell> {
            val observation = Bq11Observation()
            observation.select(owner)
            for (i in 0..intervals) {
                observation.observe(owner, block, start.plusSeconds(i * 15L), i * 15_000L)
            }
            return observation.finish(owner, start.plusSeconds(1_300))
        }
        assertTrue(observe(80).isEmpty())
        assertEquals(listOf(Bq11Dwell(block, LocalDate.parse("2026-11-28"), 1_215_000)), observe(81))
        assertNull(weekendStart(Instant.parse("2026-11-28T04:59:59Z")))
        assertNull(weekendStart(Instant.parse("2026-11-30T05:00:00Z")))
    }

    @Test
    fun missingSamplesAndLongGapsDoNotAccumulateAcrossPauses() {
        val observation = Bq11Observation()
        observation.select(owner)
        for (i in 0..40) {
            observation.observe(owner, block, start.plusSeconds(i * 15L), i * 15_000L)
        }
        observation.pause(owner)
        for (i in 80..120) {
            observation.observe(owner, block, start.plusSeconds(i * 15L), i * 15_000L)
        }
        observation.observe(owner, block, start.plusSeconds(3600), 3_600_000)
        assertTrue(observation.finish(owner, start.plusSeconds(3700)).isEmpty())
    }

    @Test
    fun anotherAttemptCannotReleaseOrInheritAnObservation() {
        val observation = Bq11Observation()
        observation.select(owner)
        for (i in 0..81) {
            observation.observe(owner, block, start.plusSeconds(i * 15L), i * 15_000L)
        }
        val other = Bq11Attempt("other", owner.attemptId)
        assertTrue(observation.finish(other, start.plusSeconds(1300)).isEmpty())
        observation.select(null)
        observation.select(other)
        assertTrue(observation.finish(other, start.plusSeconds(1300)).isEmpty())
    }

    @Test
    fun mappingRejectsUncertainAndUnmappedFixes() {
        assertEquals(block, Bq11Blocks.match(4.616, -74.08426, 5f))
        assertEquals("usaquen-cra6a-c119", Bq11Blocks.match(4.69577, -74.031, 5f))
        assertNull(Bq11Blocks.match(4.616, -74.08426, 80f))
        assertNull(Bq11Blocks.match(4.601, -74.065, 5f))
    }
}
