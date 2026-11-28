package com.sidequests.app.domain

/** A foreground segment of Android's cumulative step counter. The first reading is a baseline. */
class WalkingSegment {
    private var previous: Int? = null

    fun observe(cumulative: Int): Int {
        val delta = previous?.let { (cumulative - it).coerceAtLeast(0) } ?: 0
        previous = cumulative
        return delta
    }
}
