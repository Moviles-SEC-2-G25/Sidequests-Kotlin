package com.sidequests.app.model

enum class QuestDifficulty { Easy, Medium, Hard }
enum class LocationMode { Gps, Anywhere }
enum class SocialLevel { Solo, Social, Group }

data class QuestStep(
    val title: String,
    val description: String,
    val requiresPhoto: Boolean = false,
)

data class GroupMember(
    val name: String,
    val avatar: String,
    val completedSteps: Int,
)

data class Quest(
    val id: String,
    val title: String,
    val category: String,
    val emoji: String,
    val duration: String,
    val durationMinutes: Int,
    val distance: String,
    val budget: String,
    val budgetAmount: Int,
    val difficulty: QuestDifficulty,
    val location: LocationMode,
    val socialLevel: SocialLevel,
    val description: String,
    val isNew: Boolean = false,
    val isSponsored: Boolean = false,
    val sponsorName: String? = null,
    val isGroup: Boolean = false,
    val groupMembers: List<GroupMember> = emptyList(),
    val steps: List<QuestStep>,
    val tags: List<String>,
)

data class QuestProgress(
    val currentStep: Int = 0,
    val completedSteps: Set<Int> = emptySet(),
    val localPhotoProofByStep: Map<Int, String> = emptyMap(),
    val uploadedPhotoProofByStep: Map<Int, String> = emptyMap(),
    val photoProofUploadingSteps: Set<Int> = emptySet(),
    val photoProofErrorByStep: Map<Int, String> = emptyMap(),
    val abandoned: Boolean = false,
    val abandonReason: AbandonmentReason? = null,
) {
    fun hasPhotoProof(stepIndex: Int): Boolean = stepIndex in localPhotoProofByStep
}

data class UserPreferences(
    val interests: Set<String> = emptySet(),
    val difficulty: QuestDifficulty = QuestDifficulty.Easy,
    val typicalTime: Int = 30,
    val budgetMax: Int = 20,
    val socialLevel: SocialLevel = SocialLevel.Solo,
    val locationMode: String = "all",
)

data class QuestRating(
    val stars: Int,
    val tags: Set<String>,
)

enum class AppScreen {
    Auth,
    Onboarding,
    Explorer,
    QuestDetail,
    ActiveQuest,
    ExitFlow,
    QuestAlternatives,
    Rating,
    GroupQuest,
    Profile,
}

data class SidequestsUiState(
    val screen: AppScreen = AppScreen.Onboarding,
    val onboardingStep: Int = 0,
    val darkMode: Boolean = false,
    /** Theme follows the ambient light sensor instead of the manual toggle. */
    val autoThemeEnabled: Boolean = false,
    val lightSensorAvailable: Boolean = false,
    val ambientLux: Float? = null,
    val selectedQuestId: String = "botanical-garden",
    val activeQuestId: String = "botanical-garden",
    val preferences: UserPreferences = UserPreferences(),
    val availableTime: Int = 30,
    val selectedCategory: String = "All",
    val progressByQuest: Map<String, QuestProgress> = emptyMap(),
    val skippedQuestIds: Set<String> = emptySet(),
    val ratingsByQuest: Map<String, QuestRating> = emptyMap(),
    val attemptIdByQuest: Map<String, String> = emptyMap(),
    val progressSyncing: Boolean = false,
    val progressSyncError: String? = null,
    val progressSyncMessage: String? = null,
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val catalogSource: String = "Local catalogue",
    val recommendationLoading: Boolean = false,
    val recommendationError: String? = null,
    val recommendationSource: String = "Local fallback",
    val remoteRecommendationIds: List<String> = emptyList(),
    val notificationQuestId: String? = null,
    val contextualNotificationShown: Boolean = false,
    val questAlternatives: QuestAlternativesState? = null,
    /** Smart feature: recent completed/abandoned attempts (most recent last). */
    val difficultyOutcomes: List<com.sidequests.app.domain.DifficultyOutcome> = emptyList(),
    /** Smart feature: history size when the user dismissed the last suggestion. */
    val difficultySuggestionDismissedAt: Int? = null,
    /** Smart feature: explicit "I have X minutes" choices by part of the day. */
    val timeChoices: List<com.sidequests.app.domain.TimeChoice> = emptyList(),
    /** Smart feature: part of the day for which the smart time was already applied this session. */
    val smartTimeAppliedFor: com.sidequests.app.context.TimeOfDay? = null,
    val smartTimeHint: String? = null,
)
