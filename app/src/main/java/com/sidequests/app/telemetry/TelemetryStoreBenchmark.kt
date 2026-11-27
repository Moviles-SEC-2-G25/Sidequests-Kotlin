package com.sidequests.app.telemetry

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

/** Timing summary of one benchmark run, in microseconds. */
data class BenchmarkSummary(
    val samples: Int,
    val totalMicros: Long,
    val avgMicros: Long,
    val medianMicros: Long,
    val p95Micros: Long,
    val maxMicros: Long,
) {
    fun describe(): String = String.format(
        Locale.US,
        "%d inserts · total %.1f ms · avg %d µs · median %d µs · p95 %d µs · max %d µs",
        samples, totalMicros / 1000.0, avgMicros, medianMicros, p95Micros, maxMicros,
    )
}

/**
 * Sprint 4 micro-optimization measurement (debug builds only).
 *
 * Measures [TelemetryStore.insert] in the worst realistic case: the queue is already full
 * (the phone was offline for a long time), so every insert also has to trim the table.
 * It uses its own database file, so the real telemetry queue is never touched.
 */
object TelemetryStoreBenchmark {
    private const val TAG = "TelemetryBench"
    private const val DATABASE_NAME = "sidequests_telemetry_benchmark.db"
    const val MEASURED_INSERTS = 200

    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    suspend fun run(context: Context): BenchmarkSummary = withContext(Dispatchers.IO) {
        _running.value = true
        val app = context.applicationContext
        app.deleteDatabase(DATABASE_NAME)
        val store = TelemetryStore(app, DATABASE_NAME)
        try {
            val metadata = """{"bq":"BENCH","module":"benchmark","fatal":false}"""
            val now = System.currentTimeMillis()
            // Fill the queue up to its cap so every measured insert pays for the trim.
            repeat(TelemetryStore.MAX_ROWS) { store.insert("benchmark_fill", "bench", metadata, now) }

            val durations = LongArray(MEASURED_INSERTS)
            repeat(MEASURED_INSERTS) { i ->
                val start = SystemClock.elapsedRealtimeNanos()
                store.insert("benchmark_event", "bench", metadata, now + i)
                durations[i] = SystemClock.elapsedRealtimeNanos() - start
            }
            val summary = summarize(durations.toList())
            Log.i(TAG, "rows after run=${store.count()} · ${summary.describe()}")
            _lastResult.value = summary.describe()
            summary
        } finally {
            store.close()
            app.deleteDatabase(DATABASE_NAME)
            _running.value = false
        }
    }

    /** Pure statistics over durations in nanoseconds; results in microseconds. */
    fun summarize(durationsNanos: List<Long>): BenchmarkSummary {
        require(durationsNanos.isNotEmpty()) { "No samples" }
        val sorted = durationsNanos.sorted()
        val total = sorted.sum()
        val p95Index = (Math.ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
        return BenchmarkSummary(
            samples = sorted.size,
            totalMicros = total / 1_000,
            avgMicros = total / sorted.size / 1_000,
            medianMicros = median / 1_000,
            p95Micros = sorted[p95Index] / 1_000,
            maxMicros = sorted.last() / 1_000,
        )
    }
}
