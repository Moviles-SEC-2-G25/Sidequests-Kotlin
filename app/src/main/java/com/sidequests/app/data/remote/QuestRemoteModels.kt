package com.sidequests.app.data.remote

import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.Quest
import com.sidequests.app.model.QuestDifficulty
import com.sidequests.app.model.QuestStep
import com.sidequests.app.model.SocialLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class QuestDto(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val emoji: String? = null,
    @SerialName("duration_minutes")
    val durationMinutes: Int,
    @SerialName("estimated_cost")
    val estimatedCost: Double,
    val difficulty: String,
    @SerialName("location_mode")
    val locationMode: String,
    @SerialName("social_level")
    val socialLevel: String,
    @SerialName("location_name")
    val locationName: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("is_new")
    val isNew: Boolean = false,
    @SerialName("is_sponsored")
    val isSponsored: Boolean = false,
    @SerialName("sponsor_name")
    val sponsorName: String? = null,
    @SerialName("sponsor_business_category")
    val sponsorBusinessCategory: String? = null,
    @SerialName("is_group")
    val isGroup: Boolean = false,
)

@Serializable
data class QuestStepDto(
    val id: Long,
    @SerialName("quest_id")
    val questId: String,
    @SerialName("step_order")
    val stepOrder: Int,
    val title: String,
    val description: String,
    @SerialName("verification_type")
    val verificationType: String,
)

fun QuestDto.toDomain(
    remoteSteps: List<QuestStepDto>,
    fallback: Quest? = null,
): Quest {
    val mappedSteps = remoteSteps
        .sortedBy { it.stepOrder }
        .map { step ->
            QuestStep(
                title = step.title,
                description = step.description,
                requiresPhoto = step.verificationType in setOf("photo", "photo_and_location"),
            )
        }
        .ifEmpty { fallback?.steps.orEmpty() }

    val budgetAmount = estimatedCost.roundToInt()

    return Quest(
        id = id,
        title = title,
        category = category,
        emoji = emoji ?: fallback?.emoji ?: "⚡",
        duration = "$durationMinutes min",
        durationMinutes = durationMinutes,
        distance = fallback?.distance ?: when (locationMode) {
            "anywhere" -> "Anywhere"
            else -> locationName ?: "Nearby"
        },
        budget = if (budgetAmount == 0) "Free" else "\$budgetAmount",
        budgetAmount = budgetAmount,
        difficulty = when (difficulty.lowercase()) {
            "medium" -> QuestDifficulty.Medium
            "hard" -> QuestDifficulty.Hard
            else -> QuestDifficulty.Easy
        },
        location = if (locationMode == "anywhere") {
            LocationMode.Anywhere
        } else {
            LocationMode.Gps
        },
        socialLevel = when (socialLevel.lowercase()) {
            "social" -> SocialLevel.Social
            "group" -> SocialLevel.Group
            else -> SocialLevel.Solo
        },
        description = description,
        isNew = isNew,
        isSponsored = isSponsored,
        sponsorName = sponsorName,
        isGroup = isGroup,
        groupMembers = fallback?.groupMembers.orEmpty(),
        steps = mappedSteps,
        tags = tags,
    )
}
