package com.trippulse.app

import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Expenses
import com.trippulse.app.domain.JourneyAnalytics
import com.trippulse.app.domain.Pictures
import com.trippulse.app.domain.StageRepair
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TripEvent
import com.trippulse.app.domain.report.JourneyStory
import com.trippulse.app.domain.report.PlaceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The morning commute of 6 October: the metro from Mettuguda, a few hundred
 * metres on foot at Madhapur, then a cab to the office. The walk is the
 * change between the two rides, not a way the journey was made.
 */
class CommuteStagesTest {

    private val t0 = 1_791_279_840_000L // 11:24 IST
    private fun min(m: Int) = t0 + m * 60_000L
    private var n = 0
    private fun ev(type: String, at: Long, lat: Double, lng: Double, p: Map<String, Any?> = emptyMap()) =
        TripEvent("e${n++}", "T", type, at, lat, lng, null, EventSource.SYSTEM_INFERRED, p)

    private val mettuguda = 17.4445 to 78.5250
    private val madhapur = 17.4480 to 78.3910
    private val office = 17.4323 to 78.3877

    /** Straight-line fixes every minute between two places. */
    private fun fixes(from: Pair<Double, Double>, to: Pair<Double, Double>, a: Long, b: Long): List<JourneyStory.Sample> {
        val steps = ((b - a) / 60_000L).toInt().coerceAtLeast(1)
        return (0..steps).map { i ->
            val f = i / steps.toDouble()
            JourneyStory.Sample(a + (b - a) * i / steps, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f)
        }
    }

    /** The walk at Madhapur covers [walkM] metres due north before the cab is boarded. */
    private fun input(walkM: Double): JourneyStory.Input {
        val walkEnd = madhapur.first + walkM / 111_000.0 to madhapur.second
        val samples = fixes(mettuguda, madhapur, min(0), min(36)) + fixes(madhapur, walkEnd, min(36), min(40)) + fixes(walkEnd, office, min(40), min(55))
        val events = listOf(
            ev(EventTypes.LEG_STARTED, min(0), mettuguda.first, mettuguda.second, mapOf("mode" to "METRO")),
            ev(EventTypes.TRAVEL_MODE_CHANGED, min(36), madhapur.first, madhapur.second, mapOf("fromMode" to "METRO", "toMode" to "WALK")),
            ev(EventTypes.TRAVEL_MODE_CHANGED, min(40), walkEnd.first, walkEnd.second, mapOf("fromMode" to "WALK", "toMode" to "CAB"))
        )
        return JourneyStory.Input(
            who = "Prashobh Paul", origin = "Mettuguda, Secunderabad", destination = "Qualizeal Office",
            originLat = mettuguda.first, originLng = mettuguda.second, destLat = office.first, destLng = office.second,
            mode = "METRO", startedAtMs = min(0), endedAtMs = min(55), nowMs = min(60),
            events = events, samples = samples, distanceM = 20_000.0, routeDistanceM = 20_000.0, zone = ZoneId.of("Asia/Kolkata")
        )
    }

    private fun story(i: JourneyStory.Input): JourneyStory.Story {
        val book = PlaceBook()
        JourneyStory.seed(i, book)
        book.add(madhapur.first, madhapur.second, "Madhapur", PlaceBook.Rank.GEOCODED)
        return JourneyStory.build(i, book)
    }

    @Test fun a_few_hundred_metres_between_the_metro_and_the_cab_is_the_change_not_a_walk() {
        val s = story(input(walkM = 300.0))
        assertEquals(listOf("METRO", "CAB"), s.stages.map { it.mode })
        val changes = s.days.flatMap { it.entries }.filterIsInstance<JourneyStory.Entry.Moment>().filter { it.kind == JourneyStory.MomentKind.MODE }
        assertEquals(1, changes.size)
        val change = changes.single()
        assertEquals("Changed from the metro to a cab", change.text)
        assertEquals("CAB", change.mode)
        // The four minutes on the platform and at the kerb are the change's.
        assertEquals(min(36), change.atMs)
        assertEquals(min(40), change.endMs)
        assertTrue(s.headline.contains("by metro"))
    }

