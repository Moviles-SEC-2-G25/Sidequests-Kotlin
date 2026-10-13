package com.sidequests.app.context

/** Controlled inputs for debug-only Android launch scenarios, never the production providers. */
object AlternativeDemoContext {
    fun parse(weather: String?, hour: Int?): UserContext? {
        if (hour == null || hour !in 0..23) return null
        val condition = WeatherCondition.entries.firstOrNull { it.name.equals(weather, ignoreCase = true) }
            ?: return null
        return UserContext(
            weather = WeatherData(20.0, condition),
            timeOfDay = timeOfDayFromHour(hour),
        )
    }
}
