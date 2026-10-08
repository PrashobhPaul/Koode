package com.trippulse.app

import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Expenses
import com.trippulse.app.domain.Pictures
import com.trippulse.app.domain.StageRepair
import com.trippulse.app.domain.TripEvent
import com.trippulse.app.domain.report.JourneyStory
import com.trippulse.app.domain.report.PlaceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The commute of 8 October: a bike taxi from Nacharam to Habsiguda, the metro
 * to HITEC City, a bike taxi to the office, and a few steps after getting off
 * there. A journey made more than one way is told as a journey, never as the
 * last of its modes ("bound for the office on foot").
 */
class MultiModeJourneyTest {

    private val t0 = 1_791_442_560_000L // 8 Oct, 10:36 IST
    private fun min(m: Int) = t0 + m * 60_000L
    private var n = 0
    private fun ev(type: String, at: Long, p: Pair<Double, Double>, payload: Map<String, Any?> = emptyMap()) =
        TripEvent("e${n++}", "T", type, at, p.first, p.second, null, EventSource.DRIVER_MANUAL, payload)

    private val raheja = 17.4250 to 78.5560
    private val habsiguda = 17.42018 to 78.54055
    private val hitec = 17.44740 to 78.38140
    private val office = 17.43230 to 78.38770

    private fun fixes(from: Pair<Double, Double>, to: Pair<Double, Double>, a: Long, b: Long): List<JourneyStory.Sample> {
        val steps = ((b - a) / 60_000L).toInt().coerceAtLeast(1)
        return (0..steps).map { i ->
            val f = i / steps.toDouble()
            JourneyStory.Sample(a + (b - a) * i / steps, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f)
        }
    }

    private fun input(): JourneyStory.Input {
        val samples = fixes(raheja, habsiguda, min(0), min(19)) + fixes(habsiguda, hitec, min(19), min(56)) +
            fixes(hitec, office, min(56), min(79)) + fixes(office, office, min(79), min(81))
        val events = listOf(
            ev(EventTypes.LEG_STARTED, min(0), raheja, mapOf("mode" to "BIKE_TAXI")),
            ev(EventTypes.TRAVEL_MODE_CHANGED, min(19), habsiguda, mapOf("fromMode" to "BIKE_TAXI", "toMode" to "METRO")),
            ev(EventTypes.TRAVEL_MODE_CHANGED, min(56), hitec, mapOf("fromMode" to "METRO", "toMode" to "BIKE_TAXI")),
            ev(EventTypes.TRAVEL_MODE_CHANGED, min(79), office, mapOf("fromMode" to "BIKE_TAXI", "toMode" to "WALK"))
        )
        return JourneyStory.Input(
            who = "Prashobh Paul", origin = "Raheja Vistas, Nacharam", destination = "Qualizeal Office",
            originLat = raheja.first, originLng = raheja.second, destLat = office.first, destLng = office.second,
            // The journey's mode as stored is the last stage's: the few steps at the office.
            mode = "WALK", startedAtMs = min(0), endedAtMs = min(81), nowMs = min(90),
            events = events, samples = samples, distanceM = 26_000.0, routeDistanceM = 26_000.0, zone = ZoneId.of("Asia/Kolkata")
        )
    }

    private fun story(i: JourneyStory.Input = input()): JourneyStory.Story {
        val book = PlaceBook()
        JourneyStory.seed(i, book)
        return JourneyStory.build(i, book)
    }

    @Test fun a_journey_ridden_three_ways_is_not_told_as_a_walk_or_as_any_one_ride() {
        val s = story()
        val opening = s.paragraphs.first()
        listOf("on foot", "by bike taxi", "by metro", "walked").forEach {
            assertFalse("opening says \"$it\": $opening", opening.contains(it))
        }
        assertTrue(opening, opening.contains("Qualizeal Office."))
        assertFalse(s.headline, s.headline.contains("on foot") || s.headline.contains(" by "))
        // The ways it was made are told, in order.
        assertTrue(s.paragraphs.any { it.contains("bike taxi") && it.contains("metro") })
    }

    @Test fun its_longest_part_is_named_with_its_own_picture() {
        val s = story()
        val longest = s.highlights.firstOrNull { it.detail == "The longest part" }
        assertNotNull(s.highlights.toString(), longest)
        assertEquals(Pictures.mode("METRO"), longest!!.picture)
        assertTrue(longest.title, longest.title.endsWith("by metro"))
        assertTrue(s.highlights.none { it.title.endsWith("longest stretch") })
        assertTrue(s.paragraphs.joinToString(" "), s.paragraphs.any { it.contains("the metro") && (it.contains("longest part") || it.contains("Most of the time")) })
        assertFalse(s.paragraphs.joinToString(" ").contains("without a stop"))
    }

    @Test fun a_bike_taxi_journey_is_a_ride_not_a_walk() {
        val i = input().let { base ->
            base.copy(
                mode = "BIKE_TAXI", endedAtMs = min(19), nowMs = min(20),
                events = listOf(ev(EventTypes.LEG_STARTED, min(0), raheja, mapOf("mode" to "BIKE_TAXI"))),
                samples = fixes(raheja, habsiguda, min(0), min(19)), destination = "Habsiguda Metro", distanceM = 2_000.0
            )
        }
        val opening = story(i).paragraphs.first()
        assertFalse(opening, opening.contains("on foot"))
        assertTrue(opening, opening.contains("bike taxi"))
    }

