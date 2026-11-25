package com.sidequests.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sidequests.app.BuildConfig
import com.sidequests.app.context.AddressProvider
import com.sidequests.app.domain.DifficultyOutcome
import com.sidequests.app.domain.DifficultyStreaks
import com.sidequests.app.domain.DifficultySuggestion
import com.sidequests.app.domain.SmartDifficultyAdvisor
import com.sidequests.app.domain.SmartTimeAdvisor
import com.sidequests.app.domain.TimeChoice
import com.sidequests.app.data.settings.SmartHistory
import com.sidequests.app.data.settings.ThemeSettings
import com.sidequests.app.data.settings.SettingsStore
import com.sidequests.app.telemetry.AppModule
import com.sidequests.app.telemetry.FailureReporter
import com.sidequests.app.context.label
import com.sidequests.app.context.ContextAwareRecommendationEngine
import com.sidequests.app.context.ContextManager
import com.sidequests.app.context.ContextUiState
import com.sidequests.app.context.DeviceStatus
import com.sidequests.app.context.LocationData
import com.sidequests.app.context.UserContext
import com.sidequests.app.context.timeOfDayFromHour
import com.sidequests.app.data.QuestCatalogueOrigin
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.data.SidequestsRepository
import com.sidequests.app.data.SupabaseSidequestsRepository
import com.sidequests.app.data.remote.SupabaseProvider
import com.sidequests.app.data.progress.QuestProgressRepository
import com.sidequests.app.data.progress.SupabaseQuestProgressRepository
import com.sidequests.app.data.analytics.AnalyticsRepository
import com.sidequests.app.data.analytics.Bq9RecommendationOutbox
import com.sidequests.app.data.analytics.buildRecommendationContextMetadata
import com.sidequests.app.data.analytics.buildBq6AbandonmentEvidence
import com.sidequests.app.data.analytics.SupabaseAnalyticsRepository
import com.sidequests.app.data.photo.PhotoProofRepository
import com.sidequests.app.data.photo.SupabasePhotoProofRepository
import com.sidequests.app.data.analytics.OnboardingRecorder
import com.sidequests.app.data.local.LexLocalStore
import com.sidequests.app.data.local.LexLocalState
import com.sidequests.app.data.local.OnboardingJourney
import com.sidequests.app.data.local.StoredPhotoProof
import com.sidequests.app.data.local.PhotoQuestSnapshot
import com.sidequests.app.data.local.PendingAnalyticsEvent
import com.sidequests.app.data.local.PendingProgressOp
import com.sidequests.app.data.remote.isNetworkFailure
import com.sidequests.app.domain.StepCompletionDecision
import com.sidequests.app.data.photo.photoEvent
import com.sidequests.app.data.recommendation.RecommendationRepository
import com.sidequests.app.domain.Bq8RecommendationPresentation
import com.sidequests.app.data.recommendation.CachedRecommendationRepository
import com.sidequests.app.data.recommendation.SupabaseRecommendationRepository
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.AbandonmentReason
import com.sidequests.app.model.Quest
import com.sidequests.app.model.QuestDifficulty
import com.sidequests.app.model.QuestProgress
import com.sidequests.app.model.QuestAlternative
import com.sidequests.app.model.QuestAlternativesState
import com.sidequests.app.model.QuestRating
import com.sidequests.app.model.SidequestsUiState
import com.sidequests.app.model.SocialLevel
import com.sidequests.app.model.UserPreferences
import com.sidequests.app.domain.QuestStepCompletionPolicy
import com.sidequests.app.domain.QuestAlternativeRecommender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.time.ZonedDateTime
class AppViewModel(
    private val contextManager: ContextManager,
    private val repository: SidequestsRepository =
        if (SupabaseProvider.isConfigured) SupabaseSidequestsRepository(SupabaseProvider.client)
        else InMemorySidequestsRepository(),
    private val progressRepository: QuestProgressRepository? =
        if (SupabaseProvider.isConfigured) SupabaseQuestProgressRepository(SupabaseProvider.client)
        else null,
    private val analyticsRepository: AnalyticsRepository? =
        if (SupabaseProvider.isConfigured) SupabaseAnalyticsRepository(SupabaseProvider.client)
        else null,
    private val recommendationRepository: RecommendationRepository? =
        if (SupabaseProvider.isConfigured) {
            // #23: LRU + TTL cache in front of the Smart Picks RPC.
            CachedRecommendationRepository(SupabaseRecommendationRepository(SupabaseProvider.client))
        } else null,
    private val photoProofRepository: PhotoProofRepository? =
        if (SupabaseProvider.isConfigured) {
            SupabasePhotoProofRepository(
                client = SupabaseProvider.client,
                bucketName = BuildConfig.SUPABASE_QUEST_PROOFS_BUCKET,
            )
        } else null,
    private val alternativeContextReader: suspend () -> UserContext = { contextManager.readContext() },
    private val alternativeContextSimulated: Boolean = false,
    private val localStore: LexLocalStore? = null,
    private val scheduleLocalSync: (String) -> Unit = {},
    private val localIoDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val retryLocalSync: (String) -> Unit = scheduleLocalSync,
    private val addressProvider: AddressProvider? = null,
    /** #31: DataStore persistence of theme and smart-feature history (null in unit tests). */
    private val settingsStore: SettingsStore? = null,
    /** BQ1 (#22): non-fatal module failures are reported here (no-op by default/in tests). */
    private val failureReporter: FailureReporter = FailureReporter.NONE,
    val missionHistorySource: com.sidequests.app.data.history.HistorySource? = null,
) : ViewModel() {

    private val analyticsSessionId = UUID.randomUUID().toString()
    private val recommendationEngine = ContextAwareRecommendationEngine()
    private val alternativeRecommender = QuestAlternativeRecommender()
    private var alternativeJob: Job? = null
    private var openedFromSmartPicksId: String? = null
    private val bq8RecordedExposures = mutableSetOf<String>()
    private var attachedUserId: String? = null
    private var accountGeneration = 0
    private var photoObserver: Job? = null
    /**
     * Eventual connectivity: quest lifecycle changes go to the durable outbox and WorkManager
     * replays them when online, so quests can be accepted and played without internet.
     * Without a local store (unit tests, no backend) the previous direct calls are used.
     */
    private val progressQueueEnabled: Boolean get() = localStore != null && progressRepository != null
    private val journeyMutex = Mutex()
    private val photoMutex = Mutex()
    private val _uiState = MutableStateFlow(SidequestsUiState())
    private val _contextState = MutableStateFlow(ContextUiState())
    val uiState: StateFlow<SidequestsUiState> = _uiState.asStateFlow()
    val contextState: StateFlow<ContextUiState> = _contextState.asStateFlow()

    // Context-aware feature: latest connectivity/battery snapshot from DeviceStatusMonitor.
    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    // Smart feature: when the last context was read, used by the battery-aware refresh policy.
    private var lastContextReadAtMillis: Long = 0L

    // Sensor feature: last decision from the ambient light sensor (null until the first reading).
    private var lastAmbientDark: Boolean? = null

    init {
        refreshContext()
        refreshQuestCatalog()
        restoreAndPersistSettings()
    }

    /** #31: user whose smart-feature history has been restored (and may now be saved). */
    @Volatile private var historyOwner: String? = null

    /**
     * Local storage (#31): restores the device theme, then saves every change of theme and of the
     * signed-in user's smart-feature history. Saving starts only after each restore, so defaults
     * never overwrite what was saved.
     */
    private fun restoreAndPersistSettings() {
        val store = settingsStore ?: return
        viewModelScope.launch {
            runCatching { store.loadTheme() }.getOrNull()?.let { theme ->
                _uiState.update {
                    it.copy(
                        darkMode = theme.darkMode,
                        autoThemeEnabled = theme.autoThemeEnabled && it.lightSensorAvailable,
                    )
                }
            }
            launch {
                _uiState
                    .map { ThemeSettings(it.darkMode, it.autoThemeEnabled) }
                    .distinctUntilChanged()
                    .collect { theme -> runCatching { store.saveTheme(theme) } }
            }
            _uiState
                .map {
                    SmartHistory(
                        timeChoices = it.timeChoices,
                        difficultyOutcomes = it.difficultyOutcomes,
                        difficultySuggestionDismissedAt = it.difficultySuggestionDismissedAt,
                    )
                }
                .distinctUntilChanged()
                .collect { history ->
                    val owner = historyOwner ?: return@collect
                    runCatching { store.saveHistory(owner, history) }
                }
        }
    }

    /** #31: loads the smart-feature history of the account that just signed in. */
    private fun restoreHistory(userId: String) {
        val store = settingsStore ?: return
        viewModelScope.launch {
            val history = runCatching { store.loadHistory(userId) }.getOrNull() ?: SmartHistory()
            // Wait until the account's saved setup (preferences, onboarding) has been applied.
            withTimeoutOrNull(5_000) { _uiState.first { it.accountUserId == userId } }
            if (attachedUserId != userId) return@launch
            _uiState.update {
                it.copy(
                    timeChoices = history.timeChoices,
                    difficultyOutcomes = history.difficultyOutcomes,
                    difficultySuggestionDismissedAt = history.difficultySuggestionDismissedAt,
                )
            }
            historyOwner = userId
        }
    }

    /**
     * New account state. Device-level settings (theme, light sensor) are kept, because they belong
     * to the phone, not to the signed-in user.
     */
    private fun freshAccountState(): SidequestsUiState {
        val current = _uiState.value
        return SidequestsUiState(
            darkMode = current.darkMode,
            autoThemeEnabled = current.autoThemeEnabled,
            lightSensorAvailable = current.lightSensorAvailable,
            ambientLux = current.ambientLux,
        )
    }

    fun detachAccount() {
        (recommendationRepository as? CachedRecommendationRepository)?.useAccountScope(null)
        bq8RecordedExposures.clear()
        historyOwner = null
        attachedUserId = null
        accountGeneration += 1
        photoObserver?.cancel()
        alternativeJob?.cancel()
        _uiState.value = freshAccountState()
    }

    fun attachAccount(userId: String) {
        if (attachedUserId == userId && _uiState.value.accountUserId == userId && _uiState.value.localStateError == null) return
        historyOwner = null
        (recommendationRepository as? CachedRecommendationRepository)?.useAccountScope(userId)
        bq8RecordedExposures.clear()
        attachedUserId = userId
        accountGeneration += 1
        photoObserver?.cancel()
        _uiState.value = freshAccountState()
        restoreHistory(userId)
        val store = localStore ?: run {
            _uiState.update { it.copy(accountUserId = userId) }
            return
        }
        viewModelScope.launch {
            try {
                val (journey, saved) = withContext(localIoDispatcher) {
                    OnboardingRecorder(store, userId).startOrResume() to store.read()
                }
                if (attachedUserId != userId) return@launch
                _uiState.update { it.copy(
                    accountUserId = userId,
                    preferences = journey.preferences,
                    availableTime = journey.preferences.typicalTime,
                    onboardingStep = journey.step,
                    screen = if (journey.status == "active") AppScreen.Onboarding else AppScreen.Explorer,
                ) }
                val open = saved.photoQuests.values.filter { it.userId == userId && !it.closed }
                if (open.isNotEmpty()) {
                    _uiState.update { state -> state.copy(
                        attemptIdByQuest = open.associate { it.questId to it.attemptId },
                        progressByQuest = open.associate { it.questId to QuestProgress(currentStep = it.currentStep, completedSteps = it.completedSteps) },
                        activeQuestId = open.last().questId,
                        screen = if (repository.questById(open.last().questId).isGroup) AppScreen.GroupQuest else AppScreen.ActiveQuest,
                    ) }
                }
                applyStoredPhotos(saved, userId)
                scheduleLocalSync(userId)
                refreshQuestCatalog()
                refreshRecommendations()
                photoObserver = viewModelScope.launch {
                    store.changes.collect {
                        val latest = withContext(localIoDispatcher) { store.read() }
                        if (attachedUserId == userId) applyStoredPhotos(latest, userId)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                if (attachedUserId == userId) _uiState.update { it.copy(accountUserId = userId, localStateError = "Could not read your saved setup and photos. Try again.") }
            }
        }
    }

    private fun applyStoredPhotos(saved: LexLocalState, userId: String) {
        val photos = saved.photos.filter { it.userId == userId }
        _uiState.update { state ->
            var progress = state.progressByQuest
            photos.groupBy { it.questId to it.stepIndex }.forEach { (key, _) ->
                val entry = photos.lastOrNull { it.questId == key.first && it.stepIndex == key.second && it.attemptId == state.attemptIdByQuest[key.first] }
                if (entry != null) {
                    val old = progress[entry.questId] ?: QuestProgress()
                    progress = progress + (entry.questId to old.copy(
                        localPhotoProofByStep = if (entry.status != "missing") old.localPhotoProofByStep + (entry.stepIndex to entry.localPath) else old.localPhotoProofByStep - entry.stepIndex,
                        uploadedPhotoProofByStep = if (entry.status == "uploaded" && entry.storagePath != null) old.uploadedPhotoProofByStep + (entry.stepIndex to entry.storagePath) else old.uploadedPhotoProofByStep - entry.stepIndex,
                        photoProofUploadingSteps = if (entry.status == "uploading") old.photoProofUploadingSteps + entry.stepIndex else old.photoProofUploadingSteps - entry.stepIndex,
                        photoProofErrorByStep = if (entry.error != null) old.photoProofErrorByStep + (entry.stepIndex to entry.error) else old.photoProofErrorByStep - entry.stepIndex,
                    ))
                }
            }
            state.copy(
                progressByQuest = progress,
                pendingPhotoUploads = photos.count { it.status in setOf("pending", "uploading") },
                pendingProgressSync = saved.progressOps.count { it.userId == userId },
            )
        }
    }

    fun retryPendingPhotoUploads() { attachedUserId?.let(retryLocalSync) }

    private fun updateJourney(action: (OnboardingRecorder) -> OnboardingJourney, navigate: Boolean = false, schedule: Boolean = true) {
        val store = localStore ?: return
        val userId = attachedUserId ?: return
        viewModelScope.launch {
            journeyMutex.withLock {
                try {
                    val journey = withContext(localIoDispatcher) { action(OnboardingRecorder(store, userId)) }
                    if (attachedUserId == userId && navigate) {
                        _uiState.update { it.copy(
                            onboardingStep = journey.step,
                            screen = if (journey.status == "active") AppScreen.Onboarding else AppScreen.Explorer,
                            onboardingSaving = false,
                        ) }
                        if (journey.status != "active") refreshRecommendations()
                    }
                    if (schedule) scheduleLocalSync(userId)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) {
                    _uiState.update { it.copy(onboardingSaving = false, localStateError = "Your setup could not be saved. Please try again.") }
                }
            }
        }
    }

    fun firstOnboardingRecommendationVisible(questId: String) {
        updateJourney({ it.firstRecommendationShown(questId) })
    }

    private fun persistSetupPreferences() {
        // Explorer choices are user preferences too, not just onboarding answers.
        if (attachedUserId == null || localStore == null) return
        val preferences = _uiState.value.preferences
        updateJourney({ it.savePreferences(preferences) }, schedule = false)
    }

    private fun persistActiveQuestProgress(
        questId: String,
        attemptId: String,
        progress: QuestProgress,
        closed: Boolean = false,
        op: PendingProgressOp? = null,
    ) {
        val store = localStore ?: return
        val userId = attachedUserId ?: return
        viewModelScope.launch {
            try {
                photoMutex.withLock {
                    withContext(localIoDispatcher) {
                        // The snapshot and its queued server change are committed together.
                        store.update { saved ->
                            saved.copy(
                                photoQuests = saved.photoQuests + (attemptId to PhotoQuestSnapshot(userId, questId, attemptId, progress.currentStep, progress.completedSteps, closed)),
                                progressOps = if (op == null) saved.progressOps else saved.progressOps + op,
                            )
                        }
                    }
                }
                if (op != null) scheduleLocalSync(userId)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { _uiState.update { it.copy(localStateError = "Quest progress could not be saved on this device. Please try again.") } }
        }
    }

    /** Builds a queued lifecycle change for the signed-in account (null when not queueing). */
    private fun progressOp(
        type: String,
        questId: String,
        attemptId: String,
        currentStep: Int = 0,
        completedSteps: Set<Int> = emptySet(),
        completed: Boolean = false,
        reason: String? = null,
        stars: Int? = null,
        tags: Set<String> = emptySet(),
        confirmedEvents: List<PendingAnalyticsEvent> = emptyList(),
    ): PendingProgressOp? {
        if (!progressQueueEnabled) return null
        val userId = attachedUserId ?: return null
        return PendingProgressOp(
            id = UUID.randomUUID().toString(),
            userId = userId,
            attemptId = attemptId,
            questId = questId,
            type = type,
            occurredAt = java.time.Instant.now().toString(),
            currentStep = currentStep,
            completedSteps = completedSteps,
            completed = completed,
            reason = reason,
            stars = stars,
            tags = tags,
            confirmedEvents = confirmedEvents,
        )
    }

    /** Feedback right after a change was queued: it is safe on the device either way. */
    private fun showQueuedProgress(message: String) {
        val online = _deviceStatus.value.isOnline
        _uiState.update {
            it.copy(
                progressSyncing = false,
                progressSyncError = null,
                progressSyncMessage = if (online) message else QUEUED_OFFLINE_MESSAGE,
            )
        }
    }

    /** Analytics row that is released only when Supabase confirms the queued operation. */
    private fun confirmedEvent(eventType: String, quest: Quest, metadata: kotlinx.serialization.json.JsonObject): PendingAnalyticsEvent? {
        if (analyticsRepository == null) return null
        val userId = attachedUserId ?: return null
        val state = _uiState.value
        return Bq9RecommendationOutbox.build(
            userId = userId,
            sessionId = analyticsSessionId,
            eventType = eventType,
            availableMinutes = state.availableTime,
            preferences = state.preferences,
            quest = quest,
            metadata = metadata,
        )
    }

    /**
     * Smart feature (battery-aware context): with low battery and no charger, GPS + weather are
     * not requested again if the last context is recent enough; the last context is reused and
     * the UI says so. [force] (manual refresh, reconnection) always reads a new context.
     */
    fun refreshContext(force: Boolean = false) {
        val recentContext = lastContextReadAtMillis > 0L &&
            System.currentTimeMillis() - lastContextReadAtMillis < BATTERY_SAVER_REUSE_MILLIS
        if (!force && _deviceStatus.value.isLowBattery && recentContext) {
            _contextState.update { it.copy(loading = false, batterySaverActive = true) }
            return
        }

        viewModelScope.launch {
            _contextState.update { it.copy(loading = true, error = null) }
            runCatching { contextManager.readContext() }
                .onSuccess { context ->
                    lastContextReadAtMillis = System.currentTimeMillis()
                    val previous = _contextState.value
                    _contextState.value = ContextUiState(
                        loading = false,
                        context = context,
                        error = null,
                        address = previous.address,
                        addressError = previous.addressError,
                        batterySaverActive = false,
                    )
                    lookupAddress(context.location)
                }
                .onFailure { error ->
                    failureReporter.report(AppModule.CONTEXT, error, null)
                    _contextState.update {
                        it.copy(
                            loading = false,
                            error = if (error.isNetworkFailure()) "weather unavailable offline" else "context unavailable",
                        )
                    }
                }
        }
    }

    /**
     * External service feature: reverse geocoding with Nominatim. Offline, or when the lookup
     * fails, the last known address is kept and an explanatory message is shown instead.
     */
    private fun lookupAddress(location: LocationData?) {
        val provider = addressProvider ?: return
        if (location == null) return
        if (!_deviceStatus.value.isOnline) {
            _contextState.update { it.copy(addressError = ADDRESS_OFFLINE_MESSAGE) }
            return
        }
        viewModelScope.launch {
            val result = runCatching { provider.addressFor(location) }
            val address = result.getOrNull()
            if (address == null) {
                failureReporter.report(AppModule.GEOCODING, result.exceptionOrNull(), "Reverse geocoding returned no address")
            }
            _contextState.update {
                if (address != null) it.copy(address = address, addressError = null)
                else it.copy(addressError = ADDRESS_UNAVAILABLE_MESSAGE)
            }
        }
    }

    /**
     * Context-aware feature: receives connectivity/battery changes. When the phone comes back
     * online, the network-dependent data (catalogue, Smart Picks, context and address) is
     * refreshed automatically instead of waiting for the user.
     */
    fun onDeviceStatusChanged(status: DeviceStatus) {
        val previous = _deviceStatus.value
        _deviceStatus.value = status
        if (!previous.isOnline && status.isOnline) {
            retryNetworkWork()
        }
    }

    /** Manual "Retry" from the offline banner, also used after reconnection. */
    fun retryNetworkWork() {
        // Errors from the outage are stale now; queued progress and photos are retried right away
        // instead of waiting for WorkManager's backoff.
        _uiState.update { if (it.progressSyncing) it else it.copy(progressSyncError = null, progressSyncMessage = null) }
        attachedUserId?.let(retryLocalSync)
        refreshQuestCatalog()
        refreshRecommendations()
        refreshContext(force = true)
    }

    /** Sensor feature: availability of the ambient light sensor on this device. */
    fun setLightSensorAvailable(available: Boolean) {
        _uiState.update {
            it.copy(
                lightSensorAvailable = available,
                autoThemeEnabled = it.autoThemeEnabled && available,
            )
        }
    }

    /** Sensor feature: new dark/light decision from the ambient light sensor. */
    fun onAmbientLightChanged(isDark: Boolean) {
        lastAmbientDark = isDark
        if (_uiState.value.autoThemeEnabled) {
            _uiState.update { it.copy(darkMode = isDark) }
        }
    }

    /** Sensor feature: latest ambient light reading, rounded so the UI only updates on real changes. */
    fun onAmbientLux(lux: Float) {
        val rounded = kotlin.math.round(lux)
        if (_uiState.value.ambientLux != rounded) _uiState.update { it.copy(ambientLux = rounded) }
    }

    /** Sensor feature: turns the automatic (light sensor) theme on or off. */
    fun toggleAutoTheme() {
        _uiState.update { state ->
            if (!state.lightSensorAvailable) return@update state
            val enabled = !state.autoThemeEnabled
            state.copy(
                autoThemeEnabled = enabled,
                darkMode = if (enabled) lastAmbientDark ?: state.darkMode else state.darkMode,
            )
        }
    }

    val categories: List<String> get() = repository.categories()

    fun allQuests(): List<Quest> = repository.allQuests()

    fun selectedQuest(): Quest = repository.questById(_uiState.value.selectedQuestId)

    fun activeQuest(): Quest = repository.questById(_uiState.value.activeQuestId)

    fun activeProgress(): QuestProgress =
        _uiState.value.progressByQuest[_uiState.value.activeQuestId] ?: QuestProgress()

    /** BQ1 (#21): id of the active location-based (Nearby/GPS) quest, or null. */
    fun activeNearbyQuestId(): String? {
        if (!hasActiveQuest()) return null
        val quest = activeQuest()
        val progress = activeProgress()
        if (progress.abandoned || progress.completedSteps.size >= quest.steps.size) return null
        return quest.id.takeIf { quest.location == LocationMode.Gps }
    }

    fun hasActiveQuest(): Boolean {
        val state = _uiState.value
        return state.activeQuestId in state.attemptIdByQuest
    }

    fun recommendations(): List<Quest> {
        val state = _uiState.value

        // BQ8: the backend controls the order of experimental recommendations.
        // Applying context-aware re-ranking here would display a different order
        // than the server's rank_position and corrupt the comparison of arms.
        if (state.remoteRecommendationIds.isNotEmpty()) {
            return Bq8RecommendationPresentation.orderedQuests(
                state.remoteRecommendationIds,
                repository.allQuests(),
            )
        }

        val completedIds = state.progressByQuest
            .filterValues { it.completedSteps.size >= 4 }
            .keys
        val inProgressIds = state.progressByQuest
            .filterValues { it.completedSteps.size in 1..3 && !it.abandoned }
            .keys
        val local = repository.recommendations(
            availableMinutes = state.availableTime,
            preferences = state.preferences,
            skippedIds = state.skippedQuestIds,
            completedIds = completedIds,
            inProgressIds = inProgressIds,
        )
        // Context adaptation remains available for offline/sample fallback.
        return recommendationEngine.apply(local, _contextState.value.context)
    }

    /** BQ8 records an impression only when a current RPC-ranked card is in
     * the visible LazyColumn viewport. Cached results never start a new batch.
     * Multiple recompositions and scrolling back to the card are deduplicated.
     */
    fun bq8RecommendationVisible(questId: String) {
        val state = _uiState.value
        val batch = state.bq8FreshBatchId ?: return
        if (state.screen != AppScreen.Explorer ||
            state.catalogLoading || state.recommendationLoading ||
            questId !in state.remoteRecommendationIds) return
        val quest = repository.allQuests().firstOrNull { it.id == questId } ?: return
        val key = "${batch}:${questId}"
        if (!bq8RecordedExposures.add(key)) return

        val rank = state.bq8RankByQuestId[questId]
            ?: state.remoteRecommendationIds.indexOf(questId) + 1
        val contextMetadata = buildRecommendationContextMetadata(
            context = _contextState.value.context,
            rank = rank,
        )
        trackEvent(
            eventType = "recommendation_shown",
            quest = quest,
            metadata = buildJsonObject {
                contextMetadata.forEach { (field, value) -> put(field, value) }
                put("batch_id", batch)
                put("rank", rank)
                state.bq8Variant?.let { put("variant", it) }
                put("source", "rpc_viewport")
            },
        )
    }


    fun refreshQuestCatalog() {
        if (_uiState.value.catalogLoading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    catalogLoading = true,
                    catalogError = null,
                )
            }

            repository.refresh()
                .onSuccess { count ->
                    _uiState.update {
                        it.copy(
                            catalogLoading = false,
                            catalogSource = when (repository.catalogueOrigin()) {
                                QuestCatalogueOrigin.Remote -> "Supabase · $count quests"
                                QuestCatalogueOrigin.Saved -> "Saved catalogue · $count quests"
                                QuestCatalogueOrigin.Sample -> "Sample catalogue · $count quests"
                            },
                            catalogError = null,
                        )
                    }
                    refreshRecommendations()
                }
                .onFailure { throwable ->
                    failureReporter.report(AppModule.CATALOG, throwable, null)
                    _uiState.update {
                        it.copy(
                            catalogLoading = false,
                            catalogSource = when (repository.catalogueOrigin()) {
                                QuestCatalogueOrigin.Saved -> "Saved catalogue · offline"
                                QuestCatalogueOrigin.Remote -> "Last loaded catalogue · offline"
                                QuestCatalogueOrigin.Sample -> "Sample catalogue · offline"
                            },
                            catalogError = if (throwable.isNetworkFailure()) "offline" else "Remote catalogue unavailable.",
                        )
                    }
                }
        }
    }

    fun refreshRecommendations() {
        val remote = recommendationRepository ?: return
        val state = _uiState.value
        if (state.recommendationLoading) return
        val generation = accountGeneration

        viewModelScope.launch {
            _uiState.update {
                it.copy(recommendationLoading = true, recommendationError = null)
            }

            remote.recommend(
                availableMinutes = state.availableTime,
                preferences = state.preferences,
                excludedQuestIds = state.skippedQuestIds,
                limit = 3,
            )
                .onSuccess { results ->
                    if (generation != accountGeneration) return@onSuccess
                    val ids = results.map { it.questId }
                    val source = (remote as? CachedRecommendationRepository)?.lastSource
                    val isFreshRpc = source == null || source == CachedRecommendationRepository.Source.NETWORK
                    val batchId = if (isFreshRpc && ids.isNotEmpty()) UUID.randomUUID().toString() else null
                    if (batchId != null) bq8RecordedExposures.clear()
                    val serverArm = Bq8RecommendationPresentation.experimentArm(
                        results.map { it.variant }
                    )
                    val serverRanks = results.mapIndexed { index, row ->
                        row.questId to (row.rankPosition?.takeIf { it > 0 } ?: index + 1)
                    }.toMap()
                    _uiState.update {
                        it.copy(
                            recommendationLoading = false,
                            remoteRecommendationIds = ids,
                            bq8FreshBatchId = batchId,
                            bq8Variant = serverArm,
                            bq8RankByQuestId = serverRanks,
                            recommendationSource = when (source) {
                                CachedRecommendationRepository.Source.CACHE -> "Smart Picks · cached · " + ids.size + " quests"
                                CachedRecommendationRepository.Source.STALE_CACHE -> "Smart Picks · saved (offline) · " + ids.size + " quests"
                                else -> "Supabase BQ5 · " + ids.size + " quests"
                            },
                            recommendationError = null,
                        )
                    }
                    // No event here: a successful RPC does not prove a card was seen.
                    // ExplorerScreen records one impression when each card enters its viewport.
                }
                .onFailure { throwable ->
                    if (generation != accountGeneration) return@onFailure
                    failureReporter.report(AppModule.RECOMMENDATIONS, throwable, null)
                    _uiState.update {
                        it.copy(
                            recommendationLoading = false,
                            remoteRecommendationIds = emptyList(),
                            bq8FreshBatchId = null,
                            bq8Variant = null,
                            bq8RankByQuestId = emptyMap(),
                            recommendationSource = "Local fallback",
                            recommendationError = if (throwable.isNetworkFailure()) "offline" else "shared recommendations unavailable",
                        )
                    }
                }
        }
    }
    fun navigate(screen: AppScreen) {
        if (screen != AppScreen.QuestAlternatives) alternativeJob?.cancel()
        if (screen != AppScreen.QuestDetail) openedFromSmartPicksId = null
        _uiState.update {
            it.copy(screen = screen, detailFromHistory = false, questAlternatives = if (screen == AppScreen.QuestAlternatives) it.questAlternatives else null)
        }
    }

    fun openQuestDetail(questId: String, fromSmartPicks: Boolean = false) {
        alternativeJob?.cancel()
        openedFromSmartPicksId = if (fromSmartPicks) questId else null
        _uiState.update { it.copy(selectedQuestId = questId, screen = AppScreen.QuestDetail, detailFromHistory = false, questAlternatives = null) }
    }

    fun openQuestFromHistory(questId: String): Boolean {
        if (repository.allQuests().none { it.id == questId }) return false
        alternativeJob?.cancel()
        openedFromSmartPicksId = null
        _uiState.update { it.copy(selectedQuestId = questId, screen = AppScreen.QuestDetail,
            detailFromHistory = true, questAlternatives = null) }
        return true
    }

    fun acceptQuest(questId: String) {
        alternativeJob?.cancel()
        val quest = repository.questById(questId)
        val currentState = _uiState.value
        val existingProgress = currentState.progressByQuest[questId]
        val previousAttemptFinished = existingProgress?.let { progress ->
            progress.abandoned || progress.completedSteps.size >= quest.steps.size
        } ?: false
        val existingAttemptId = currentState.attemptIdByQuest[questId]
        val createsNewAttempt = existingAttemptId == null || previousAttemptFinished
        // Offline, a new attempt is queued durably and created in Supabase on reconnection.
        // Group quests depend on other players, and without the durable queue there is no way to
        // replay the acceptance, so those still need internet.
        if (createsNewAttempt && progressRepository != null && !_deviceStatus.value.isOnline) {
            val reason = when {
                quest.isGroup -> "Group quests need internet to join. Connect and try again."
                !progressQueueEnabled -> "Connect to the internet before accepting a new quest."
                else -> null
            }
            if (reason != null) {
                _uiState.update { it.copy(progressSyncError = reason, progressSyncMessage = null) }
                return
            }
        }
        val fromRecommendation = isRecommendationAcceptance(
            questId = questId,
            openedFromSmartPicksId = openedFromSmartPicksId,
            currentRemoteRecommendations = currentState.remoteRecommendationIds,
            startsNewAttempt = createsNewAttempt,
        )
        val firstOnboardingRecommendationSource = openedFromSmartPicksId == questId
        openedFromSmartPicksId = null
        val attemptId = if (createsNewAttempt) {
            UUID.randomUUID().toString()
        } else {
            requireNotNull(existingAttemptId)
        }

        _uiState.update { state ->
            val progressMap = if (questId in state.progressByQuest && !previousAttemptFinished) {
                state.progressByQuest
            } else {
                state.progressByQuest + (questId to QuestProgress())
            }

            state.copy(
                activeQuestId = questId,
                progressByQuest = progressMap,
                skippedQuestIds = state.skippedQuestIds - questId,
                attemptIdByQuest = state.attemptIdByQuest + (questId to attemptId),
                screen = if (quest.isGroup) AppScreen.GroupQuest else AppScreen.ActiveQuest,
                progressSyncError = null,
                progressSyncMessage = null,
                questAlternatives = null,
            )
        }

        val acceptedProgress = _uiState.value.progressByQuest[questId] ?: QuestProgress()
        if (createsNewAttempt && progressQueueEnabled) {
            updateJourney({ it.firstRecommendationAccepted(questId, firstOnboardingRecommendationSource, attemptId) })
            val attemptMetadata = buildJsonObject { put("attempt_id", attemptId) }
            val events = listOfNotNull(
                confirmedEvent("quest_accepted", quest, attemptMetadata),
                if (fromRecommendation) confirmedEvent("recommendation_accepted", quest, attemptMetadata) else null,
            )
            persistActiveQuestProgress(
                questId, attemptId, acceptedProgress,
                op = progressOp(PendingProgressOp.ACCEPT, questId, attemptId, confirmedEvents = events),
            )
            showQueuedProgress("Saving accepted quest…")
            return
        }
        persistActiveQuestProgress(questId, attemptId, acceptedProgress)

        if (createsNewAttempt) {
            updateJourney({ it.firstRecommendationAccepted(questId, firstOnboardingRecommendationSource, attemptId) })
            // BQ9 must reflect a quest acceptance confirmed by Supabase, not
            // just an attempted tap while offline or after a server rejection.
            syncProgress(
                message = "Saving accepted quest…",
                onSuccess = {
                    trackEvent("quest_accepted", quest)
                    if (fromRecommendation) {
                        trackEvent("recommendation_accepted", quest)
                    }
                },
            ) {
                progressRepository?.acceptQuest(questId, attemptId)
                    ?: Result.success(Unit)
            }
        }
    }

    fun skipQuest(questId: String) {
        val quest = repository.questById(questId)
        _uiState.update {
            it.copy(
                skippedQuestIds = it.skippedQuestIds + questId,
                remoteRecommendationIds = it.remoteRecommendationIds.filterNot { id -> id == questId },
            )
        }
        trackEvent("recommendation_skipped", quest)
        refreshRecommendations()
    }

    fun toggleDarkMode() {
        // A manual choice overrides the automatic (light sensor) theme.
        _uiState.update { it.copy(darkMode = !it.darkMode, autoThemeEnabled = false) }
    }

    fun setAvailableTime(minutes: Int) {
        val choice = TimeChoice(timeOfDayFromHour(ZonedDateTime.now().hour), minutes)
        _uiState.update {
            it.copy(
                availableTime = minutes,
                preferences = it.preferences.copy(typicalTime = minutes),
                remoteRecommendationIds = emptyList(),
                timeChoices = (it.timeChoices + choice).takeLast(SmartTimeAdvisor.HISTORY_SIZE),
                smartTimeHint = null,
            )
        }
        persistSetupPreferences()
        refreshRecommendations()
    }

    /**
     * Smart feature: when Explorer opens, preselect the time the user usually picks in this part
     * of the day. Applied at most once per part of the day per session so it never fights a
     * choice the user just made.
     */
    fun applySmartAvailableTime() {
        val state = _uiState.value
        val timeOfDay = timeOfDayFromHour(ZonedDateTime.now().hour)
        if (state.smartTimeAppliedFor == timeOfDay) return
        val preferred = SmartTimeAdvisor.preferredMinutes(state.timeChoices, timeOfDay) ?: return
        _uiState.update { it.copy(smartTimeAppliedFor = timeOfDay) }
        if (preferred == state.availableTime) return

        val label = if (preferred == 60) "1 hr+" else "$preferred min"
        _uiState.update {
            it.copy(
                availableTime = preferred,
                remoteRecommendationIds = emptyList(),
                smartTimeHint = "⚡ Smart pick: you usually have $label in the ${timeOfDay.label().lowercase()}",
            )
        }
        refreshRecommendations()
        trackEvent("smart_time_applied", metadata = buildJsonObject {
            put("minutes", preferred)
            put("time_of_day", timeOfDay.name.lowercase())
        })
    }

    fun setCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun nextOnboardingStep() {
        if (localStore != null) {
            if (_uiState.value.onboardingSaving) return
            val preferences = _uiState.value.preferences
            _uiState.update { it.copy(onboardingSaving = true) }
            updateJourney({ it.completeStep(preferences) }, navigate = true)
            return
        }
        _uiState.update { state ->
            if (state.onboardingStep < 3) state.copy(onboardingStep = state.onboardingStep + 1)
            else state.copy(screen = AppScreen.Explorer)
        }
    }

    fun skipOnboarding() {
        if (localStore != null) {
            if (_uiState.value.onboardingSaving) return
            val preferences = _uiState.value.preferences
            _uiState.update { it.copy(onboardingSaving = true) }
            updateJourney({ it.skip(preferences) }, navigate = true)
            return
        }
        _uiState.update { it.copy(preferences = UserPreferences(), screen = AppScreen.Explorer) }
    }

    fun toggleInterest(interest: String) {
        _uiState.update { state ->
            val next = state.preferences.interests.toMutableSet().apply {
                if (!add(interest)) remove(interest)
            }
            state.copy(
                preferences = state.preferences.copy(interests = next),
                remoteRecommendationIds = emptyList(),
            )
        }
        persistSetupPreferences()
        refreshRecommendations()
    }

    private fun recordDifficultyOutcome(outcome: DifficultyOutcome) {
        val before = difficultySuggestion()
        _uiState.update {
            it.copy(difficultyOutcomes = (it.difficultyOutcomes + outcome).takeLast(SmartDifficultyAdvisor.HISTORY_SIZE))
        }
        // BQ13 instrumentation: a new adaptive suggestion was shown to the user.
        val after = difficultySuggestion() ?: return
        if (after.suggested != before?.suggested) {
            trackDifficultyEvent("smart_difficulty_suggested", after)
        }
    }

    /** BQ13 (Adaptive Quest Optimization): events to compare acceptance/completion with and without adaptation. */
    private fun trackDifficultyEvent(eventType: String, suggestion: DifficultySuggestion) {
        val streaks = difficultyStreaks()
        trackEvent(eventType, metadata = buildJsonObject {
            put("bq", "BQ13")
            put("from_difficulty", suggestion.current.name.lowercase())
            put("to_difficulty", suggestion.suggested.name.lowercase())
            put("abandon_streak", streaks.abandons)
            put("completion_streak", streaks.completions)
        })
    }

    /** Smart feature: current difficulty suggestion, or null when there is nothing to suggest. */
    fun difficultySuggestion(): DifficultySuggestion? {
        val state = _uiState.value
        if (state.difficultySuggestionDismissedAt == state.difficultyOutcomes.size) return null
        return SmartDifficultyAdvisor.suggest(state.difficultyOutcomes, state.preferences.difficulty)
    }

    /** Smart feature: current streaks at the preferred difficulty (shown in Profile). */
    fun difficultyStreaks(): DifficultyStreaks {
        val state = _uiState.value
        return SmartDifficultyAdvisor.streaks(state.difficultyOutcomes, state.preferences.difficulty)
    }

    fun acceptDifficultySuggestion() {
        val suggestion = difficultySuggestion() ?: return
        trackDifficultyEvent("smart_difficulty_accepted", suggestion)
        setDifficulty(suggestion.suggested)
    }

    fun dismissDifficultySuggestion() {
        difficultySuggestion()?.let { trackDifficultyEvent("smart_difficulty_dismissed", it) }
        _uiState.update { it.copy(difficultySuggestionDismissedAt = it.difficultyOutcomes.size) }
    }

    fun setDifficulty(difficulty: QuestDifficulty) {
        _uiState.update { it.copy(preferences = it.preferences.copy(difficulty = difficulty)) }
        persistSetupPreferences()
    }

    fun setTypicalTime(minutes: Int) {
        _uiState.update { state ->
            state.copy(
                availableTime = minutes,
                preferences = state.preferences.copy(typicalTime = minutes),
            )
        }
        persistSetupPreferences()
    }

    fun setBudget(amount: Int) {
        _uiState.update { it.copy(preferences = it.preferences.copy(budgetMax = amount)) }
        persistSetupPreferences()
    }

    fun setSocialLevel(level: SocialLevel) {
        _uiState.update {
            it.copy(
                preferences = it.preferences.copy(socialLevel = level),
                remoteRecommendationIds = emptyList(),
            )
        }
        persistSetupPreferences()
        refreshRecommendations()
    }

    fun setLocationMode(mode: String) {
        val normalized = mode.lowercase()
        _uiState.update {
            it.copy(
                preferences = it.preferences.copy(locationMode = normalized),
                remoteRecommendationIds = emptyList(),
            )
        }
        persistSetupPreferences()

        val eventType = when (normalized) {
            "anywhere" -> "location_independent_mode_selected"
            "gps" -> "location_based_mode_selected"
            else -> "location_mode_all_selected"
        }

        viewModelScope.launch {
            val context = runCatching { contextManager.readContext() }
                .getOrElse { _contextState.value.context }

            _contextState.update {
                it.copy(context = context, loading = false)
            }

            trackEvent(
                eventType = eventType,
                metadata = buildJsonObject {
                    put("location_mode", normalized)
                    put("time_of_day", context.timeOfDay.name.lowercase())
                    put(
                        "weather_condition",
                        context.weather?.condition?.name?.lowercase() ?: "unknown",
                    )
                },
            )

            refreshRecommendations()
        }
    }


    /**
     * Whether [stepIndex] of the active quest can be marked complete. Finishing the quest also
     * needs every photo proof confirmed by Supabase (when photo uploads are available).
     */
    fun stepCompletionDecision(stepIndex: Int): StepCompletionDecision {
        val state = _uiState.value
        val quest = repository.questById(state.activeQuestId)
        val step = quest.steps.getOrNull(stepIndex) ?: return StepCompletionDecision(false)
        return QuestStepCompletionPolicy.evaluate(
            stepIndex = stepIndex,
            step = step,
            progress = state.progressByQuest[quest.id] ?: QuestProgress(),
            steps = quest.steps,
            requireUploadedProofToFinish = photoProofRepository != null,
            isOnline = _deviceStatus.value.isOnline,
        )
    }

    /** Open (not finished, not abandoned) attempt progress for [questId], or null. */
    fun openAttemptProgress(questId: String): QuestProgress? {
        val state = _uiState.value
        if (questId !in state.attemptIdByQuest) return null
        val quest = repository.questById(questId)
        val progress = state.progressByQuest[questId] ?: QuestProgress()
        if (progress.abandoned || progress.completedSteps.size >= quest.steps.size) return null
        return progress
    }

    /** True when the quest can be accepted right now, also offline when the change can be queued. */
    fun canAcceptQuest(quest: Quest): Boolean =
        _deviceStatus.value.isOnline || progressRepository == null || (progressQueueEnabled && !quest.isGroup)

    /** Explore -> the quest already in progress: continue the same attempt instead of a new one. */
    fun resumeQuest(questId: String) {
        if (openAttemptProgress(questId) == null) return
        alternativeJob?.cancel()
        openedFromSmartPicksId = null
        val quest = repository.questById(questId)
        _uiState.update {
            it.copy(
                activeQuestId = questId,
                screen = if (quest.isGroup) AppScreen.GroupQuest else AppScreen.ActiveQuest,
                detailFromHistory = false,
                questAlternatives = null,
                progressSyncError = null,
            )
        }
    }

    fun completeCurrentStep() {
        val state = _uiState.value
        val questId = state.activeQuestId
        val quest = repository.questById(questId)
        val current = state.progressByQuest[questId] ?: QuestProgress()
        if (quest.steps.getOrNull(current.currentStep) == null) return
        val completionDecision = stepCompletionDecision(current.currentStep)
        if (!completionDecision.allowed) {
            _uiState.update {
                it.copy(
                    progressSyncError = completionDecision.reason,
                    progressSyncMessage = null,
                )
            }
            return
        }

        val nextCompleted = current.completedSteps + current.currentStep
        val completed = nextCompleted.size >= quest.steps.size
        val nextStep = if (current.currentStep < quest.steps.lastIndex) {
            current.currentStep + 1
        } else {
            current.currentStep
        }

        _uiState.update {
            it.copy(
                progressByQuest = it.progressByQuest + (
                    questId to current.copy(
                        currentStep = nextStep,
                        completedSteps = nextCompleted,
                    )
                ),
                progressSyncError = null,
            )
        }

        if (current.completedSteps.isEmpty() && nextCompleted.isNotEmpty()) {
            trackEvent("quest_started", quest)
        }
        if (completed) {
            trackEvent("quest_completed", quest)
            recordDifficultyOutcome(DifficultyOutcome(quest.difficulty, completed = true))
        }

        val attemptId = _uiState.value.attemptIdByQuest[questId] ?: return
        val op = progressOp(PendingProgressOp.PROGRESS, questId, attemptId, nextStep, nextCompleted, completed)
        persistActiveQuestProgress(questId, attemptId, _uiState.value.progressByQuest.getValue(questId), closed = completed, op = op)
        if (op != null) {
            showQueuedProgress(if (completed) "Saving completed quest…" else "Saving progress…")
            return
        }
        syncProgress(if (completed) "Saving completed quest…" else "Saving progress…") {
            progressRepository?.updateProgress(
                attemptId = attemptId,
                currentStep = nextStep,
                completedSteps = nextCompleted,
                completed = completed,
            ) ?: Result.success(Unit)
        }
    }

    fun jumpToStep(step: Int) {
        val state = _uiState.value
        val questId = state.activeQuestId
        val current = state.progressByQuest[questId] ?: QuestProgress()
        val updated = current.copy(currentStep = step.coerceIn(0, 3))
        _uiState.update {
            it.copy(progressByQuest = it.progressByQuest + (questId to updated))
        }
        state.attemptIdByQuest[questId]?.let { attemptId ->
            persistActiveQuestProgress(questId, attemptId, updated)
        }
    }

    fun markPhotoProofCaptured(stepIndex: Int, localPath: String, expectedAttemptId: String? = null, expectedUserId: String? = null) {
        val state = _uiState.value
        val questId = state.activeQuestId
        if (expectedAttemptId != null && state.attemptIdByQuest[questId] != expectedAttemptId) return
        if (expectedUserId != null && attachedUserId != expectedUserId) return
        val quest = repository.questById(questId)
        val current = state.progressByQuest[questId] ?: QuestProgress()
        if (localStore != null) {
            val userId = attachedUserId ?: return
            val attemptId = state.attemptIdByQuest[questId] ?: return
            if (quest.steps.getOrNull(stepIndex)?.requiresPhoto != true) return
            viewModelScope.launch {
                try {
                    val saved = photoMutex.withLock { withContext(localIoDispatcher) {
                        val file = File(localPath)
                        check(file.isFile && file.length() > 0)
                        val entry = StoredPhotoProof(UUID.randomUUID().toString(), userId, attemptId, questId, stepIndex, localPath, java.time.Instant.now().toString(), category = quest.category)
                        localStore.update { old -> old.copy(
                            photos = old.photos + entry,
                            events = old.events + photoEvent(entry, "photo_proof_captured"),
                            photoQuests = old.photoQuests + (attemptId to PhotoQuestSnapshot(userId, questId, attemptId, current.currentStep, current.completedSteps)),
                        ) }
                    } }
                    if (attachedUserId == userId) applyStoredPhotos(saved, userId)
                    scheduleLocalSync(userId)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { reportPhotoProofError(stepIndex, "Could not save the captured photo. Please try again.") }
            }
            return
        }
        _uiState.update {
            it.copy(
                progressByQuest = it.progressByQuest + (
                    questId to current.copy(
                        localPhotoProofByStep = current.localPhotoProofByStep + (stepIndex to localPath),
                        uploadedPhotoProofByStep = current.uploadedPhotoProofByStep - stepIndex,
                        photoProofErrorByStep = current.photoProofErrorByStep - stepIndex,
                    )
                )
            )
        }

        val attemptId = state.attemptIdByQuest[questId]
        val photoFile = File(localPath)
        trackEvent(
            eventType = "photo_proof_captured",
            quest = quest,
            metadata = buildJsonObject {
                put("step_index", stepIndex)
                put("byte_count", photoFile.length())
                put("verification_type", "photo")
                put("stored_locally", true)
                if (attemptId != null) put("attempt_id", attemptId)
            },
        )

        val remote = photoProofRepository
        if (attemptId != null && remote != null) {
            uploadPhotoProof(
                quest = quest,
                attemptId = attemptId,
                stepIndex = stepIndex,
                photoFile = photoFile,
                remote = remote,
            )
        }
    }

    fun retryPhotoProofUpload(stepIndex: Int) {
        if (localStore != null) { retryPendingPhotoUploads(); return }
        val state = _uiState.value
        val questId = state.activeQuestId
        val attemptId = state.attemptIdByQuest[questId] ?: return
        val localPath = state.progressByQuest[questId]
            ?.localPhotoProofByStep
            ?.get(stepIndex)
            ?: return
        val remote = photoProofRepository ?: return

        uploadPhotoProof(
            quest = repository.questById(questId),
            attemptId = attemptId,
            stepIndex = stepIndex,
            photoFile = File(localPath),
            remote = remote,
        )
    }

    fun reportPhotoProofError(stepIndex: Int, message: String) {
        val state = _uiState.value
        val questId = state.activeQuestId
        val current = state.progressByQuest[questId] ?: QuestProgress()
        _uiState.update {
            it.copy(
                progressByQuest = it.progressByQuest + (
                    questId to current.copy(
                        photoProofErrorByStep = current.photoProofErrorByStep + (stepIndex to message)
                    )
                )
            )
        }
    }

    private fun uploadPhotoProof(
        quest: Quest,
        attemptId: String,
        stepIndex: Int,
        photoFile: File,
        remote: PhotoProofRepository,
    ) {
        val questId = quest.id
        _uiState.update { state ->
            val current = state.progressByQuest[questId] ?: QuestProgress()
            state.copy(
                progressByQuest = state.progressByQuest + (
                    questId to current.copy(
                        photoProofUploadingSteps = current.photoProofUploadingSteps + stepIndex,
                        photoProofErrorByStep = current.photoProofErrorByStep - stepIndex,
                    )
                )
            )
        }

        viewModelScope.launch {
            remote.upload(
                attemptId = attemptId,
                questId = questId,
                stepIndex = stepIndex,
                photoFile = photoFile,
            )
                .onSuccess { upload ->
                    _uiState.update { state ->
                        val current = state.progressByQuest[questId] ?: QuestProgress()
                        state.copy(
                            progressByQuest = state.progressByQuest + (
                                questId to current.copy(
                                    uploadedPhotoProofByStep = current.uploadedPhotoProofByStep +
                                        (stepIndex to upload.storagePath),
                                    photoProofUploadingSteps = current.photoProofUploadingSteps - stepIndex,
                                    photoProofErrorByStep = current.photoProofErrorByStep - stepIndex,
                                )
                            )
                        )
                    }
                    trackEvent(
                        eventType = "photo_proof_uploaded",
                        quest = quest,
                        metadata = buildJsonObject {
                            put("attempt_id", attemptId)
                            put("step_index", stepIndex)
                            put("byte_count", upload.byteCount)
                            put("storage_path", upload.storagePath)
                            put("verification_type", "photo")
                        },
                    )
                }
                .onFailure { uploadError ->
                    failureReporter.report(AppModule.PHOTO_PROOF, uploadError, null)
                    _uiState.update { state ->
                        val current = state.progressByQuest[questId] ?: QuestProgress()
                        state.copy(
                            progressByQuest = state.progressByQuest + (
                                questId to current.copy(
                                    photoProofUploadingSteps = current.photoProofUploadingSteps - stepIndex,
                                    photoProofErrorByStep = current.photoProofErrorByStep + (
                                        stepIndex to "Photo captured locally, but upload failed. Retry when connected."
                                    ),
                                )
                            )
                        )
                    }
                    trackEvent(
                        eventType = "photo_proof_upload_failed",
                        quest = quest,
                        metadata = buildJsonObject {
                            put("attempt_id", attemptId)
                            put("step_index", stepIndex)
                            put("verification_type", "photo")
                        },
                    )
                }
        }
    }

    fun saveAndExit() {
        val state = _uiState.value
        val questId = state.activeQuestId
        val current = state.progressByQuest[questId] ?: QuestProgress()

        _uiState.update {
            it.copy(
                progressByQuest = it.progressByQuest + (
                    questId to current.copy(abandonReason = null)
                ),
                screen = AppScreen.Explorer,
                progressSyncError = null,
            )
        }

        trackEvent(
            eventType = "quest_progress_saved",
            quest = repository.questById(questId),
            metadata = buildJsonObject {
                state.attemptIdByQuest[questId]?.let { put("attempt_id", it) }
                put("current_step_index", current.currentStep)
                put("completed_step_count", current.completedSteps.size)
            },
        )

        val attemptId = state.attemptIdByQuest[questId] ?: return
        val op = progressOp(PendingProgressOp.SAVE, questId, attemptId, current.currentStep, current.completedSteps)
        persistActiveQuestProgress(questId, attemptId, current, op = op)
        if (op != null) {
            showQueuedProgress("Saving quest progress…")
            return
        }
        syncProgress("Saving quest progress…") {
            progressRepository?.saveProgress(
                attemptId = attemptId,
                currentStep = current.currentStep,
                completedSteps = current.completedSteps,
                reason = null,
            ) ?: Result.success(Unit)
        }
    }

    fun startAnotherQuest(reason: AbandonmentReason) {
        val state = _uiState.value
        val questId = state.activeQuestId
        val quest = repository.questById(questId)
        val current = state.progressByQuest[questId] ?: QuestProgress()
        val attemptId = state.attemptIdByQuest[questId] ?: return
        if (current.abandoned || current.completedSteps.size >= quest.steps.size) return
        val evidence = buildBq6AbandonmentEvidence(reason, quest, current)
        val abandonOp = progressOp(
            PendingProgressOp.ABANDON, questId, attemptId,
            current.currentStep, current.completedSteps, reason = reason.code,
        )
        persistActiveQuestProgress(questId, attemptId, current, closed = true, op = abandonOp)

        recordDifficultyOutcome(DifficultyOutcome(quest.difficulty, completed = false, reason = reason))
        _uiState.update {
            it.copy(
                progressByQuest = it.progressByQuest + (
                    questId to current.copy(
                        abandoned = true,
                        abandonReason = reason,
                    )
                ),
                skippedQuestIds = it.skippedQuestIds + questId,
                attemptIdByQuest = it.attemptIdByQuest - questId,
                screen = AppScreen.QuestAlternatives,
                questAlternatives = QuestAlternativesState(
                    sourceQuestId = questId,
                    sourceAttemptId = attemptId,
                    reason = reason,
                    simulatedContext = alternativeContextSimulated,
                ),
                progressSyncError = null,
            )
        }

        trackEvent(
            eventType = "quest_abandoned",
            quest = quest,
            metadata = buildJsonObject {
                put("schema_version", 1)
                put("reason", evidence.reasonCode)
                put("reason_code", evidence.reasonCode)
                put("reason_label", evidence.reasonLabel)
                put("quest_duration_minutes", evidence.questDurationMinutes)
                put("estimated_cost", evidence.estimatedCost)
                put("quest_distance_label", evidence.questDistanceLabel)
                evidence.questDistanceMeters?.let { put("quest_distance_meters", it) }
                put("quest_distance_band", evidence.questDistanceBand)
                put("current_step_index", evidence.currentStepIndex)
                put("completed_step_count", evidence.completedStepCount)
                put("total_step_count", evidence.totalStepCount)
                put("progress_percent", evidence.progressPercent)
                put("had_photo_proof", evidence.hadPhotoProof)
                put("uploaded_photo_proof_count", evidence.uploadedPhotoProofCount)
                put("attempt_id", attemptId)
            },
        )

        if (abandonOp != null) {
            showQueuedProgress("Saving abandonment…")
        } else {
            syncProgress("Saving abandonment…") {
                progressRepository?.abandonQuest(
                    attemptId = attemptId,
                    currentStep = current.currentStep,
                    completedSteps = current.completedSteps,
                    reason = reason.code,
                ) ?: Result.success(Unit)
            }
        }
        refreshQuestAlternatives()
    }

    fun refreshQuestAlternatives() {
        val state = _uiState.value
        val request = state.questAlternatives ?: return
        if (state.screen != AppScreen.QuestAlternatives) return
        alternativeJob?.cancel()
        _uiState.update { it.copy(questAlternatives = request.copy(loading = true)) }
        alternativeJob = viewModelScope.launch {
            val freshContext = try {
                withTimeoutOrNull(8_000) { alternativeContextReader() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            ensureActive()
            val context = freshContext ?: UserContext(
                timeOfDay = timeOfDayFromHour(ZonedDateTime.now().hour),
            )
            val suggestions = alternativeRecommender.recommend(
                catalogue = repository.allQuests(),
                abandonedQuest = repository.questById(request.sourceQuestId),
                reason = request.reason,
                context = context,
                availableMinutes = state.availableTime,
                preferences = state.preferences,
                excludedIds = state.skippedQuestIds + state.progressByQuest.keys + state.attemptIdByQuest.keys,
            )
            val currentState = _uiState.value
            if (currentState.screen != AppScreen.QuestAlternatives ||
                currentState.questAlternatives?.sourceAttemptId != request.sourceAttemptId) return@launch
            val result = request.copy(loading = false, suggestions = suggestions, context = context)
            _uiState.update { it.copy(questAlternatives = result) }
            suggestions.forEachIndexed { index, alternative ->
                trackAlternativeEvent("quest_alternative_shown", result, alternative, index + 1)
            }
        }
    }

    fun openQuestAlternative(questId: String) {
        val result = _uiState.value.questAlternatives ?: return
        if (result.loading || _uiState.value.screen != AppScreen.QuestAlternatives) return
        val index = result.suggestions.indexOfFirst { it.quest.id == questId }
        if (index < 0) return
        trackAlternativeEvent("quest_alternative_selected", result, result.suggestions[index], index + 1)
        openQuestDetail(questId)
    }

    fun dismissQuestAlternatives() = navigate(AppScreen.Explorer)

    private fun trackAlternativeEvent(
        eventType: String,
        result: QuestAlternativesState,
        alternative: QuestAlternative,
        rank: Int,
    ) {
        trackEvent(eventType, alternative.quest, buildJsonObject {
            put("algorithm_version", "lex_alternatives_v1")
            put("source_quest_id", result.sourceQuestId)
            put("source_attempt_id", result.sourceAttemptId)
            put("reason_code", result.reason.code)
            put("rank", rank)
            put("score", alternative.score)
            put("applied_rules", alternative.ruleCodes.joinToString(","))
            put("time_of_day", result.context?.timeOfDay?.name?.lowercase() ?: "unknown")
            put("weather_condition", result.context?.weather?.condition?.name?.lowercase() ?: "unknown")
            put("context_source", if (result.simulatedContext) "simulated_debug" else "device")
        })
    }

    fun submitRating(stars: Int, tags: Set<String>) {
        val state = _uiState.value
        val questId = state.activeQuestId

        _uiState.update {
            it.copy(
                ratingsByQuest = it.ratingsByQuest + (
                    questId to QuestRating(stars, tags)
                ),
                attemptIdByQuest = it.attemptIdByQuest - questId,
                screen = AppScreen.Explorer,
                progressSyncError = null,
            )
        }

        val attemptId = state.attemptIdByQuest[questId] ?: return
        val op = progressOp(PendingProgressOp.RATE, questId, attemptId, stars = stars, tags = tags)
        persistActiveQuestProgress(
            questId = questId,
            attemptId = attemptId,
            progress = state.progressByQuest[questId] ?: QuestProgress(),
            closed = true,
            op = op,
        )
        if (op != null) {
            showQueuedProgress("Saving rating…")
            return
        }
        syncProgress("Saving rating…") {
            progressRepository?.rateQuest(
                attemptId = attemptId,
                stars = stars,
                tags = tags,
            ) ?: Result.success(Unit)
        }
    }

    fun recordGroupChallengeUnconfirmed(accountId: String?, action: com.sidequests.app.data.group.GroupAction) {
        if (accountId == null || accountId != attachedUserId) return
        trackEvent("group_challenge_client_unconfirmed", metadata = buildJsonObject {
            put("operation", action.operation)
            put("request_id", action.requestId)
        })
    }

    private fun trackEvent(
        eventType: String,
        quest: Quest? = null,
        metadata: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    ) {
        val analytics = analyticsRepository ?: return
        val state = _uiState.value
        val userId = attachedUserId
        val store = localStore
        // Capture the interaction time before asynchronous file I/O begins.
        val eventOccurredAt = java.time.Instant.now().toString()

        viewModelScope.launch {
            // Recommendation events support BQ9's secondary funnel. Queue them
            // before delivery so an offline request or process restart cannot
            // silently discard a shown/accepted/skipped interaction.
            if (Bq9RecommendationOutbox.supports(eventType) && userId != null && store != null) {
                try {
                    withContext(localIoDispatcher) {
                        Bq9RecommendationOutbox.enqueue(
                            store = store,
                            userId = userId,
                            sessionId = analyticsSessionId,
                            eventType = eventType,
                            availableMinutes = state.availableTime,
                            preferences = state.preferences,
                            quest = quest,
                            metadata = metadata,
                            occurredAt = eventOccurredAt,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // If local storage is unavailable, preserve the previous
                    // direct-send behavior rather than blocking app navigation.
                    analytics.track(
                        sessionId = analyticsSessionId,
                        eventType = eventType,
                        availableMinutes = state.availableTime,
                        preferences = state.preferences,
                        quest = quest,
                        metadata = metadata,
                    )
                    return@launch
                }
                // Once persisted, a scheduler error must not trigger a second
                // direct send. It will be rescheduled when this account resumes.
                try {
                    scheduleLocalSync(userId)
                } catch (_: Exception) { /* safely retained on disk */ }
                return@launch
            }
            analytics.track(
                sessionId = analyticsSessionId,
                eventType = eventType,
                availableMinutes = state.availableTime,
                preferences = state.preferences,
                quest = quest,
                metadata = metadata,
            )
        }
    }

    private fun syncProgress(
        message: String,
        onSuccess: (() -> Unit)? = null,
        operation: suspend () -> Result<Unit>,
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    progressSyncing = true,
                    progressSyncError = null,
                    progressSyncMessage = message,
                )
            }

            operation()
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            progressSyncing = false,
                            progressSyncError = null,
                            progressSyncMessage = "Progress synced with Supabase",
                        )
                    }
                    onSuccess?.invoke()
                }
                .onFailure { throwable ->
                    failureReporter.report(AppModule.QUEST_PROGRESS, throwable, null)
                    _uiState.update {
                        it.copy(
                            progressSyncing = false,
                            progressSyncError = if (throwable.isNetworkFailure()) {
                                "No internet connection. Your progress is saved on this device."
                            } else "Progress could not be synced. Please try again.",
                            progressSyncMessage = "Saved locally; remote sync failed",
                        )
                    }
                }
        }
    }

    fun showContextualNotification() {
        _uiState.update {
            if (it.contextualNotificationShown) it
            else it.copy(notificationQuestId = "sponsored-climbing", contextualNotificationShown = true)
        }
    }

    fun dismissNotification() {
        _uiState.update { it.copy(notificationQuestId = null) }
    }

    fun openNotificationQuest() {
        val questId = _uiState.value.notificationQuestId ?: return
        _uiState.update {
            it.copy(
                selectedQuestId = questId,
                notificationQuestId = null,
                screen = AppScreen.QuestDetail,
            )
        }
    }

    private companion object {
        /** Low battery: reuse the last context for up to 15 minutes before reading GPS again. */
        const val BATTERY_SAVER_REUSE_MILLIS = 15 * 60 * 1000L
        const val ADDRESS_OFFLINE_MESSAGE = "Address unavailable offline"
        const val ADDRESS_UNAVAILABLE_MESSAGE = "Address unavailable right now"
        const val QUEUED_OFFLINE_MESSAGE = "Saved on this device. It will sync when you're back online."
    }
}
