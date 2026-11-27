package com.sidequests.app.domain

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MissionHistoryTest {
    private val zone = ZoneId.of("America/Bogota")
    private fun attempt(id: String, quest: String, status: String, date: String, rating: Int? = null) =
        HistoryAttempt(id, "alice", quest, status, date, rating = rating)
    private val snapshot = HistorySnapshot("alice", listOf(
        attempt("uq-1","cafe","completed","2026-11-02T17:00:00Z",5),
        attempt("uq-2","mural","completed","2026-11-20T17:00:00Z"),
        attempt("uq-3","yoga","abandoned","2026-11-06T17:00:00Z"),
        attempt("uq-4","cafe","in_progress","2026-11-22T17:00:00Z"),
        attempt("uq-5","yoga","completed","2026-10-31T17:00:00Z"),
        attempt("uq-6","mural","skipped","2026-11-21T17:00:00Z"),
        attempt("uq-7","deleted-quest","completed","2026-09-02T17:00:00Z"),
    ), listOf(
        HistoryQuest("cafe","Café de especialidad","food",45,"Usaquén"),
        HistoryQuest("mural","Murales del centro","art",60,"La Candelaria"),
        HistoryQuest("yoga","Yoga al amanecer","mindfulness",20),
    ), mapOf("uq-1" to 1200,"uq-3" to 300), setOf("uq-3","uq-1","uq-6"))

    private fun run(filter: HistoryFilter = HistoryFilter.All, query: String = "") =
        buildMissionHistory(snapshot, filter, query, zone)
    private fun ids(result: MissionHistory) = result.months.flatMap { it.entries }.map { it.attempt.id }

    @Test fun mirrorOrderAndMonthlyTotals() {
        val h = run()
        assertEquals(6,h.totalAttempts)
        assertEquals(listOf("uq-4","uq-2","uq-3","uq-1","uq-5","uq-7"), ids(h))
        assertEquals(listOf(11,10,9),h.months.map { it.month })
        val nov = h.months.first()
        assertEquals(2,nov.completedCount); assertEquals(1,nov.abandonedCount)
        assertEquals(1,nov.inProgressCount); assertEquals(105,nov.completedMinutes)
        assertEquals(1500,nov.steps)
        assertNull(h.months[1].steps)
        assertEquals(0,h.months[2].completedMinutes)
        assertNull(h.months[2].entries.single().quest)
    }

    @Test fun filtersSearchAndPendingCountMatchFlutter() {
        assertEquals(listOf("uq-2","uq-1","uq-5","uq-7"), ids(run(HistoryFilter.Completed)))
        assertEquals(listOf("uq-3"),ids(run(HistoryFilter.Abandoned)))
        assertEquals(listOf("uq-4","uq-1"),ids(run(query="CAFE")))
        assertEquals(listOf("uq-2"),ids(run(query="candelaria")))
        assertEquals(listOf("uq-4","uq-1"),ids(run(query="gastronomía")))
        assertEquals(listOf("uq-3","uq-5"),ids(run(query="bienestar")))
        assertEquals(listOf("uq-3","uq-5"),ids(run(query="mindfulness")))
        assertEquals(listOf("uq-4","uq-1"),ids(run(query="especialidad usaquen")))
        assertTrue(ids(run(query="usaquen murales")).isEmpty())
        assertEquals(listOf("uq-1"),ids(run(HistoryFilter.Completed,"cafe")))
        assertEquals(6,run(query="   ").matchCount)
        assertEquals(listOf("uq-7"),ids(run(query="deleted")))
        assertEquals(2,run(HistoryFilter.Abandoned).pendingSyncCount)
        assertEquals("cafe con nandu",normalizeHistorySearch("  Café   CON  Ñandú "))
    }

    @Test fun categoryLabelsAreEnglishAndSearchRemainsCompatible() {
        val expected = mapOf(
            "outdoors" to "Outdoors", "food" to "Food", "art" to "Art",
            "learning" to "Learning", "mindfulness" to "Mindfulness",
            "movement" to "Movement", "social" to "Social",
        )
        expected.forEach { (category, label) ->
            assertEquals(label, historyCategoryLabel(category))
        }
        assertEquals("Food", historyCategoryLabel("FOOD"))
        assertEquals(listOf("uq-4","uq-1"), ids(run(query="FOOD")))
        assertEquals(listOf("uq-3","uq-5"), ids(run(query="Mindfulness")))
    }

    @Test fun dateUsesStatusTimestampAndLocalZoneAndStableId() {
        val a = attempt("a","cafe","completed","2026-11-01T10:00:00Z")
            .copy(completedAt="2026-11-02T02:00:00Z")
        val b = a.copy(id="b")
        val h = buildMissionHistory(snapshot.copy(attempts=listOf(b,a)), zone=zone)
        assertEquals(listOf("a","b"),ids(h))
        assertEquals(1,h.months.single().entries.first().date.dayOfMonth)
    }
}