    @Test fun a_fare_written_before_its_stage_was_named_takes_the_name() {
        val stages = listOf(
            Expenses.FareStage("BIKE_TAXI", "Raheja Vistas, Nacharam", "Habsiguda Metro", min(19)),
            Expenses.FareStage("METRO", "Habsiguda Metro", "HITEC City Metro", min(56)),
            Expenses.FareStage("BIKE_TAXI", "HITEC City Metro", "Qualizeal Office", min(79))
        )
        val cat = Expenses.Category.METRO
        assertEquals("Metro · Habsiguda Metro → HITEC City Metro",
            Expenses.relabelled("Metro · Tarnaka, Hyderabad → En route", cat, min(56) + 800, stages))
        assertEquals("Bike taxi · Raheja Vistas, Nacharam → Habsiguda Metro",
            Expenses.relabelled("Bike taxi · Raheja Vistas, Nacharam → En route", Expenses.Category.BIKE_TAXI, min(19), stages))
        // Already right, someone else's words, or a fare at no stage's end: left alone.
        assertNull(Expenses.relabelled("Metro · Habsiguda Metro → HITEC City Metro", cat, min(56), stages))
        assertNull(Expenses.relabelled("Lunch with the team", Expenses.Category.FOOD, min(56), stages))
        assertNull(Expenses.relabelled("Metro · A → B", cat, min(30), stages))
    }

    @Test fun the_stages_read_as_the_8_october_journey_was_made() {
        // As recorded: the metro to Durgam Cheruvu, 250 m on foot to the bike
        // taxi, the bike taxi to the office, and "got off" there.
        data class S(val mode: String, val from: String, val to: String, val m: Double?)
        val recorded = listOf(
            S("BIKE_TAXI", "Raheja Vistas, Nacharam", "Habsiguda Metro", 2_100.0),
            S("METRO", "Habsiguda Metro", "Durgam Cheruvu Metro", 17_800.0),
            S("WALK", "Durgam Cheruvu Metro", "Madhapur, Hyderabad", 250.0),
            S("BIKE_TAXI", "Madhapur, Hyderabad", "Qualizeal Office", 1_400.0),
            S("WALK", "Qualizeal Office", "Qualizeal Office", 0.0)
        )
        val told = StageRepair.told(recorded, { it.mode }, { it.m }, { it.from }, { it.to }) { st, from -> st.copy(from = from) }
        assertEquals(
            listOf("Raheja Vistas, Nacharam → Habsiguda Metro", "Habsiguda Metro → Durgam Cheruvu Metro", "Durgam Cheruvu Metro → Qualizeal Office"),
            told.map { "${it.from} → ${it.to}" }
        )
        // A journey only walked is still told.
        val walked = listOf(S("WALK", "Home", "Home", 40.0))
        assertEquals(walked, StageRepair.told(walked, { it.mode }, { it.m }, { it.from }, { it.to }) { st, from -> st.copy(from = from) })
    }

    @Test fun no_stage_runs_past_the_journeys_end() {
        // The journey closed at its arrival, 11:43; "got off the bike taxi"
        // was tapped at 11:57 and started a walk. The bike taxi ran 11:34 to
        // 11:43, the walk was never part of the journey.
        data class S(val mode: String, val started: Long?, val completed: Long?)
        val arrived = min(67)
        val legs = listOf(S("BIKE_TAXI", min(0), min(19)), S("METRO", min(19), min(56)), S("BIKE_TAXI", min(58), min(81)), S("WALK", min(81), null))
        val within = StageRepair.withinJourney(legs, arrived, { it.started }, { it.completed }) { l, e -> l.copy(completed = e) }
        assertEquals(3, within.size)
        assertEquals(arrived, within.last().completed)
        assertTrue(within.sumOf { it.completed!! - it.started!! } <= arrived - min(0))
        // A stage still open when the journey ended ended with it; not while the journey is under way.
        val open = listOf(S("CAB", min(0), null))
        assertEquals(min(30), StageRepair.withinJourney(open, min(30), { it.started }, { it.completed }) { l, e -> l.copy(completed = e) }.single().completed)
        assertNull(StageRepair.withinJourney(open, min(30), { it.started }, { it.completed }, completed = false) { l, e -> l.copy(completed = e) }.single().completed)

        // The story tells the same: the last part is 11 minutes, not 23.
        val s = story(input().copy(endedAtMs = arrived, nowMs = min(90)))
        val text = s.paragraphs.joinToString(" ")
        assertTrue(text, text.contains("bike taxi again (11 min)"))
        assertFalse(text, text.contains("23 min") || text.contains("on foot"))
    }

    @Test fun a_walk_from_the_office_to_the_office_is_not_a_stage() {
        assertTrue(StageRepair.goesNowhere("WALK", "Qualizeal Office", "Qualizeal Office"))
        assertFalse(StageRepair.goesNowhere("BIKE_TAXI", "Qualizeal Office", "Qualizeal Office"))
        assertFalse(StageRepair.goesNowhere("WALK", "Madhapur", "Qualizeal Office"))
        assertFalse(StageRepair.goesNowhere("WALK", StageRepair.EN_ROUTE, StageRepair.EN_ROUTE))
    }
}
