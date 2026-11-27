package com.sidequests.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.core.location.LocationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.sidequests.app.data.analytics.Bq11Attempt
import com.sidequests.app.data.analytics.Bq11Blocks
import com.sidequests.app.data.analytics.weekendStart
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
internal fun Bq11Sharing(attempt: Bq11Attempt, viewModel: AppViewModel) {
    val context = LocalContext.current
    val enrolled by viewModel.bq11Enrollment.collectAsStateWithLifecycle()
    val sharing = enrolled == attempt
    var permissionDenied by remember(attempt) { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionDenied = grants[Manifest.permission.ACCESS_FINE_LOCATION] != true
        if (!permissionDenied) viewModel.setBq11Sharing(attempt, true)
    }

    Column(Modifier.padding(bottom = 2.dp)) {
        Text("Weekend quest research", style = MaterialTheme.typography.titleSmall)
        Text(
            "Optional. On weekends in Bogotá, location estimates time at pilot commercial blocks while this quest screen is open. " +
                "We share a summary linked to this attempt after completion for aggregate sponsorship research. " +
                "This measurement does not save or upload your GPS coordinates.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            when {
                sharing -> viewModel.setBq11Sharing(attempt, false)
                context.hasPreciseLocation() -> viewModel.setBq11Sharing(attempt, true)
                else -> permissions.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ))
            }
        }) {
            Text(if (sharing) "Stop sharing and discard measurement" else "Share activity for this attempt")
        }
        if (permissionDenied && !sharing) {
            Text("Precise location is required to share. You can continue the quest without it.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ObserveBq11Sharing(attempt: Bq11Attempt, viewModel: AppViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val enrolled by viewModel.bq11Enrollment.collectAsStateWithLifecycle()
    val sharing = enrolled == attempt
    LaunchedEffect(lifecycle, attempt, sharing) {
        if (sharing) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (isActive) {
                    if (weekendStart(Instant.now()) != null && context.hasPreciseLocation()) {
                        val fix = freshBq11Fix(context)
                        val age = SystemClock.elapsedRealtimeNanos() - (fix?.elapsedRealtimeNanos ?: 0)
                        if (fix != null && age in 0..5_000_000_000L && !LocationCompat.isMock(fix)) {
                            viewModel.observeBq11(
                                attempt,
                                Bq11Blocks.match(fix.latitude, fix.longitude, fix.accuracy),
                                Instant.ofEpochMilli(fix.time),
                                fix.elapsedRealtimeNanos / 1_000_000L,
                            )
                        } else viewModel.pauseBq11(attempt)
                    } else viewModel.pauseBq11(attempt)
                    delay(15_000)
                }
            } finally {
                viewModel.pauseBq11(attempt)
            }
        }
    }
}

private fun Context.hasPreciseLocation(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private suspend fun freshBq11Fix(context: Context): Location? = suspendCancellableCoroutine { continuation ->
    val token = CancellationTokenSource()
    val request = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .setMaxUpdateAgeMillis(0)
        .setDurationMillis(8_000)
        .build()
    continuation.invokeOnCancellation { token.cancel() }
    try {
        LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, token.token)
            .addOnCompleteListener { task ->
                if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null)
            }
    } catch (_: SecurityException) {
        if (continuation.isActive) continuation.resume(null)
    }
}
