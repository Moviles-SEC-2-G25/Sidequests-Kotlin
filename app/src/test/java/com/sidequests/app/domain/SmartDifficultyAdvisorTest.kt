package com.sidequests.app.domain

import com.sidequests.app.model.AbandonmentReason
import com.sidequests.app.model.QuestDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartDifficultyAdvisorTest {
    private fun done(d: QuestDifficulty) = DifficultyOutcome(d, completed = true)
    private fun left(d: QuestDifficulty, r: AbandonmentReason) = DifficultyOutcome(d, completed = false, reason = r)

    @Test
    fun `two difficulty-related abandons suggest an easier level`() {
        val history = listOf(
            done(QuestDifficulty.Medium),
            left(QuestDifficulty.Medium, AbandonmentReason.TooDifficult),
            left(QuestDifficulty.Medium, AbandonmentReason.RanOutOfTime),
        )
        val suggestion = SmartDifficultyAdvisor.suggest(history, QuestDifficulty.Medium)
        assertEquals(QuestDifficulty.Easy, suggestion?.suggested)
    }

    @Test
    fun `abandoning easier quests as too hard also lowers the level`() {
        val history = List(2) { left(QuestDifficulty.Easy, AbandonmentReason.TooDifficult) }
        assertEquals(QuestDifficulty.Easy, SmartDifficultyAdvisor.suggest(history, QuestDifficulty.Medium)?.suggested)
    }

    @Test
    fun `abandons for weather or distance do not lower the level`() {
        val history = listOf(
            left(QuestDifficulty.Medium, AbandonmentReason.WeatherOrMoodChanged),
            left(QuestDifficulty.Medium, AbandonmentReason.TooFar),
        )
        assertNull(SmartDifficultyAdvisor.suggest(history, QuestDifficulty.Medium))
    }

    @Test
    fun `three completions in a row suggest a harder level`() {
        val history = List(3) { done(QuestDifficulty.Easy) }
        assertEquals(QuestDifficulty.Medium, SmartDifficultyAdvisor.suggest(history, QuestDifficulty.Easy)?.suggested)
    }

    @Test
    fun `no suggestion beyond the limits or for other levels`() {
        assertNull(SmartDifficultyAdvisor.suggest(List(3) { done(QuestDifficulty.Hard) }, QuestDifficulty.Hard))
        assertNull(SmartDifficultyAdvisor.suggest(List(3) { done(QuestDifficulty.Easy) }, QuestDifficulty.Medium))
    }
}
