package com.sidequests.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.sidequests.app.data.history.WalkingTotalsStore
import com.sidequests.app.domain.WalkingSegment
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

data class QuestWalkingUi(
    val steps: StateFlow<Int?>,
    val sensorAvailable: Boolean,
    val permissionGranted: Boolean,
    val isOpen: Boolean,
    val requestPermission: () -> Unit,
)

@Composable
fun rememberQuestWalking(userId: String, attemptId: String?, isOpen: Boolean): QuestWalkingUi {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val manager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val sensor = remember(manager) { manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    val store = remember(context) { WalkingTotalsStore(context) }
    val steps = remember(userId, attemptId) { MutableStateFlow<Int?>(null) }
    var granted by remember(context) { mutableStateOf(hasActivityPermission(context)) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = hasActivityPermission(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasActivityPermission(context) }

    LaunchedEffect(owner, userId, attemptId, isOpen, granted) {
        if (attemptId == null) return@LaunchedEffect
        if (steps.value == null) steps.value = store.read(userId)[attemptId]
        if (!isOpen || !granted || manager == null || sensor == null) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val segment = WalkingSegment()
            var pending = 0
            var hasSample = steps.value != null

            suspend fun saveTotals() = withContext(NonCancellable) {
                try {
                    store.add(userId, attemptId, pending)
                    pending = 0
                } catch (_: IOException) {
                    // A later batch can still save the steps counted in memory.
                }
            }

            try {
                stepSamples(manager, sensor).collect { cumulative ->
                    val delta = segment.observe(cumulative)
                    val total = ((steps.value ?: 0).toLong() + delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    steps.value = total
                    pending = (pending.toLong() + delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    if (!hasSample || pending >= 8) saveTotals()
                    hasSample = true
                }
            } finally {
                if (pending > 0) saveTotals()
            }
        }
    }

    return QuestWalkingUi(
        steps = steps,
        sensorAvailable = sensor != null,
        permissionGranted = granted,
        isOpen = isOpen,
        requestPermission = {
            if (Build.VERSION.SDK_INT >= 29) requestPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        },
    )
}

@Composable
fun QuestWalkingCard(walking: QuestWalkingUi) {
    val steps by walking.steps.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Steps walked", style = MaterialTheme.typography.titleMedium)
            if (steps != null) Text(requireNotNull(steps).toString(), style = MaterialTheme.typography.headlineSmall)
            when {
                !walking.isOpen && steps == null -> Text("No walking data recorded.")
                !walking.isOpen -> Unit
                !walking.sensorAvailable -> Text("This device does not have a step counter.")
                !walking.permissionGranted -> {
                    Text("Allow physical activity access to count steps during this quest.")
                    TextButton(onClick = walking.requestPermission) { Text("Allow access") }
                }
                steps == null -> Text("Waiting for the step counter…")
            }
        }
    }
}

private fun hasActivityPermission(context: Context) = Build.VERSION.SDK_INT < 29 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

private fun stepSamples(manager: SensorManager, sensor: Sensor) = callbackFlow {
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            event.values.firstOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { trySend(it.toInt()) }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) close()
    awaitClose { manager.unregisterListener(listener) }
}.conflate()
