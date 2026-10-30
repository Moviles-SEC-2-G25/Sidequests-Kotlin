package com.sidequests.app.context

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Turns coordinates into a short human-readable address.
 */
interface AddressProvider {
    suspend fun addressFor(location: LocationData): String?
}

/**
 * External service feature (Sprint 2, Carlos): OpenStreetMap Nominatim reverse geocoding.
 *
 * Nominatim's usage policy asks for an identifying User-Agent and at most one request per
 * second, so requests are serialized with a [Mutex] and spaced by [MIN_INTERVAL_MILLIS].
 * Results are kept in a small in-memory LRU keyed by coordinates rounded to ~100 m, so the
 * same area is not requested again while the app is open. Any network or parsing failure
 * returns null and the caller keeps the last known address.
 */
class NominatimAddressProvider(
    private val userAgent: String = "Sidequests-Kotlin/1.0 (ISIS-3510 Uniandes student project)",
    private val cacheSize: Int = 32,
) : AddressProvider {

    private val cache = object : LinkedHashMap<String, String>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > cacheSize
    }
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    override suspend fun addressFor(location: LocationData): String? = mutex.withLock {
        val key = cacheKey(location)
        val cached = cache[key]
        if (cached != null) return@withLock cached

        val waitMillis = MIN_INTERVAL_MILLIS - (System.currentTimeMillis() - lastRequestAt)
        if (waitMillis > 0) delay(waitMillis)

        val address = withContext(Dispatchers.IO) { fetch(location) }
        lastRequestAt = System.currentTimeMillis()
        if (address != null) cache[key] = address
        address
    }

    private fun cacheKey(location: LocationData): String =
        String.format(Locale.US, "%.3f,%.3f", location.latitude, location.longitude)

    private fun fetch(location: LocationData): String? {
        val url = URL(
            "https://nominatim.openstreetmap.org/reverse" +
                "?format=jsonv2&zoom=17&addressdetails=1" +
                "&lat=${location.latitude}&lon=${location.longitude}"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 7_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", "es,en")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            formatAddress(JSONObject(body))
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun formatAddress(json: JSONObject): String? {
        val address = json.optJSONObject("address")
        fun first(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key -> address?.optString(key)?.takeIf { it.isNotBlank() } }

        val parts = listOfNotNull(
            first("road", "pedestrian", "footway"),
            first("neighbourhood", "suburb", "quarter", "city_district"),
            first("city", "town", "village"),
        ).distinct()
        if (parts.isNotEmpty()) return parts.joinToString(", ")

        return json.optString("display_name")
            .takeIf { it.isNotBlank() }
            ?.split(",")
            ?.take(3)
            ?.joinToString(",") { it.trim() }
    }

    companion object {
        const val MIN_INTERVAL_MILLIS = 1_100L
    }
}
