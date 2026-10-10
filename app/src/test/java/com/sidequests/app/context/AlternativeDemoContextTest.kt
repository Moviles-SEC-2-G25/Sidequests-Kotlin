package com.sidequests.app.context

import org.junit.Assert.*
import org.junit.Test

class AlternativeDemoContextTest {
    @Test fun `parses valid debug scenario without pretending to have GPS`() {
        val context = AlternativeDemoContext.parse("rain", 14)!!
        assertEquals(WeatherCondition.RAIN, context.weather?.condition)
        assertEquals(TimeOfDay.AFTERNOON, context.timeOfDay)
        assertNull(context.location)
    }

    @Test fun `ignores missing or invalid scenario values`() {
        assertNull(AlternativeDemoContext.parse("rain", 24))
        assertNull(AlternativeDemoContext.parse("unexpected", 14))
        assertNull(AlternativeDemoContext.parse(null, 14))
        assertNull(AlternativeDemoContext.parse("clear", null))
    }
}
