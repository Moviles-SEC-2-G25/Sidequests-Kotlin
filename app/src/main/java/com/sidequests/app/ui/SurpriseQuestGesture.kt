package com.sidequests.app.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sidequests.app.sensor.ShakeDetector
import kotlinx.coroutines.awaitCancellation

@Composable
fun rememberSurpriseQuestGesture(onShake: () -> Unit): Boolean {
    val context = LocalContext.current.applicationContext
    val manager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val sensor = remember(manager) { manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val notify by rememberUpdatedState(onShake)
    val detector = remember { ShakeDetector() }
    LaunchedEffect(manager, sensor, lifecycle) {
        if (manager == null || sensor == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val (x, y, z) = event.values
                    if (detector.sample(x, y, z, event.timestamp)) notify()
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)) {
                try {
                    awaitCancellation()
                } finally {
                    manager.unregisterListener(listener)
                }
            }
        }
    }
    return sensor != null
}
