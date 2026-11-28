package com.sidequests.app.domain

import com.sidequests.app.context.LocationData
import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.Quest
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NearbyQuest(val quest: Quest, val distanceKm: Double?)

fun nearbyQuests(catalog: List<Quest>, location: LocationData?): List<NearbyQuest> =
    catalog.asSequence()
        .filter { it.location == LocationMode.Gps }
        .map { quest -> NearbyQuest(quest, location?.let { quest.distanceKmFrom(it) }) }
        .sortedBy { it.distanceKm ?: Double.POSITIVE_INFINITY }
        .toList()

fun Quest.distanceKmFrom(location: LocationData): Double? {
    val latitude = latitude ?: return null
    val longitude = longitude ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return null
    val deltaLat = Math.toRadians(latitude - location.latitude)
    val deltaLon = Math.toRadians(longitude - location.longitude)
    val a = sin(deltaLat / 2).let { it * it } + sin(deltaLon / 2).let { it * it } *
        cos(Math.toRadians(location.latitude)) * cos(Math.toRadians(latitude))
    return 2 * 6_371.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

fun Quest.hasDestination(): Boolean =
    latitude?.let { it in -90.0..90.0 } == true && longitude?.let { it in -180.0..180.0 } == true

fun Quest.isWithinCheckInRange(location: LocationData, accuracyMeters: Float): Boolean =
    accuracyMeters in 0f..75f && (distanceKmFrom(location) ?: Double.POSITIVE_INFINITY) <= 0.05

fun Quest.bearingFrom(location: LocationData): Float? {
    val latitude = latitude ?: return null
    val longitude = longitude ?: return null
    if (!hasDestination() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return null
    val start = Math.toRadians(location.latitude)
    val target = Math.toRadians(latitude)
    val deltaLongitude = Math.toRadians(longitude - location.longitude)
    val east = sin(deltaLongitude) * cos(target)
    val north = cos(start) * sin(target) - sin(start) * cos(target) * cos(deltaLongitude)
    return ((Math.toDegrees(atan2(east, north)) + 360) % 360).toFloat()
}
