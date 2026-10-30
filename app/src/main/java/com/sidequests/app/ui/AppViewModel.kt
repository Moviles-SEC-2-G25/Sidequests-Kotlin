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
import com.sidequests.app.data.photo.photoEvent
import com.sidequests.app.data.recommendation.RecommendationRepository
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
        if (SupabaseProvider.isConfigured) SupabaseRecommendationRepository(SupabaseProvider.client)
        else null,
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
    /** BQ1 (#22): non-fatal module failures are reported here (no-op by default/in tests). */
    private val failureReporter: FailureReporter = FailureReporter.NONE,
) : ViewModel() {

    private val analyticsSessionId = UUID.randomUUID().toString()
    private val recommendationEngine = ContextAwareRecommendationEngine()
    private val alternativeRecommender = QuestAlternativeRecommender()
    private var alternativeJob: Job? = null
    private var openedFromSmartPicksId: String? = null
    private var attachedUserId: String? = null
    private var accountGeneration = 0
    private var photoObserver: Job? = null
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
        attachedUserId = null
        accountGeneration += 1
        photoObserver?.cancel()
        alternativeJob?.cancel()
        _uiState.value = freshAccountState()
    }

    fun attachAccount(userId: String) {
        if (attachedUserId == userId && _uiState.value.accountUserId == userId && _uiState.value.localStateError == null) return
        attachedUserId = userId
        accountGeneration += 1
        photoObserver?.cancel()
        _uiState.value = freshAccountState()
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
            state.copy(progressByQuest = progress, pendingPhotoUploads = photos.count { it.status in setOf("pending", "uploading") })
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
        if (_uiState.value.screen == AppScreen.Onboarding) {
            val preferences = _uiState.value.preferences
            updateJourney({ it.savePreferences(preferences) }, schedule = false)
        }
    }

    private fun persistPhotoProgress(questId: String, attemptId: String, progress: QuestProgress, closed: Boolean = false) {
        val store = localStore ?: return
        val userId = attachedUserId ?: return
        viewModelScope.launch {
            try {
                photoMutex.withLock {
                    withContext(localIoDispatcher) {
                        store.update { saved ->
                            if (saved.photos.none { it.attemptId == attemptId && it.userId == userId }) saved
                            else saved.copy(photoQuests = saved.photoQuests + (attemptId to PhotoQuestSnapshot(userId, questId, attemptId, progress.currentStep, progress.completedSteps, closed)))
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { _uiState.update { it.copy(localStateError = "Photo progress could not be saved on this device. Please try again.") } }
        }
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
                            error = error.message ?: "Context unavailable",
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

        val base = if (state.remoteRecommendationIds.isNotEmpty()) {
            state.remoteRecommendationIds.mapNotNull { id ->
                repository.allQuests().firstOrNull { it.id == id }
            }
        } else {
            val completedIds = state.progressByQuest
                .filterValues { it.completedSteps.size >= 4 }
                .keys
            val inProgressIds = state.progressByQuest
                .filterValues { it.completedSteps.size in 1..3 && !it.abandoned }
                .keys

            repository.recommendations(
                availableMinutes = state.availableTime,
                preferences = state.preferences,
                skippedIds = state.skippedQuestIds,
                completedIds = completedIds,
                inProgressIds = inProgressIds,
            )
        }

        return recommendationEngine.apply(base, _contextState.value.context)
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
                            catalogError = throwable.message ?: "Remote catalogue unavailable.",
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
                    _uiState.update {
                        it.copy(
                            recommendationLoading = false,
                            remoteRecommendationIds = ids,
                            recommendationSource = "Supabase BQ5 · " + ids.size + " quests",
                            recommendationError = null,
                        )
                    }

                    val contextSnapshot = _contextState.value.context
                    ids.forEachIndexed { index, id ->
                        repository.allQuests().firstOrNull { it.id == id }?.let { quest ->
                            trackEvent(
                                eventType = "recommendation_shown",
                                quest = quest,
                                metadata = buildRecommendationContextMetadata(
                                    context = contextSnapshot,
                                    rank = index + 1,
                                ),
                            )
                        }
                    }
                }
                .onFailure { throwable ->
                    if (generation != accountGeneration) return@onFailure
                    failureReporter.report(AppModule.RECOMMENDATIONS, throwable, null)
                    _uiState.update {
                        it.copy(
                            recommendationLoading = false,
                            remoteRecommendationIds = emptyList(),
                            recommendationSource = "Local fallback",
                            recommendationError = throwable.message ?: "Shared recommendation unavailable.",
                        )
                    }
                }
        }
    }
    fun navigate(screen: AppScreen) {
        if (screen != AppScreen.QuestAlternatives) alternativeJob?.cancel()
        if (screen != AppScreen.QuestDetail) openedFromSmartPicksId = null
        _uiState.update {
            it.copy(screen = screen, questAlternatives = if (screen == AppScreen.QuestAlternatives) it.questAlternatives else null)
        }
    }

    fun openQuestDetail(questId: String, fromSmartPicks: Boolean = false) {
        alternativeJob?.cancel()
        openedFromSmartPicksId = if (fromSmartPicks) questId else null
        _uiState.update { it.copy(selectedQuestId = questId, screen = AppScreen.QuestDetail, questAlternatives = null) }
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
                remoteRecommendationIds = emptyList(),
                timeChoices = (it.timeChoices + choice).takeLast(SmartTimeAdvisor.HISTORY_SIZE),
                smartTimeHint = null,
            )
        }
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


    fun completeCurrentStep() {
        val state = _uiState.value
        val questId = state.activeQuestId
        val quest = repository.questById(questId)
        val current = state.progressByQuest[questId] ?: QuestProgress()
        val currentStep = quest.steps.getOrNull(current.currentStep) ?: return
        val completionDecision = QuestStepCompletionPolicy.evaluate(
            stepIndex = current.currentStep,
            step = currentStep,
            progress = current,
        )
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
        persistPhotoProgress(questId, attemptId, _uiState.value.progressByQuest.getValue(questId), closed = completed)
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
        _uiState.update {
            it.copy(progressByQuest = it.progressByQuest + (questId to current.copy(currentStep = step.coerceIn(0, 3))))
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
        persistPhotoProgress(questId, attemptId, current)
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
        persistPhotoProgress(questId, attemptId, current, closed = true)

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

        syncProgress("Saving abandonment…") {
            progressRepository?.abandonQuest(
                attemptId = attemptId,
                currentStep = current.currentStep,
                completedSteps = current.completedSteps,
                reason = reason.code,
            ) ?: Result.success(Unit)
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
        syncProgress("Saving rating…") {
            progressRepository?.rateQuest(
                attemptId = attemptId,
                stars = stars,
                tags = tags,
            ) ?: Result.success(Unit)
        }
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
                            progressSyncError = throwable.message ?: "Progress could not be synced.",
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
    }
}
