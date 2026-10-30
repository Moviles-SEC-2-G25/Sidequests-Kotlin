package com.sidequests.app.context

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ContextManagerTest {
    private val fixedTime = ZonedDateTime.of(2026, 10, 30, 14, 30, 0, 0, ZoneId.of("UTC"))
    private val location = LocationData(4.65, -74.05)

    @Test
    fun readsTimeWhileLocationIsStillPendingAndFetchesWeatherAfterLocation() = runBlocking {
        val locationResult = CompletableDeferred<LocationData?>()
        val timeWasRead = CompletableDeferred<Unit>()
        val weatherWasRequested = CompletableDeferred<LocationData>()
        val manager = ContextManager(
            locationProvider = object : LocationProvider {
                override suspend fun getCurrentLocation(): LocationData? = locationResult.await()
            },
            weatherProvider = object : WeatherProvider {
                override suspend fun getWeather(location: LocationData): WeatherData {
                    weatherWasRequested.complete(location)
                    return WeatherData(18.0, WeatherCondition.CLOUDY)
                }
            },
            timeProvider = object : TimeProvider {
                override fun now(): ZonedDateTime {
                    timeWasRead.complete(Unit)
                    return fixedTime
                }
            },
        )

        val result = async { manager.readContext() }

        withTimeout(2_000) { timeWasRead.await() }
        assertTrue(!weatherWasRequested.isCompleted)

        locationResult.complete(location)
        val context = result.await()

        assertEquals(location, context.location)
        assertEquals(WeatherData(18.0, WeatherCondition.CLOUDY), context.weather)
        assertEquals(TimeOfDay.AFTERNOON, context.timeOfDay)
        assertEquals(fixedTime.toInstant(), context.timestamp)
        assertEquals(location, weatherWasRequested.await())
    }

    @Test
    fun skipsWeatherWhenLocationIsUnavailable() = runBlocking {
        var weatherRequested = false
        val manager = ContextManager(
            locationProvider = object : LocationProvider {
                override suspend fun getCurrentLocation(): LocationData? = null
            },
            weatherProvider = object : WeatherProvider {
                override suspend fun getWeather(location: LocationData): WeatherData? {
                    weatherRequested = true
                    return null
                }
            },
            timeProvider = object : TimeProvider {
                override fun now(): ZonedDateTime = fixedTime
            },
        )

        val context = manager.readContext()

        assertNull(context.location)
        assertNull(context.weather)
        assertEquals(TimeOfDay.AFTERNOON, context.timeOfDay)
        assertTrue(!weatherRequested)
    }

    @Test
    fun preservesContextWhenWeatherIsUnavailable() = runBlocking {
        val manager = ContextManager(
            locationProvider = object : LocationProvider {
                override suspend fun getCurrentLocation(): LocationData = location
            },
            weatherProvider = object : WeatherProvider {
                override suspend fun getWeather(location: LocationData): WeatherData? = null
            },
            timeProvider = object : TimeProvider {
                override fun now(): ZonedDateTime = fixedTime
            },
        )

        val context = manager.readContext()

        assertEquals(location, context.location)
        assertNull(context.weather)
        assertEquals(TimeOfDay.AFTERNOON, context.timeOfDay)
    }
}
