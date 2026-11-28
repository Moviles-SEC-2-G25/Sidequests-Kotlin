package com.sidequests.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sidequests.app.context.LocationData
import com.sidequests.app.context.UserContext
import com.sidequests.app.context.WeatherData
import com.sidequests.app.context.label
import com.sidequests.app.domain.hasDestination
import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.Quest
import kotlin.math.roundToInt

@Composable
fun QuestWeatherCard(quest: Quest, context: UserContext, online: Boolean, viewModel: AppViewModel) {
    if (quest.location == LocationMode.Gps && quest.hasDestination()) {
        val target = LocationData(requireNotNull(quest.latitude), requireNotNull(quest.longitude))
        key(target) {
            val result by produceState<DestinationWeather?>(null, target, online) {
                value = DestinationWeather(viewModel.destinationWeather(target, online))
            }
            result?.let { WeatherReadingCard("Weather at the location", it.weather, online) }
        }
    } else if (context.location != null) {
        WeatherReadingCard("Weather near you", context.weather, online)
    }
}

private data class DestinationWeather(val weather: WeatherData?)

@Composable
private fun WeatherReadingCard(title: String, weather: WeatherData?, online: Boolean) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (weather == null) {
                Text(if (online) "Weather is unavailable right now." else "Weather is unavailable offline.")
                return@Column
            }
            Text("${weather.temperatureCelsius.roundToInt()}°C · ${weather.condition.label()}")
            val ageMinutes = weather.fetchedAtMillis?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) / 60_000 }
            if (weather.rainProbabilityPercent != null && ageMinutes != null && ageMinutes < 180) {
                Text("Rain chance in the forecast: ${weather.rainProbabilityPercent}%", style = MaterialTheme.typography.bodySmall)
            }
            if (weather.isStale || !online) {
                Text(ageMinutes?.let { "Last update: $it min ago" } ?: "Showing last known weather",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
