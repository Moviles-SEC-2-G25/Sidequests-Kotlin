package com.sidequests.app.context

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface WeatherProvider {
    suspend fun getWeather(location: LocationData): WeatherData?
}

class OpenMeteoWeatherProvider(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val fetchWeather: suspend (LocationData) -> WeatherData? = ::fetchOpenMeteoWeather,
) : WeatherProvider {
    private data class LocationKey(val latitude: Int, val longitude: Int)
    private data class CacheEntry(val weather: WeatherData, val fetchedAtMillis: Long)

    private val cache = LinkedHashMap<LocationKey, CacheEntry>(16, 0.75f, true)
    private val cacheMutex = Mutex()

    init {
        require(ttlMillis > 0)
        require(maxEntries > 0)
    }

    override suspend fun getWeather(location: LocationData): WeatherData? =
        withContext(Dispatchers.IO) {
            cacheMutex.withLock {
                val key = LocationKey(
                    latitude = (location.latitude * KEY_PRECISION).roundToInt(),
                    longitude = (location.longitude * KEY_PRECISION).roundToInt(),
                )
                val previous = cache[key]
                val now = nowMillis()
                if (previous != null && now - previous.fetchedAtMillis < ttlMillis) {
                    return@withLock previous.weather.copy(isStale = false)
                }

                val fresh = fetchWeather(location)
                if (fresh != null) {
                    cache[key] = CacheEntry(fresh.copy(isStale = false), nowMillis())
                    while (cache.size > maxEntries) {
                        cache.remove(cache.entries.iterator().next().key)
                    }
                    fresh.copy(isStale = false)
                } else {
                    previous?.weather?.copy(isStale = true)
                }
            }
        }

    companion object {
        const val DEFAULT_TTL_MILLIS = 600000L
        const val DEFAULT_MAX_ENTRIES = 3
        private const val KEY_PRECISION = 100.0
    }
}

private suspend fun fetchOpenMeteoWeather(location: LocationData): WeatherData? {
    val url = URL(
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${location.latitude}" +
            "&longitude=${location.longitude}" +
            "&current=temperature_2m,weather_code" +
            "&temperature_unit=celsius"
    )
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 7000
        readTimeout = 7000
    }
    return try {
        if (connection.responseCode !in 200..299) return null
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val current = JSONObject(body).getJSONObject("current")
        val temperature = current.optDouble("temperature_2m", Double.NaN)
        if (!temperature.isFinite()) return null
        WeatherData(
            temperatureCelsius = temperature,
            condition = weatherCodeToCondition(current.optInt("weather_code", -1)),
        )
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun weatherCodeToCondition(code: Int): WeatherCondition = when (code) {
    0, 1 -> WeatherCondition.CLEAR
    2, 3, 45, 48 -> WeatherCondition.CLOUDY
    in 51..67, in 80..82 -> WeatherCondition.RAIN
    in 71..77, 85, 86 -> WeatherCondition.SNOW
    in 95..99 -> WeatherCondition.STORM
    else -> WeatherCondition.UNKNOWN
}

class MockWeatherProvider : WeatherProvider {
    override suspend fun getWeather(location: LocationData): WeatherData =
        WeatherData(20.0, WeatherCondition.CLEAR)
}
