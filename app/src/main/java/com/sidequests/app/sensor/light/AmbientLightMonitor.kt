package com.sidequests.app.sensor.light

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides dark/light from ambient light readings (lux).
 *
 * Two thresholds (hysteresis) avoid flicker around a single value, and a new decision is only
 * applied after it has persisted for [minHoldMillis] (so briefly passing a hand over the phone
 * does not switch the theme). Pure Kotlin so it can be unit tested without Android.
 */
class AmbientLightClassifier(
    private val darkBelowLux: Float = DARK_BELOW_LUX,
    private val lightAboveLux: Float = LIGHT_ABOVE_LUX,
    private val minHoldMillis: Long = MIN_HOLD_MILLIS,
) {
    var isDark: Boolean? = null
        private set
    private var pendingCandidate: Boolean? = null
    private var pendingSince: Long = 0L

    /** True while a change is waiting for the hold time to pass. */
    val hasPendingChange: Boolean get() = pendingCandidate != null

    /** Returns the new decision when it changes, or null when it stays the same (or is pending). */
    fun onReading(lux: Float, nowMillis: Long): Boolean? {
        val current = isDark
        val candidate = when {
            lux < darkBelowLux -> true
            lux > lightAboveLux -> false
            else -> current ?: false
        }
        if (current == null) {
            isDark = candidate
            return candidate
        }
        if (candidate == current) {
            pendingCandidate = null
            return null
        }
        if (pendingCandidate != candidate) {
            pendingCandidate = candidate
            pendingSince = nowMillis
            return null
        }
        if (nowMillis - pendingSince < minHoldMillis) return null

        isDark = candidate
        pendingCandidate = null
        return candidate
    }

    companion object {
        const val DARK_BELOW_LUX = 15f
        const val LIGHT_ABOVE_LUX = 40f
        const val MIN_HOLD_MILLIS = 2_000L
    }
}

/**
 * Sensor feature (Sprint 2, Carlos): ambient light sensor that drives the automatic theme.
 *
 * The listener is registered only while the Activity is started ([start]/[stop]) to avoid
 * draining battery in the background. Light sensors are "on-change" sensors: they stop
 * reporting while the light is stable, so when a change is pending the last reading is
 * re-evaluated after the hold time instead of waiting for a new event.
 * Devices without a light sensor report [isAvailable] = false and keep the manual toggle.
 */
class AmbientLightMonitor(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val classifier = AmbientLightClassifier()
    private val handler = Handler(Looper.getMainLooper())
    private var lastLux: Float? = null

    val isAvailable: Boolean get() = lightSensor != null

    private val _isDark = MutableStateFlow<Boolean?>(null)
    val isDark: StateFlow<Boolean?> = _isDark.asStateFlow()

    /** Latest reading in lux (shown in Profile so the behaviour can be checked live). */
    private val _lux = MutableStateFlow<Float?>(null)
    val lux: StateFlow<Float?> = _lux.asStateFlow()

    private val recheck = Runnable { lastLux?.let(::evaluate) }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val value = event.values.firstOrNull() ?: return
            lastLux = value
            _lux.value = value
            evaluate(value)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun evaluate(value: Float) {
        classifier.onReading(value, SystemClock.elapsedRealtime())?.let { _isDark.value = it }
        handler.removeCallbacks(recheck)
        if (classifier.hasPendingChange) {
            handler.postDelayed(recheck, AmbientLightClassifier.MIN_HOLD_MILLIS + 100)
        }
    }

    fun start() {
        val sensor = lightSensor ?: return
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        handler.removeCallbacks(recheck)
        sensorManager.unregisterListener(listener)
    }
}
