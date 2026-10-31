package com.sidequests.app.context

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoWeatherProviderTest {
    private val bogota = LocationData(4.65, -74.05)
    private val nearby = LocationData(4.651, -74.049)
    private val other = LocationData(4.70, -74.10)
    private val clear = WeatherData(20.0, WeatherCondition.CLEAR)

    @Test
    fun reusesFreshResponseForSameRoundedLocation() = runBlocking {
        var calls = 0
        val provider = OpenMeteoWeatherProvider(fetchWeather = {
            calls++
            clear
        })

        assertEquals(clear, provider.getWeather(bogota))
        assertEquals(clear, provider.getWeather(nearby))
        assertEquals(1, calls)
    }

    @Test
    fun refreshesExpiredEntry() = runBlocking {
        var now = 1000L
        var calls = 0
        val provider = OpenMeteoWeatherProvider(
            ttlMillis = 100,
            nowMillis = { now },
            fetchWeather = {
                calls++
                WeatherData(20.0 + calls, WeatherCondition.CLEAR)
            },
        )

        assertEquals(21.0, provider.getWeather(bogota)!!.temperatureCelsius, 0.0)
        now += 100
        assertEquals(22.0, provider.getWeather(bogota)!!.temperatureCelsius, 0.0)
        assertEquals(2, calls)
    }

    @Test
    fun differentLocationUsesDifferentCacheEntry() = runBlocking {
        var calls = 0
        val provider = OpenMeteoWeatherProvider(fetchWeather = {
            calls++
            clear
        })

        provider.getWeather(bogota)
        provider.getWeather(other)

        assertEquals(2, calls)
    }

    @Test
    fun failedRefreshReturnsPreviousValueMarkedStale() = runBlocking {
        var now = 0L
        var calls = 0
        val provider = OpenMeteoWeatherProvider(
            ttlMillis = 10,
            nowMillis = { now },
            fetchWeather = {
                calls++
                if (calls == 1) clear else null
            },
        )

        assertEquals(clear, provider.getWeather(bogota))
        now = 10
        val fallback = provider.getWeather(bogota)

        assertEquals(clear, fallback?.copy(isStale = false))
        assertTrue(fallback!!.isStale)
        assertEquals(2, calls)
    }

    @Test
    fun failedFirstRequestReturnsNull() = runBlocking {
        val provider = OpenMeteoWeatherProvider(fetchWeather = { null })

        assertNull(provider.getWeather(bogota))
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedLocationWhenBoundIsReached() = runBlocking {
        var calls = 0
        val provider = OpenMeteoWeatherProvider(
            maxEntries = 2,
            fetchWeather = {
                calls++
                clear
            },
        )

        provider.getWeather(bogota)
        provider.getWeather(other)
        provider.getWeather(bogota)
        provider.getWeather(LocationData(4.80, -74.20))
        provider.getWeather(other)

        assertEquals(4, calls)
    }
}
