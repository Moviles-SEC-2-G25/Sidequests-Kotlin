package com.sidequests.app.domain

import com.sidequests.app.model.AbandonmentReason
import com.sidequests.app.model.QuestDifficulty

/** One finished attempt (completed or abandoned) used by the smart difficulty feature. */
data class DifficultyOutcome(
    val difficulty: QuestDifficulty,
    val completed: Boolean,
    val reason: AbandonmentReason? = null,
)

data class DifficultyStreaks(val abandons: Int, val completions: Int)

data class DifficultySuggestion(
    val current: QuestDifficulty,
    val suggested: QuestDifficulty,
    val explanation: String,
)

/**
 * Smart feature (Sprint 2, Carlos): adapts the preferred difficulty to how the user actually plays.
 *
 * - Abandoning [EASIER_AFTER_ABANDONS] quests in a row at the preferred difficulty (or easier) because
 *   they were too difficult or took too long → suggest the easier level.
 * - Completing [HARDER_AFTER_COMPLETIONS] quests in a row at the preferred difficulty (or harder) →
 *   suggest the harder level.
 * Abandons caused by things unrelated to difficulty (weather, distance, budget, place closed) are
 * ignored, so a rainy day does not lower the user's level.
 *
 * Runs entirely on the device with data the app already has, so it also works offline.
 */
object SmartDifficultyAdvisor {
    const val EASIER_AFTER_ABANDONS = 2
    const val HARDER_AFTER_COMPLETIONS = 3
    const val HISTORY_SIZE = 10

    private val difficultyRelatedReasons = setOf(
        AbandonmentReason.TooDifficult,
        AbandonmentReason.RanOutOfTime,
    )

    /** Current streaks of difficulty-related abandons and of completions at [preferred]. */
    fun streaks(history: List<DifficultyOutcome>, preferred: QuestDifficulty): DifficultyStreaks {
        // Most recent first, only attempts that say something about the preferred level:
        // - abandoning a quest at this level (or an easier one) because it was too hard/long means
        //   the level is too high;
        // - completing a quest at this level (or a harder one) means the user can handle more.
        val relevant = history.asReversed().filter { outcome ->
            if (outcome.completed) {
                outcome.difficulty >= preferred
            } else {
                outcome.reason in difficultyRelatedReasons && outcome.difficulty <= preferred
            }
        }
        return DifficultyStreaks(
            abandons = relevant.takeWhile { !it.completed }.size,
            completions = relevant.takeWhile { it.completed }.size,
        )
    }

    fun suggest(history: List<DifficultyOutcome>, preferred: QuestDifficulty): DifficultySuggestion? {
        val (abandonStreak, completionStreak) = streaks(history, preferred)

        if (abandonStreak >= EASIER_AFTER_ABANDONS) {
            val easier = preferred.easier() ?: return null
            return DifficultySuggestion(
                current = preferred,
                suggested = easier,
                explanation = "You left your last $abandonStreak ${preferred.name} quests because they were too hard or too long. Try ${easier.name} for a while?",
            )
        }
        if (completionStreak >= HARDER_AFTER_COMPLETIONS) {
            val harder = preferred.harder() ?: return null
            return DifficultySuggestion(
                current = preferred,
                suggested = harder,
                explanation = "You completed $completionStreak ${preferred.name} quests in a row. Ready for ${harder.name}?",
            )
        }
        return null
    }

    private fun QuestDifficulty.easier(): QuestDifficulty? = QuestDifficulty.entries.getOrNull(ordinal - 1)
    private fun QuestDifficulty.harder(): QuestDifficulty? = QuestDifficulty.entries.getOrNull(ordinal + 1)
}
