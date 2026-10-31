package com.sidequests.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeSessionTrackerTest {
    private val minute = 60_000L

    @Test
    fun `resume after more than 10 minutes with an active Nearby quest qualifies`() {
        val tracker = ResumeSessionTracker()
        tracker.onBackground(nowMillis = 0, activeNearbyQuestId = "botanical-garden")
        val resume = tracker.onForeground(nowMillis = 11 * minute)
        assertNotNull(resume)
        assertEquals("botanical-garden", resume!!.questId)
        assertEquals(11 * minute, resume.backgroundMillis)
    }

    @Test
    fun `short background or no Nearby quest does not qualify`() {
        val tracker = ResumeSessionTracker()
        tracker.onBackground(0, "botanical-garden")
        assertNull(tracker.onForeground(9 * minute))
        tracker.onBackground(0, null)
        assertNull(tracker.onForeground(30 * minute))
    }

    @Test
    fun `failures are attributed to the resume only during the 5-minute window`() {
        val tracker = ResumeSessionTracker()
        tracker.onBackground(0, "q1")
        tracker.onForeground(15 * minute)
        assertNotNull(tracker.activeResume(15 * minute + 4 * minute))
        assertNull(tracker.activeResume(15 * minute + 6 * minute))
    }

    @Test
    fun `background period restored after process death is still detected`() {
        val tracker = ResumeSessionTracker()
        tracker.restore(backgroundSinceMillis = 0, questId = "q1")
        assertNotNull(tracker.onForeground(20 * minute))
    }
}
