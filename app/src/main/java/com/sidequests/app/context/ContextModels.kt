package com.sidequests.app.context

import com.sidequests.app.model.LocationMode
import java.time.Instant


data class LocationData(
    val latitude: Double,
    val longitude: Double,
)

enum class WeatherCondition {
    CLEAR, CLOUDY, RAIN, SNOW, STORM, UNKNOWN
}

data class WeatherData(
    val temperatureCelsius: Double,
    val condition: WeatherCondition,
)

enum class TimeOfDay { MORNING, AFTERNOON, EVENING, NIGHT }

data class UserContext(
    val location: LocationData? = null,
    val weather: WeatherData? = null,
    val timeOfDay: TimeOfDay,
    val timestamp: Instant = Instant.now(),
)

data class ContextUiState(
    val loading: Boolean = true,
    val context: UserContext = UserContext(timeOfDay = timeOfDayFromHour(java.time.ZonedDateTime.now().hour)),
    val error: String? = null,
    val locationMode: LocationMode = LocationMode.Anywhere,
    /** Last known human-readable address (Nominatim). Kept when a new lookup fails. */
    val address: String? = null,
    val addressError: String? = null,
    /** True when a refresh was skipped to save battery and the last context is reused. */
    val batterySaverActive: Boolean = false,
)

fun timeOfDayFromHour(hour: Int): TimeOfDay = when (hour) {
    in 6..11 -> TimeOfDay.MORNING
    in 12..17 -> TimeOfDay.AFTERNOON
    in 18..21 -> TimeOfDay.EVENING
    else -> TimeOfDay.NIGHT
}

fun TimeOfDay.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
fun WeatherCondition.label(): String = when (this) {
    WeatherCondition.CLEAR -> "Clear"
    WeatherCondition.CLOUDY -> "Cloudy"
    WeatherCondition.RAIN -> "Rain"
    WeatherCondition.SNOW -> "Snow"
    WeatherCondition.STORM -> "Storm"
    WeatherCondition.UNKNOWN -> "Unknown"
}
