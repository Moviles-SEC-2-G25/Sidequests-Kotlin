package com.sidequests.app.data.settings

import com.sidequests.app.context.TimeOfDay
import com.sidequests.app.domain.DifficultyOutcome
import com.sidequests.app.domain.TimeChoice
import com.sidequests.app.model.AbandonmentReason
import com.sidequests.app.model.QuestDifficulty
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun `time choices survive a round trip`() {
        val choices = listOf(TimeChoice(TimeOfDay.MORNING, 15), TimeChoice(TimeOfDay.EVENING, 45))
        assertEquals(choices, SettingsCodec.decodeTimeChoices(SettingsCodec.encodeTimeChoices(choices)))
    }

    @Test
    fun `difficulty outcomes survive a round trip`() {
        val outcomes = listOf(
            DifficultyOutcome(QuestDifficulty.Medium, completed = true),
            DifficultyOutcome(QuestDifficulty.Easy, completed = false, reason = AbandonmentReason.TooDifficult),
        )
        assertEquals(outcomes, SettingsCodec.decodeOutcomes(SettingsCodec.encodeOutcomes(outcomes)))
    }

    @Test
    fun `empty or corrupted data is skipped instead of crashing`() {
        assertEquals(emptyList<TimeChoice>(), SettingsCodec.decodeTimeChoices(null))
        assertEquals(listOf(TimeChoice(TimeOfDay.NIGHT, 20)), SettingsCodec.decodeTimeChoices("bad;NIGHT:20;NOON:x"))
        assertEquals(emptyList<DifficultyOutcome>(), SettingsCodec.decodeOutcomes("Legendary:1:-;Easy:maybe:-"))
    }
}
