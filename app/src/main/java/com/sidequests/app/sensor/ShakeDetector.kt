package com.sidequests.app.sensor

/** Uses linear acceleration so the phone's orientation does not affect the threshold. */
class ShakeDetector {
    private var lastShakeNanos: Long? = null

    fun sample(x: Float, y: Float, z: Float, timestampNanos: Long): Boolean {
        if (x * x + y * y + z * z <= 225f || !x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        if (lastShakeNanos?.let { timestampNanos - it < 2_000_000_000L } == true) return false
        lastShakeNanos = timestampNanos
        return true
    }
}
