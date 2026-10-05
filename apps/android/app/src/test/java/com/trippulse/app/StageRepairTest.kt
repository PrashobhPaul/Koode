package com.trippulse.app

import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.JourneyAnalytics
import com.trippulse.app.domain.StageRepair
import com.trippulse.app.domain.TripEvent
import com.trippulse.app.domain.report.JourneyStory
import com.trippulse.app.domain.report.PlaceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The office-to-home commute of 5 October, as an older build recorded it:
 * a cab, a change to the metro recorded only as a plan change, then the cab
 * tapped three times in a minute and a half.
 */
class StageRepairTest {

    private val t0 = 1_791_201_983_549L
    private fun min(m: Int) = t0 + m * 60_000L
    private val home = 17.4322161 to 78.5650201

    private fun stage(i: Int, mode: String, from: String, fLat: Double, fLng: Double, start: Long, end: Long?) =
        StageRepair.Stage(i, mode, from, fLat, fLng, "Raheja Vistas, Nacharam", home.first, home.second, start, end)

    private val recorded = listOf(
        stage(0, "CAB", "Qualizeal Office", 17.432309, 78.387687, min(0), min(28)),
        stage(1, "METRO", "En route", 17.4424829, 78.3877171, min(28), min(77)),
        stage(2, "CAB", "En route", 17.4225763, 78.545425, min(77), min(77) + 3_000),
        stage(3, "CAB", "En route", 17.4231274, 78.5458575, min(77) + 3_000, min(78) + 25_000),
        stage(4, "CAB", "En route", 17.4234742, 78.5464515, min(78) + 25_000, null)
    )

    @Test fun a_finished_stage_ends_where_the_next_began() {
        val fixed = StageRepair.endsWhereNextBegan(recorded).associateBy { it.index }
        // The cab went to the metro, not home.
        assertEquals("En route", fixed[0]!!.toName)
        assertEquals(17.4424829, fixed[0]!!.toLat, 1e-9)
        assertEquals(17.4225763, fixed[1]!!.toLat, 1e-9)
        // The stage still running keeps its destination.
        assertTrue(4 !in fixed)
        // Applied twice, nothing more changes.
        val again = recorded.map { s -> fixed[s.index] ?: s }
        assertTrue(StageRepair.endsWhereNextBegan(again).isEmpty())
    }

    @Test fun a_change_tapped_three_times_is_one_stage() {
        val folded = StageRepair.folded(recorded, { it.mode }, { it.startedAtMs }, { it.completedAtMs }) { a, b ->
            b.copy(fromName = a.fromName, fromLat = a.fromLat, startedAtMs = a.startedAtMs)
        }
        assertEquals(listOf("CAB", "METRO", "CAB"), folded.map { it.mode })
        assertEquals(min(77), folded[2].startedAtMs)
        assertEquals(null, folded[2].completedAtMs)
        // A long stage in the same mode after a real one stays its own (a second cab after a breakdown).
        val twoCabs = listOf(recorded[0], recorded[0].copy(index = 1, startedAtMs = min(28), completedAtMs = min(60)))
        assertEquals(2, StageRepair.folded(twoCabs, { it.mode }, { it.startedAtMs }, { it.completedAtMs }) { a, _ -> a }.size)
    }

    @Test fun the_report_lists_the_stages_once_each() {
        val legs = recorded.map { JourneyAnalytics.LegInput(it.index, it.mode, it.fromName, it.toName, it.startedAtMs, it.completedAtMs) }
        val r = JourneyAnalytics.analyse(JourneyAnalytics.Inputs(
            events = emptyList(), distanceCoveredM = 24_000.0, startedAtMs = min(0), endedAtMs = min(95), legs = legs
        ))
        assertEquals(listOf("CAB", "METRO", "CAB"), r.legs.map { it.mode })
        assertEquals(listOf(0, 1, 2), r.legs.map { it.index })
    }

    @Test fun a_mode_change_recorded_only_as_a_plan_change_still_starts_a_stage() {
        var n = 0
        fun e(type: String, at: Long, p: Map<String, Any?>) = TripEvent("e${n++}", "T", type, at, 17.43, 78.4, null, EventSource.DRIVER_MANUAL, p)
        val events = listOf(
            e(EventTypes.TRAVEL_MODE_CHANGED, min(28), mapOf("fromMode" to "CAB", "toMode" to "METRO")),
            e(EventTypes.LEG_STARTED, min(77), mapOf("mode" to "CAB")),
            e(EventTypes.TRAVEL_MODE_CHANGED, min(77), mapOf("fromMode" to "METRO", "toMode" to "CAB")),
            e(EventTypes.LEG_STARTED, min(77) + 3_000, mapOf("mode" to "CAB"))
        )
        val samples = (0..95).map { JourneyStory.Sample(min(it), 17.43, 78.38 + it * 0.0019) }
        val input = JourneyStory.Input(
            who = "Prashobh Paul", origin = "Qualizeal Office", destination = "Raheja Vistas, Nacharam",
            originLat = 17.43, originLng = 78.38, destLat = home.first, destLng = home.second,
            mode = "CAB", startedAtMs = min(0), endedAtMs = min(95), nowMs = min(96),
            events = events, samples = samples, distanceM = 24_000.0, zone = ZoneId.of("Asia/Kolkata")
        )
        val book = PlaceBook(); JourneyStory.seed(input, book)
        val s = JourneyStory.build(input, book)
        assertEquals(listOf("CAB", "METRO", "CAB"), s.stages.map { it.mode })
        assertEquals(min(28), s.stages[1].fromMs)
        assertEquals("METRO", JourneyStory.primaryMode(s, input.mode))
    }
}
