package com.sidequests.app.model

import kotlin.math.roundToInt

/** Normalizes catalogue labels. This is not a distance measured from the user's GPS. */
fun Quest.catalogueDistanceMeters(): Int? {
    val match = DISTANCE_PATTERN.find(distance.lowercase()) ?: return null
    val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2]) {
        "mi", "mile", "miles" -> 1_609.344
        "km", "kilometer", "kilometers" -> 1_000.0
        "m", "meter", "meters" -> 1.0
        else -> return null
    }
    return (amount * multiplier).roundToInt()
}

private val DISTANCE_PATTERN = Regex(
    """([0-9]+(?:[.,][0-9]+)?)\s*(mi|mile|miles|km|kilometer|kilometers|m|meter|meters)\b"""
)
