package com.sidequests.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryStoreBenchmarkTest {

    @Test
    fun `summary converts nanoseconds to microseconds`() {
        val summary = TelemetryStoreBenchmark.summarize(listOf(1_000_000L, 3_000_000L, 2_000_000L))
        assertEquals(3, summary.samples)
        assertEquals(6_000L, summary.totalMicros)
        assertEquals(2_000L, summary.avgMicros)
        assertEquals(2_000L, summary.medianMicros)
        assertEquals(3_000L, summary.maxMicros)
    }

    @Test
    fun `median of an even number of samples is the mean of the two middle ones`() {
        val summary = TelemetryStoreBenchmark.summarize(listOf(4_000L, 1_000L, 3_000L, 2_000L))
        assertEquals(2L, summary.medianMicros) // (2000 + 3000) / 2 ns = 2.5 µs -> 2 µs
    }

    @Test
    fun `p95 ignores the slowest five percent`() {
        // 1..100 µs: p95 is the 95th value, not the max.
        val samples = (1L..100L).map { it * 1_000 }.shuffled()
        val summary = TelemetryStoreBenchmark.summarize(samples)
        assertEquals(95L, summary.p95Micros)
        assertEquals(100L, summary.maxMicros)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty input is rejected`() {
        TelemetryStoreBenchmark.summarize(emptyList())
    }
}
