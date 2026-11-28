package com.sidequests.app.ui

import android.Manifest
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidequests.app.data.history.HistoryLoad
import com.sidequests.app.data.history.HistorySource
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.SidequestsUiState
import com.sidequests.app.ui.auth.AuthScreen
import com.sidequests.app.ui.auth.AuthStage
import com.sidequests.app.ui.auth.AuthViewModel
import com.sidequests.app.ui.auth.BiometricLockScreen
import com.sidequests.app.ui.auth.BiometricUi
import com.sidequests.app.ui.auth.EmailCodeScreen
import com.sidequests.app.ui.auth.rememberBiometricUi
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
    val deviceStatus by appViewModel.deviceStatus.collectAsStateWithLifecycle()
    val biometric = rememberBiometricUi(authViewModel)
    var wasOffline by remember { mutableStateOf(false) }
    var connectionRestored by remember { mutableStateOf(false) }
    var locationPermissionRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(deviceStatus.isOnline) {
        if (!deviceStatus.isOnline) {
            wasOffline = true
            connectionRestored = false
        } else if (wasOffline) {
            wasOffline = false
            connectionRestored = true
            delay(3_000)
            connectionRestored = false
        }
    }

    LaunchedEffect(authState.stage, authState.userId) {
        if (authState.stage == AuthStage.SignedIn) authState.userId?.let(appViewModel::attachAccount)
        else if (authState.stage == AuthStage.SignedOut || authState.stage == AuthStage.BiometricLocked) {
            appViewModel.detachAccount()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        locationPermissionRevision++
        appViewModel.refreshContext(force = appState.screen in setOf(AppScreen.Nearby, AppScreen.QuestDetail))
        if (appState.screen == AppScreen.QuestAlternatives) appViewModel.refreshQuestAlternatives()
    }

    SidequestsTheme(darkTheme = appState.darkMode) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
        ) {
            DeviceStatusBanner(
                status = deviceStatus,
                onRetry = if (authState.stage == AuthStage.SignedIn &&
                    appState.accountUserId != null && appState.accountUserId == authState.userId
                ) appViewModel::retryNetworkWork else null,
                connectionRestored = connectionRestored,
                // Signed out there is no saved data to show: explain what needs internet instead.
                offlineBody = when (authState.stage) {
                    AuthStage.SignedIn -> null
                    AuthStage.BiometricLocked -> "You can unlock your saved session on this device."
                    else -> "Signing in and creating an account need internet. Connect to continue."
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
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

                    AuthStage.BiometricLocked -> BiometricLockScreen(authState, biometric, authViewModel::signOut)

                    AuthStage.SignedOut -> {
                        if (authState.emailCodeMode) {
                            EmailCodeScreen(authState, deviceStatus.isOnline, authViewModel)
                        } else AuthScreen(
                            state = authState,
                            onSignIn = { email, password ->
                                authViewModel.signIn(email, password, deviceStatus.isOnline)
                            },
                            onSignUp = { name, email, password ->
                                authViewModel.signUp(name, email, password, deviceStatus.isOnline)
                            },
                            onClearFeedback = authViewModel::clearFeedback,
                            isOnline = deviceStatus.isOnline,
                            onEmailCode = { authViewModel.setEmailCodeMode(true) },
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
                            authViewModel = authViewModel,
                            accountEmail = authState.userEmail,
                            displayName = authState.userDisplayName,
                            biometric = biometric,
                            biometricEnabled = authState.biometricEnabled,
                            locationPermissionRevision = locationPermissionRevision,
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
    }
}

@Composable
private fun AuthenticatedSidequestsContent(
    state: SidequestsUiState,
    viewModel: AppViewModel,
    authViewModel: AuthViewModel,
    accountEmail: String?,
    displayName: String?,
    biometric: BiometricUi,
    biometricEnabled: Boolean,
    locationPermissionRevision: Int,
    onSignOut: () -> Unit,
    onRequestLocation: () -> Unit,
) {
    val deviceStatus by viewModel.deviceStatus.collectAsStateWithLifecycle()
    val contextState by viewModel.contextState.collectAsStateWithLifecycle()
    val passwordState by authViewModel.passwordState.collectAsStateWithLifecycle()
    val offlinePackEntries by viewModel.offlinePackEntries.collectAsStateWithLifecycle()
    val account = state.accountUserId ?: return
    val quest = viewModel.activeQuest()
    val attemptId = state.attemptIdByQuest[quest.id]
    val openProgress = viewModel.openAttemptProgress(quest.id)?.takeUnless { it.paused }
    val walking = rememberQuestWalking(account, attemptId, openProgress != null)
    if (attemptId != null && openProgress != null) {
        QuestCheckIn(account, attemptId, quest, openProgress, locationPermissionRevision,
            deviceStatus.isLowBattery, viewModel)
    }
    LaunchedEffect(state.automaticCheckInMessage) {
        if (state.automaticCheckInMessage != null) {
            delay(5_000)
            viewModel.clearAutomaticCheckInMessage()
        }
    }
    val screenState = key(account) { rememberSaveableStateHolder() }
    val ratingVisible = state.screen == AppScreen.Rating ||
        (state.screen == AppScreen.QuestDetail && state.detailOrigin == AppScreen.Rating)
    LaunchedEffect(screenState, ratingVisible) {
        if (!ratingVisible) screenState.removeState("rating")
    }
    val historyScope = rememberCoroutineScope()
    val historyController = remember(account, viewModel, historyScope) {
        MissionHistoryController(
            account, viewModel.missionHistorySource ?: HistorySource { _, _ -> HistoryLoad(null, false) },
            historyScope,
        )
    }
    DisposableEffect(historyController) { onDispose { historyController.close() } }
    val historyState by historyController.state.collectAsStateWithLifecycle()
    LaunchedEffect(historyController, deviceStatus.isOnline) {
        historyController.connectivityChanged(deviceStatus.isOnline)
    }
    LaunchedEffect(historyController, state.screen) {
        if (state.screen == AppScreen.Statistics || state.screen == AppScreen.MissionHistory) {
            historyController.refresh()
        }
    }
    var lastHistoryRevision by remember(historyController) { mutableStateOf(state.historyRevision) }
    LaunchedEffect(historyController, state.historyRevision) {
        if (lastHistoryRevision != state.historyRevision) {
            delay(250)
            historyController.refreshAfterPendingLoad()
            lastHistoryRevision = state.historyRevision
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshQuestCatalog()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshContextIfStale() }

    LaunchedEffect(deviceStatus.isOnline) {
        if (deviceStatus.isOnline) viewModel.retryOfflinePackDownloads()
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
            state.automaticCheckInMessage?.let {
                Text(it, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
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

                    AppScreen.Explorer -> screenState.SaveableStateProvider("explore") {
                        ExplorerScreen(state, viewModel, contextState, onRequestLocation,
                            statistics = historyState.statistics, displayName = displayName)
                    }

                    AppScreen.Nearby -> screenState.SaveableStateProvider("nearby") {
                        NearbyScreen(state, contextState, viewModel, onRequestLocation)
                    }

                    AppScreen.Social -> SocialScreen { viewModel.navigate(AppScreen.GroupChallenges) }

                    AppScreen.QuestHome -> QuestHomeScreen { viewModel.navigate(AppScreen.Explorer) }

                    AppScreen.VarietyDiscovery -> VarietyScreen(state, viewModel)

                    AppScreen.GroupChallenges -> key(state.accountUserId) {
                        BackHandler(onBack = viewModel::closeGroupChallenges)
                        GroupChallengesScreen(state, viewModel, deviceStatus.isOnline)
                    }

                    AppScreen.MissionHistory, AppScreen.QuestDetail -> {
                        if (state.screen == AppScreen.MissionHistory || state.detailFromHistory) {
                            MissionHistoryRoute(state, viewModel, deviceStatus.isOnline, historyController, onRequestLocation)
                        } else {
                            BackHandler(onBack = viewModel::closeQuestDetail)
                            QuestDetailScreen(viewModel.selectedQuest(), viewModel,
                                onBack = viewModel::closeQuestDetail, onRequestLocation = onRequestLocation)
                        }
                    }

                    AppScreen.ActiveQuest -> ActiveQuestScreen(
                        quest = viewModel.activeQuest(),
                        progress = viewModel.activeProgress(),
                        viewModel = viewModel,
                        walking = walking,
                        onRequestLocation = onRequestLocation,
                    )

                    AppScreen.ExitFlow -> ExitFlowScreen(
                        quest = viewModel.activeQuest(),
                        progress = viewModel.activeProgress(),
                        viewModel = viewModel,
                        attemptId = attemptId,
                    )

                    AppScreen.QuestAlternatives -> QuestAlternativesScreen(
                        state = state,
                        viewModel = viewModel,
                        onRequestLocation = onRequestLocation,
                    )

                    AppScreen.Rating -> screenState.SaveableStateProvider("rating") {
                        BackHandler(onBack = viewModel::skipRating)
                        RatingScreen(
                            viewModel.activeQuest(),
                            viewModel,
                            state = state,
                            history = historyState.statistics,
                        )
                    }

                    AppScreen.GroupQuest -> GroupQuestScreen(
                        viewModel.activeQuest(),
                        viewModel,
                    )

                    AppScreen.OfflinePack -> OfflinePackScreen(
                        quests = viewModel.allQuests(),
                        entries = offlinePackEntries,
                        isOnline = deviceStatus.isOnline,
                        onAdd = viewModel::addQuestToOfflinePack,
                        onRemove = viewModel::removeQuestFromOfflinePack,
                        onRetry = viewModel::retryOfflinePackDownloads,
                        onBack = { viewModel.navigate(AppScreen.Profile) },
                    )
                    AppScreen.SocialComfort -> SocialComfortScreen(state, viewModel, deviceStatus.isOnline)

                    AppScreen.Statistics -> QuestStatisticsScreen(
                        historyState,
                        onBack = { viewModel.navigate(AppScreen.Profile) },
                        onRefresh = historyController::refresh,
                    )

                    AppScreen.Profile -> ProfileScreen(
                        state = state,
                        viewModel = viewModel,
                        onSignOut = onSignOut,
                        accountEmail = accountEmail,
                        displayName = displayName,
                        address = contextState.address,
                        isOnline = deviceStatus.isOnline,
                        passwordState = passwordState,
                        statistics = historyState.statistics,
                        biometric = biometric,
                        biometricEnabled = biometricEnabled,
                        onDisableBiometrics = { authViewModel.setBiometricEnabled(account, false) },
                        onChangePassword = { newPassword, confirmation ->
                            authViewModel.changePassword(newPassword, confirmation, deviceStatus.isOnline)
                        },
                        onClearPasswordFeedback = authViewModel::clearPasswordFeedback,
                    )
                }
            }

            if (state.screen !in setOf(
                    AppScreen.Onboarding,
                    AppScreen.ExitFlow,
                    AppScreen.QuestAlternatives,
                    AppScreen.Rating,
                    AppScreen.MissionHistory,
                    AppScreen.SocialComfort,
                    AppScreen.Statistics,
                ) && !(state.screen == AppScreen.QuestDetail && (state.detailFromHistory || state.detailOrigin == AppScreen.Rating))
            ) {
                BottomBar(
                    screen = when (state.screen) {
                        AppScreen.QuestDetail -> state.detailOrigin
                        AppScreen.GroupChallenges -> state.groupChallengesOrigin
                        else -> state.screen
                    },
                    onNavigate = { target ->
                        if (target == AppScreen.QuestHome) viewModel.openQuestTab() else viewModel.navigate(target)
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
