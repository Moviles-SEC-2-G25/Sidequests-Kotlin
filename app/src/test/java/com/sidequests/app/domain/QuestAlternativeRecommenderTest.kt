package com.sidequests.app.domain

import com.sidequests.app.context.TimeOfDay
import com.sidequests.app.context.UserContext
import com.sidequests.app.context.WeatherCondition
import com.sidequests.app.context.WeatherData
import com.sidequests.app.model.*
import org.junit.Assert.*
import org.junit.Test

class QuestAlternativeRecommenderTest {
    private val engine = QuestAlternativeRecommender()
    private val source = quest("source", minutes = 60, cost = 14, difficulty = QuestDifficulty.Hard, distance = "2 km")
    private fun context(weather: WeatherCondition = WeatherCondition.UNKNOWN, time: TimeOfDay = TimeOfDay.AFTERNOON) =
        UserContext(timeOfDay = time, weather = WeatherData(20.0, weather))
    private fun recommend(
        candidates: List<Quest>,
        reason: AbandonmentReason = AbandonmentReason.Other,
        context: UserContext = context(),
        excluded: Set<String> = emptySet(),
        available: Int = 30,
        previous: Quest = source,
        preferences: UserPreferences = UserPreferences(),
    ) = engine.recommend(candidates, previous, reason, context, available, preferences, excluded)

    @Test fun `rain and clear weather change the first alternative for identical inputs`() {
        val outdoor = quest("outdoor", tags = listOf("outdoor", "nature"))
        val indoor = quest("indoor", tags = listOf("indoor"))
        assertEquals("outdoor", recommend(listOf(indoor, outdoor), context = context(WeatherCondition.CLEAR)).first().quest.id)
        val wet = recommend(listOf(indoor, outdoor), context = context(WeatherCondition.RAIN))
        assertEquals("indoor", wet.first().quest.id)
        assertTrue(wet.first().ruleCodes.contains("wet_indoor"))
    }

    @Test fun `night changes a clear weather outdoor suggestion to an indoor option`() {
        val outdoor = quest("outdoor", tags = listOf("outdoor"))
        val indoor = quest("indoor", tags = listOf("indoor"))
        val candidates = listOf(indoor, outdoor)
        assertEquals("outdoor", recommend(candidates, context = context(WeatherCondition.CLEAR)).first().quest.id)
        assertEquals("indoor", recommend(candidates, context = context(WeatherCondition.CLEAR, TimeOfDay.NIGHT)).first().quest.id)
    }

    @Test fun `time reason excludes equal and longer durations instead of merely sorting them`() {
        val candidates = listOf(quest("short", minutes = 15), quest("same", minutes = 30), quest("long", minutes = 60))
        val results = recommend(candidates, AbandonmentReason.RanOutOfTime, previous = source.copy(durationMinutes = 30))
        assertEquals(listOf("short"), results.map { it.quest.id })
    }

    @Test fun `cost reason selects a cheaper option while time reason selects a shorter one`() {
        val shortPaid = quest("short-paid", minutes = 15, cost = 5)
        val longFree = quest("long-free", minutes = 30, cost = 0)
        val previous = source.copy(durationMinutes = 30, budgetAmount = 5)
        val candidates = listOf(shortPaid, longFree)
        assertEquals("short-paid", recommend(candidates, AbandonmentReason.RanOutOfTime, previous = previous).single().quest.id)
        assertEquals("long-free", recommend(candidates, AbandonmentReason.TooExpensive, previous = previous).single().quest.id)
    }

    @Test fun `distance reason handles unknown labels without inventing proximity`() {
        val local = quest("local", location = LocationMode.Anywhere, distance = "Anywhere")
        val unknown = quest("unknown", distance = "On campus")
        val closer = quest("closer", distance = "500 m")
        val previous = source.copy(distance = "Unknown")
        assertEquals("local", recommend(listOf(local, unknown, closer), AbandonmentReason.TooFar, previous = previous).single().quest.id)
        assertEquals(setOf("local", "closer"), recommend(listOf(local, unknown, closer), AbandonmentReason.TooFar).map { it.quest.id }.toSet())
    }

    @Test fun `closed venue and difficulty reasons require a meaningful alternative`() {
        val anywhere = quest("local", location = LocationMode.Anywhere)
        val hard = quest("hard", difficulty = QuestDifficulty.Hard)
        val easy = quest("easy")
        assertEquals("local", recommend(listOf(anywhere, hard, easy), AbandonmentReason.PlaceUnavailable).single().quest.id)
        assertEquals(setOf("local", "easy"), recommend(listOf(anywhere, hard, easy), AbandonmentReason.TooDifficult).map { it.quest.id }.toSet())
    }

    @Test fun `unknown weather applies no fake weather rule`() {
        val result = recommend(listOf(quest("outdoor", tags = listOf("nature"))), context = context()).single()
        assertFalse(result.ruleCodes.any { it.startsWith("wet_") || it.startsWith("clear_") })
    }

    @Test fun `excludes source existing attempts and options outside time budget or mode`() {
        val candidates = listOf(source, quest("done"), quest("too-long", minutes = 45),
            quest("too-costly", cost = 25), quest("gps"), quest("local", location = LocationMode.Anywhere))
        val results = recommend(candidates, excluded = setOf("done"), preferences = UserPreferences(locationMode = "anywhere"))
        assertEquals(listOf("local"), results.map { it.quest.id })
    }

    @Test fun `returns empty when no improvement exists or no time is available`() {
        assertTrue(recommend(listOf(quest("same", minutes = 10)), AbandonmentReason.RanOutOfTime,
            previous = source.copy(durationMinutes = 10)).isEmpty())
        assertTrue(recommend(listOf(quest("candidate")), available = 0).isEmpty())
    }

    @Test fun `limits results removes duplicates and resolves ties independent of catalogue order`() {
        val candidates = listOf(quest("d"), quest("c"), quest("b"), quest("a"), quest("a"))
        assertEquals(listOf("a", "b", "c"), recommend(candidates).map { it.quest.id })
        assertEquals(recommend(candidates), recommend(candidates.reversed()))
    }

    private fun quest(
        id: String,
        minutes: Int = 20,
        cost: Int = 0,
        difficulty: QuestDifficulty = QuestDifficulty.Easy,
        distance: String = "1 km",
        location: LocationMode = LocationMode.Gps,
        tags: List<String> = emptyList(),
    ) = Quest(id, id, "Test", "🧭", "$minutes min", minutes, distance, "$$cost", cost,
        difficulty, location, SocialLevel.Solo, "Test", steps = listOf(QuestStep("Step", "Test")), tags = tags)
}
