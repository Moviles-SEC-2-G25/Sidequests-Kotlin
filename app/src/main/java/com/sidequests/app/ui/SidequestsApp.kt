package com.sidequests.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.SidequestsUiState
import com.sidequests.app.ui.auth.AuthScreen
import com.sidequests.app.ui.auth.AuthStage
import com.sidequests.app.ui.auth.AuthViewModel
import com.sidequests.app.ui.auth.MissingSupabaseConfigurationScreen
import com.sidequests.app.ui.theme.SidequestsTheme
import kotlinx.coroutines.delay

@Composable
fun SidequestsApp(
    appViewModel: AppViewModel = viewModel(),
    authViewModel: AuthViewModel = viewModel(),
) {
    val appState by appViewModel.uiState.collectAsStateWithLifecycle()
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(authState.stage, authState.userId) {
        if (authState.stage == AuthStage.SignedIn) authState.userId?.let(appViewModel::attachAccount)
        else if (authState.stage == AuthStage.SignedOut) appViewModel.detachAccount()
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        appViewModel.refreshContext()
        if (appState.screen == AppScreen.QuestAlternatives) appViewModel.refreshQuestAlternatives()
    }

    SidequestsTheme(darkTheme = appState.darkMode) {
        when (authState.stage) {
            AuthStage.Initializing -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            AuthStage.ConfigurationMissing -> {
                MissingSupabaseConfigurationScreen()
            }

            AuthStage.SignedOut -> {
                AuthScreen(
                    state = authState,
                    onSignIn = authViewModel::signIn,
                    onSignUp = authViewModel::signUp,
                    onClearFeedback = authViewModel::clearFeedback,
                )
            }

            AuthStage.SignedIn -> {
                if (appState.accountUserId != authState.userId || authState.userId == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (appState.localStateError != null) {
                    Column(Modifier.padding(24.dp)) {
                        Text(requireNotNull(appState.localStateError))
                        TextButton(onClick = { authState.userId?.let(appViewModel::attachAccount) }) { Text("Try again") }
                    }
                } else AuthenticatedSidequestsContent(
                    state = appState,
                    viewModel = appViewModel,
                    onSignOut = authViewModel::signOut,
                    onRequestLocation = {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun AuthenticatedSidequestsContent(
    state: SidequestsUiState,
    viewModel: AppViewModel,
    onSignOut: () -> Unit,
    onRequestLocation: () -> Unit,
) {
    LaunchedEffect(Unit) {
        viewModel.refreshQuestCatalog()
    }

    LaunchedEffect(state.screen, state.contextualNotificationShown) {
        if (state.screen == AppScreen.Explorer && !state.contextualNotificationShown) {
            delay(4_000)
            viewModel.showContextualNotification()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.pendingPhotoUploads > 0) {
                PendingPhotoUploadsBanner(state.pendingPhotoUploads, viewModel::retryPendingPhotoUploads)
            }
            Box(modifier = Modifier.weight(1f)) {
                when (state.screen) {
                    AppScreen.Auth -> AuthScreen(
                        state = com.sidequests.app.ui.auth.AuthUiState(
                            stage = AuthStage.SignedOut,
                        ),
                        onSignIn = { _, _ -> },
                        onSignUp = { _, _, _ -> },
                        onClearFeedback = {},
                    )

                    AppScreen.Onboarding -> OnboardingScreen(state, viewModel)

                    AppScreen.Explorer -> ExplorerScreen(
                        state = state,
                        viewModel = viewModel,
                        contextState = viewModel.contextState.collectAsStateWithLifecycle().value,
                        onRequestLocation = onRequestLocation,
                    )

                    AppScreen.QuestDetail -> QuestDetailScreen(
                        viewModel.selectedQuest(),
                        viewModel,
                    )

                    AppScreen.ActiveQuest -> ActiveQuestScreen(
                        quest = viewModel.activeQuest(),
                        progress = viewModel.activeProgress(),
                        viewModel = viewModel,
                    )

                    AppScreen.ExitFlow -> ExitFlowScreen(
                        quest = viewModel.activeQuest(),
                        progress = viewModel.activeProgress(),
                        viewModel = viewModel,
                    )

                    AppScreen.QuestAlternatives -> QuestAlternativesScreen(
                        state = state,
                        viewModel = viewModel,
                        onRequestLocation = onRequestLocation,
                    )

                    AppScreen.Rating -> RatingScreen(
                        viewModel.activeQuest(),
                        viewModel,
                    )

                    AppScreen.GroupQuest -> GroupQuestScreen(
                        viewModel.activeQuest(),
                        viewModel,
                    )

                    AppScreen.Profile -> ProfileScreen(
                        state = state,
                        viewModel = viewModel,
                        onSignOut = onSignOut,
                    )
                }
            }

            if (state.screen !in setOf(
                    AppScreen.Onboarding,
                    AppScreen.ExitFlow,
                    AppScreen.QuestAlternatives,
                    AppScreen.Rating,
                )
            ) {
                BottomBar(
                    screen = state.screen,
                    questAvailable = viewModel.hasActiveQuest(),
                    onNavigate = { target ->
                        if (target == AppScreen.ActiveQuest) {
                            viewModel.navigate(
                                if (viewModel.activeQuest().isGroup) {
                                    AppScreen.GroupQuest
                                } else {
                                    AppScreen.ActiveQuest
                                }
                            )
                        } else {
                            viewModel.navigate(target)
                        }
                    },
                )
            }
        }

        state.notificationQuestId?.let { questId ->
            viewModel.allQuests()
                .firstOrNull { it.id == questId }
                ?.let { quest ->
                    ContextNotificationBanner(
                        quest = quest,
                        onOpen = viewModel::openNotificationQuest,
                        onDismiss = viewModel::dismissNotification,
                        modifier = Modifier
                            .zIndex(10f)
                            .padding(top = 28.dp),
                    )
                }
        }
    }
}

@Composable
private fun PendingPhotoUploadsBanner(count: Int, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (count == 1) "1 photo waiting to upload" else "$count photos waiting to upload",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Saved on this device. Upload resumes when connected.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                Text("Retry upload")
            }
        }
    }
}
