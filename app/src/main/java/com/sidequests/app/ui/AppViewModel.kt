package com.sidequests.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sidequests.app.BuildConfig
import com.sidequests.app.context.ContextAwareRecommendationEngine
import com.sidequests.app.context.ContextManager
import com.sidequests.app.context.ContextUiState
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
import com.sidequests.app.data.analytics.buildRecommendationContextMetadata
import com.sidequests.app.data.analytics.buildBq6AbandonmentEvidence
import com.sidequests.app.data.analytics.SupabaseAnalyticsRepository
import com.sidequests.app.data.photo.PhotoProofRepository
import com.sidequests.app.data.photo.SupabasePhotoProofRepository
import com.sidequests.app.data.recommendation.RecommendationRepository
import com.sidequests.app.data.recommendation.SupabaseRecommendationRepository
import com.sidequests.app.model.AppScreen
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
) : ViewModel() {

    private val analyticsSessionId = UUID.randomUUID().toString()
    private val recommendationEngine = ContextAwareRecommendationEngine()
    private val alternativeRecommender = QuestAlternativeRecommender()
    private var alternativeJob: Job? = null
    private val _uiState = MutableStateFlow(SidequestsUiState())
    private val _contextState = MutableStateFlow(ContextUiState())
    val uiState: StateFlow<SidequestsUiState> = _uiState.asStateFlow()
    val contextState: StateFlow<ContextUiState> = _contextState.asStateFlow()

    init {
        refreshContext()
        refreshQuestCatalog()
    }

    fun refreshContext() {
        viewModelScope.launch {
            _contextState.update { it.copy(loading = true, error = null) }
            runCatching { contextManager.readContext() }
                .onSuccess { context ->
                    _contextState.value = ContextUiState(
                        loading = false,
                        context = context,
                        error = null,
                    )
                }
                .onFailure { error ->
                    _contextState.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "Context unavailable",
                        )
                    }
                }
        }
    }

    val categories: List<String> get() = repository.categories()

    fun allQuests(): List<Quest> = repository.allQuests()

    fun selectedQuest(): Quest = repository.questById(_uiState.value.selectedQuestId)

    fun activeQuest(): Quest = repository.questById(_uiState.value.activeQuestId)

    fun activeProgress(): QuestProgress =
        _uiState.value.progressByQuest[_uiState.value.activeQuestId] ?: QuestProgress()

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
        _uiState.update {
            it.copy(screen = screen, questAlternatives = if (screen == AppScreen.QuestAlternatives) it.questAlternatives else null)
        }
    }

    fun openQuestDetail(questId: String) {
        alternativeJob?.cancel()
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
            syncProgress("Saving accepted quest…") {
                progressRepository?.acceptQuest(questId, attemptId)
                    ?: Result.success(Unit)
            }
            trackEvent("quest_accepted", quest)
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
        _uiState.update { it.copy(darkMode = !it.darkMode) }
    }

    fun setAvailableTime(minutes: Int) {
        _uiState.update { it.copy(availableTime = minutes, remoteRecommendationIds = emptyList()) }
        refreshRecommendations()
    }

    fun setCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun nextOnboardingStep() {
        _uiState.update { state ->
            if (state.onboardingStep < 3) state.copy(onboardingStep = state.onboardingStep + 1)
            else state.copy(screen = AppScreen.Explorer)
        }
    }

    fun skipOnboarding() {
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
        refreshRecommendations()
    }

    fun setDifficulty(difficulty: QuestDifficulty) {
        _uiState.update { it.copy(preferences = it.preferences.copy(difficulty = difficulty)) }
    }

    fun setTypicalTime(minutes: Int) {
        _uiState.update { state ->
            state.copy(
                availableTime = minutes,
                preferences = state.preferences.copy(typicalTime = minutes),
            )
        }
    }

    fun setBudget(amount: Int) {
        _uiState.update { it.copy(preferences = it.preferences.copy(budgetMax = amount)) }
    }

    fun setSocialLevel(level: SocialLevel) {
        _uiState.update {
            it.copy(
                preferences = it.preferences.copy(socialLevel = level),
                remoteRecommendationIds = emptyList(),
            )
        }
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
        }

        val attemptId = _uiState.value.attemptIdByQuest[questId] ?: return
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

    fun markPhotoProofCaptured(stepIndex: Int, localPath: String) {
        val state = _uiState.value
        val questId = state.activeQuestId
        val quest = repository.questById(questId)
        val current = state.progressByQuest[questId] ?: QuestProgress()
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
                .onFailure {
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

        viewModelScope.launch {
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
                }
                .onFailure { throwable ->
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
}