    @Test fun a_walk_of_a_kilometre_or_more_is_a_way_of_travelling() {
        val s = story(input(walkM = 1_500.0))
        assertEquals(listOf("METRO", "WALK", "CAB"), s.stages.map { it.mode })
        val changes = s.days.flatMap { it.entries }.filterIsInstance<JourneyStory.Entry.Moment>().filter { it.kind == JourneyStory.MomentKind.MODE }
        assertEquals(listOf("Changed from the metro to walking", "Changed from walking to a cab"), changes.map { it.text })
        assertEquals(listOf("WALK", "CAB"), changes.map { it.mode })
        assertNull(changes.first().endMs)
    }

    @Test fun the_rule_itself() {
        assertTrue(StageRepair.countsAsStage("METRO", 200.0))
        assertFalse(StageRepair.countsAsStage("WALK", 999.0))
        assertTrue(StageRepair.countsAsStage("WALK", 1_000.0))
        // Not known is not zero: a walk the record could not measure is kept.
        assertTrue(StageRepair.countsAsStage("WALK", null))
        val only = listOf("WALK" to 400.0)
        assertEquals(only, StageRepair.ridden(only, { it.first }, { it.second }))
        val legs = listOf("METRO" to 20_000.0, "WALK" to 300.0, "CAB" to 5_000.0)
        assertEquals(listOf("METRO", "CAB"), StageRepair.ridden(legs, { it.first }, { it.second }).map { it.first })
        // Distance along the record, only from fixes inside the stage.
        val pts = fixes(madhapur, madhapur.first + 0.009 to madhapur.second, min(0), min(10))
        val d = StageRepair.pathLengthM(pts, { it.tMs }, { it.lat }, { it.lng }, min(0), min(10))!!
        assertEquals(1_000.0, d, 15.0)
        assertNull(StageRepair.pathLengthM(pts, { it.tMs }, { it.lat }, { it.lng }, min(20), min(30)))
    }

    @Test fun the_stages_table_leaves_the_short_walk_out_too() {
        fun leg(i: Int, mode: String, from: String, to: String, a: Long, b: Long, d: Double) =
            JourneyAnalytics.LegInput(i, mode, from, to, a, b, distanceM = d)
        val legs = listOf(
            leg(0, "METRO", "Mettuguda", "Madhapur", min(0), min(36), 20_000.0),
            leg(1, "WALK", "Madhapur", "Madhapur", min(36), min(40), 300.0),
            leg(2, "CAB", "Madhapur", "Qualizeal Office", min(40), min(55), 5_000.0)
        )
        val report = JourneyAnalytics.analyse(JourneyAnalytics.Inputs(
            events = emptyList(), distanceCoveredM = 25_300.0, startedAtMs = min(0), endedAtMs = min(55), legs = legs, transportMode = "METRO"
        ))
        assertEquals(listOf("METRO", "CAB"), report.legs.map { it.mode })
    }

    @Test fun a_bike_taxi_is_its_own_way_of_travelling() {
        val p = TransportCatalog.profile("BIKE_TAXI")
        assertEquals("Bike taxi", p.label)
        assertFalse(p.isPrivateVehicle)
        assertFalse(p.asksAboutFuel)
        assertTrue(TransportCatalog.COMMUTE.any { it.key == "BIKE_TAXI" })
        assertEquals("bike-taxi", Pictures.mode("BIKE_TAXI"))
        assertFalse(Pictures.modeFacesLeft("BIKE_TAXI"))
        assertEquals(Expenses.Category.BIKE_TAXI, Expenses.Category.fareFor("BIKE_TAXI"))
        assertEquals("by bike taxi", JourneyStory.byMode("BIKE_TAXI"))
        assertEquals("a bike taxi", JourneyStory.vehicleWord("BIKE_TAXI"))
        assertEquals("Got on the bike taxi", p.quickActions.first().timelineText)
    }
}
