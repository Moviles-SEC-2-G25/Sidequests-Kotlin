package com.sidequests.app.domain

import com.sidequests.app.context.TimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartTimeAdvisorTest {
    private fun pick(t: TimeOfDay, m: Int) = TimeChoice(t, m)

    @Test
    fun `most frequent choice for the same part of the day wins`() {
        val history = listOf(
            pick(TimeOfDay.MORNING, 15),
            pick(TimeOfDay.MORNING, 15),
            pick(TimeOfDay.MORNING, 45),
            pick(TimeOfDay.EVENING, 60),
        )
        assertEquals(15, SmartTimeAdvisor.preferredMinutes(history, TimeOfDay.MORNING))
    }

    @Test
    fun `ties are broken by the most recent choice`() {
        val history = listOf(pick(TimeOfDay.AFTERNOON, 30), pick(TimeOfDay.AFTERNOON, 45))
        assertEquals(45, SmartTimeAdvisor.preferredMinutes(history, TimeOfDay.AFTERNOON))
    }

    @Test
    fun `not enough history means no suggestion`() {
        val history = listOf(pick(TimeOfDay.NIGHT, 20), pick(TimeOfDay.MORNING, 20))
        assertNull(SmartTimeAdvisor.preferredMinutes(history, TimeOfDay.NIGHT))
    }
}
