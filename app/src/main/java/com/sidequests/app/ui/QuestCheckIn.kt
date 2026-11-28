package com.sidequests.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sidequests.app.context.CheckInFix
import com.sidequests.app.context.checkInFixes
import com.sidequests.app.domain.hasDestination
import com.sidequests.app.model.Quest
import com.sidequests.app.model.QuestProgress

@Composable
fun QuestCheckIn(
    account: String,
    attemptId: String,
    quest: Quest,
    progress: QuestProgress,
    locationPermissionRevision: Int,
    lowBattery: Boolean,
    viewModel: AppViewModel,
) {
    val stepIndex = progress.currentStep
    val step = quest.steps.getOrNull(stepIndex) ?: return
    if (quest.isGroup || !step.requiresLocation || !quest.hasDestination() || stepIndex in progress.completedSteps) return
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fix by remember(account, attemptId, stepIndex, quest.latitude, quest.longitude) { mutableStateOf<CheckInFix?>(null) }
    LaunchedEffect(lifecycle, account, attemptId, stepIndex, quest.latitude, quest.longitude, locationPermissionRevision, lowBattery) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    checkInFixes(context, lowBattery).collect { fix = it }
                }
            } finally {
                fix = null
            }
        }
    }
    val reading = fix
    LaunchedEffect(reading, progress.uploadedPhotoProofByStep[stepIndex]) {
        if (reading != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && reading.isRecent()) {
            viewModel.checkInAtLocation(account, attemptId, stepIndex, reading.location, reading.accuracyMeters)
        }
    }
}
