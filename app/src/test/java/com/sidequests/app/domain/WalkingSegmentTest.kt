package com.sidequests.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkingSegmentTest {
    @Test fun `only deltas inside the segment count and a counter reset establishes a new baseline`() {
        val segment = WalkingSegment()
        assertEquals(0, segment.observe(1_000))
        assertEquals(4, segment.observe(1_004))
        assertEquals(0, segment.observe(1_004))
        assertEquals(0, segment.observe(3))
        assertEquals(2, segment.observe(5))
        assertEquals(0, WalkingSegment().observe(500))
    }
}
