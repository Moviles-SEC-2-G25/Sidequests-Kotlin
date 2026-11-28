package com.sidequests.app.ui

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sidequests.app.context.LocationData
import com.sidequests.app.domain.bearingFrom
import com.sidequests.app.domain.distanceKmFrom
import com.sidequests.app.domain.hasDestination
import com.sidequests.app.model.Quest
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun QuestCompass(quest: Quest, location: LocationData?, onRequestLocation: () -> Unit) {
    if (!quest.hasDestination()) return
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val bearing = location?.let { quest.bearingFrom(it) }
            val distance = location?.let { quest.distanceKmFrom(it) }
            if (location == null || bearing == null || distance == null) {
                Text("Turn on location to see the compass", modifier = Modifier.weight(1f))
                TextButton(onClick = onRequestLocation) { Text("Turn on") }
            } else {
                val label = if (distance < 1) "${(distance * 1_000).roundToInt()} m"
                    else String.format(Locale.US, "%.1f km", distance)
                if (distance < .005) {
                    Text("You are at the quest location")
                } else {
                    CompassDirection(location, bearing, label)
                }
            }
        }
    }
}

@Composable
private fun CompassDirection(location: LocationData, trueBearing: Float, distanceLabel: String) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember(view.context) { view.context.getSystemService(SensorManager::class.java) }
    val sensor = remember(manager) { manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    if (manager == null || sensor == null) {
        Text("Your device has no compass. Distance: $distanceLabel")
        return
    }
    val magneticDeclination = remember(location) {
        GeomagneticField(location.latitude.toFloat(), location.longitude.toFloat(), 0f, System.currentTimeMillis()).declination
    }
    val heading = produceState<Float?>(null, view, lifecycle, manager, sensor) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val matrix = FloatArray(9)
            val displayMatrix = FloatArray(9)
            val angles = FloatArray(3)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    val (x, y) = when (view.display?.rotation) {
                        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                    }
                    if (SensorManager.remapCoordinateSystem(matrix, x, y, displayMatrix)) {
                        SensorManager.getOrientation(displayMatrix, angles)
                        value = Math.toDegrees(angles[0].toDouble()).toFloat()
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)) {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    manager.unregisterListener(listener)
                    value = null
                }
            }
        }
    }
    val color = MaterialTheme.colorScheme.primary
    val calibrated = remember(heading) { derivedStateOf { heading.value != null } }
    Canvas(Modifier.size(40.dp).graphicsLayer {
        val magneticHeading = heading.value
        alpha = if (magneticHeading == null) 0f else 1f
        rotationZ = trueBearing - (magneticHeading ?: 0f) - magneticDeclination
    }) {
        drawPath(Path().apply {
            moveTo(size.width / 2, 0f)
            lineTo(size.width, size.height)
            lineTo(size.width / 2, size.height * .75f)
            lineTo(0f, size.height)
            close()
        }, color)
    }
    Column {
        Text(if (calibrated.value) "Follow the arrow" else "Calibrating compass…", style = MaterialTheme.typography.titleSmall)
        Text("$distanceLabel from the quest", style = MaterialTheme.typography.bodySmall)
    }
}
