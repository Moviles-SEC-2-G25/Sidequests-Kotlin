package com.sidequests.app.context

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

data class CheckInFix(val location: LocationData, val accuracyMeters: Float, val elapsedRealtimeNanos: Long) {
    fun isRecent(): Boolean = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos in 0L..30_000_000_000L
}

/** Foreground-only fixes. The caller starts collection after checking precise location permission. */
@SuppressLint("MissingPermission")
fun checkInFixes(context: Context, lowBattery: Boolean) = callbackFlow {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val stopped = AtomicBoolean()
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, if (lowBattery) 15_000L else 5_000L)
        .setMaxUpdateAgeMillis(0L)
        .setMinUpdateDistanceMeters(5f)
        .build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            if (location.hasAccuracy()) {
                trySend(CheckInFix(LocationData(location.latitude, location.longitude),
                    location.accuracy, location.elapsedRealtimeNanos))
            }
        }
    }
    try {
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnSuccessListener { if (stopped.get()) client.removeLocationUpdates(callback) }
            .addOnFailureListener { close() }
    } catch (_: SecurityException) {
        close()
    }
    awaitClose {
        stopped.set(true)
        client.removeLocationUpdates(callback)
    }
}.conflate()
