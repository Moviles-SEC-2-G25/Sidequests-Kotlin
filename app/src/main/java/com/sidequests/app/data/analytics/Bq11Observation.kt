package com.sidequests.app.data.analytics

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.hypot

private val bogota = ZoneId.of("America/Bogota")
private const val MaxGapMillis = 35_000L
private const val ThresholdMillis = 20 * 60 * 1000L

internal data class Bq11Attempt(val userId: String, val attemptId: String)
internal data class Bq11Dwell(val blockId: String, val weekend: LocalDate, val millis: Long)

internal fun weekendStart(time: Instant): LocalDate? {
    val date = time.atZone(bogota).toLocalDate()
    return when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date
        DayOfWeek.SUNDAY -> date.minusDays(1)
        else -> null
    }
}

/** Main-thread state. Samples retain only a block ID, never the location coordinates. */
internal class Bq11Observation {
    private data class Point(val block: String, val weekend: LocalDate, val elapsed: Long)
    private val totals = mutableMapOf<Pair<String, LocalDate>, Long>()
    private var last: Point? = null
    var attempt: Bq11Attempt? = null
        private set

    fun select(attempt: Bq11Attempt?) {
        if (this.attempt == attempt) return
        this.attempt = attempt
        last = null
        totals.clear()
    }

    fun pause(attempt: Bq11Attempt) {
        if (this.attempt == attempt) last = null
    }

    fun observe(attempt: Bq11Attempt, block: String?, time: Instant, elapsed: Long) {
        if (this.attempt != attempt) return
        val weekend = weekendStart(time)
        val previous = last
        last = if (block == null || weekend == null) null else Point(block, weekend, elapsed)
        if (block == null || weekend == null || previous == null) return
        val delta = elapsed - previous.elapsed
        if (previous.block == block && previous.weekend == weekend && delta in 1..MaxGapMillis) {
            val key = block to weekend
            totals[key] = totals.getOrDefault(key, 0) + delta
        }
    }

    fun finish(attempt: Bq11Attempt, completedAt: Instant): List<Bq11Dwell> {
        if (this.attempt != attempt) return emptyList()
        val weekend = weekendStart(completedAt)
        val result = totals.mapNotNull { (key, millis) ->
            val (block, observedWeekend) = key
            Bq11Dwell(block, observedWeekend, millis).takeIf {
                observedWeekend == weekend && millis > ThresholdMillis
            }
        }
        select(null)
        return result
    }
}

/** Two mapped commercial pilot areas, intentionally limited to their published market sites. */
internal object Bq11Blocks {
    private data class Point(val lat: Double, val lon: Double)
    private data class Block(val id: String, val boundary: List<Point>)

    private val blocks = listOf(
        // OSM way 88958128, the Carrera 6A segment north of Calle 119 at the flea market.
        Block("usaquen-cra6a-c119", listOf(
            Point(4.69552, -74.03123), Point(4.69602, -74.03101),
            Point(4.69593, -74.03082), Point(4.69546, -74.03102),
        )),
        // OSM way 713156703, simplified outline of Plaza de Mercado de Paloquemao.
        Block("paloquemao-market", listOf(
            Point(4.6150192, -74.0841617), Point(4.6157865, -74.0835081),
            Point(4.6161066, -74.0837123), Point(4.6160390, -74.0834997),
            Point(4.6164444, -74.0834288), Point(4.6165365, -74.0836106),
            Point(4.6169511, -74.0837893), Point(4.6175530, -74.0843562),
            Point(4.6166471, -74.0847598), Point(4.6160083, -74.0846366),
            Point(4.6153822, -74.0844676),
        )),
    )

    fun match(lat: Double, lon: Double, accuracyMeters: Float): String? {
        if (!lat.isFinite() || !lon.isFinite() || accuracyMeters !in 1f..25f) return null
        return blocks.singleOrNull { includes(it.boundary, lat, lon, accuracyMeters.toDouble()) }?.id
    }

    private fun includes(boundary: List<Point>, lat: Double, lon: Double, accuracy: Double): Boolean {
        // Project vertices into local metres from this fix. Use the reported accuracy as an
        // inward buffer so a fix whose uncertainty crosses the edge cannot count.
        val lonMetres = 111_320 * cos(Math.toRadians(lat))
        val points = boundary.map { (it.lon - lon) * lonMetres to (it.lat - lat) * 110_540 }
        var inside = false
        for (i in points.indices) {
            val (x1, y1) = points[i]
            val (x2, y2) = points[(i + 1) % points.size]
            if ((y1 > 0) != (y2 > 0) && x1 - y1 * (x2 - x1) / (y2 - y1) > 0) inside = !inside
            val dx = x2 - x1
            val dy = y2 - y1
            val t = (-(x1 * dx + y1 * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            if (hypot(x1 + t * dx, y1 + t * dy) < accuracy) return false
        }
        return inside
    }
}
