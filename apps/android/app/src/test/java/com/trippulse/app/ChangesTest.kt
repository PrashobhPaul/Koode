package com.trippulse.app

import com.trippulse.app.domain.Changes
import com.trippulse.app.domain.Changes.Kind
import com.trippulse.app.domain.Changes.Part
import com.trippulse.app.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The doors and the changes on the commute of 14 October: home, bike taxi, metro, bike taxi, office. */
class ChangesTest {

    private val t0 = 1_791_960_000_000L
    private fun min(m: Double) = t0 + (m * 60_000).toLong()

    private val stages = listOf(
        Changes.Stage("BIKE_TAXI", "Raheja Vistas", "Habsiguda Metro", min(0.0), min(12.0), 2_100.0),
        Changes.Stage("METRO", "Habsiguda Metro", "Durgam Cheruvu Metro", min(12.0), min(51.0), 20_500.0),
        Changes.Stage("BIKE_TAXI", "Durgam Cheruvu Metro", "Qualizeal Office", min(51.0), min(62.0), 1_900.0)
    )

    private fun steps(parts: List<Part>) = parts.filterIsInstance<Part.Step>().map { it.change }

    @Test fun every_journey_has_a_walk_out_a_change_between_rides_and_a_walk_in() {
        val parts = Changes.parts(stages, emptyList(), completed = true)
        val s = steps(parts)
        assertEquals(listOf(Kind.LEAVE, Kind.CHANGE, Kind.CHANGE, Kind.ARRIVE), s.map { it.kind })
        assertEquals(listOf(1L, 2L, 2L, 1L), s.map { it.ms / 60_000 })
        assertTrue(s.none { it.measured })
        // The parts still add up to the whole journey, with no gaps or overlaps.
        assertEquals(min(62.0) - min(0.0), parts.sumOf { it.ms })
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.toMs, b.fromMs) }
        // The metro ride is the time riding: a minute off each end for the stations.
        val metro = parts.filterIsInstance<Part.Ride>()[1]
        assertEquals(37L, metro.ms / 60_000)
        assertEquals("Out of Raheja Vistas, to the bike taxi", Changes.text(s[0]))
        assertEquals("Off the bike taxi, onto the metro", Changes.text(s[1]))
        assertEquals("Off the bike taxi and into Qualizeal Office", Changes.text(s[3]))
    }

    @Test fun a_measured_wait_replaces_the_floor() {
        val waits = listOf(
            Changes.Wait(min(0.0), min(6.0)),     // at the gate for the bike taxi
            Changes.Wait(min(8.0), min(12.0))     // on the platform
        )
        val s = steps(Changes.parts(stages, waits, completed = true))
        assertEquals(6L, s[0].ms / 60_000)
        assertTrue(s[0].measured)
        assertEquals("Out of Raheja Vistas, and the wait for the bike taxi", Changes.text(s[0]))
        // The platform wait and the minute onto the train.
        assertEquals(5L, s[1].ms / 60_000)
        assertEquals(min(8.0), s[1].fromMs)
        assertTrue(s[1].measured)
    }

    @Test fun no_walk_in_while_the_journey_runs_and_no_change_into_a_walk() {
        val live = Changes.parts(stages.take(2).let { it.dropLast(1) + it.last().copy(toMs = min(30.0)) }, emptyList(), completed = false)
        assertEquals(listOf(Kind.LEAVE, Kind.CHANGE), steps(live).map { it.kind })
        val walked = listOf(
            Changes.Stage("METRO", "Habsiguda Metro", "Durgam Cheruvu Metro", min(0.0), min(40.0)),
            Changes.Stage("WALK", "Durgam Cheruvu Metro", "Qualizeal Office", min(40.0), min(60.0))
        )
        // Out of the door to the train, then the walk is the change and the arrival.
        assertEquals(listOf(Kind.LEAVE), steps(Changes.parts(walked, emptyList(), completed = true)).map { it.kind })
    }

    @Test fun a_stage_shorter_than_its_changes_is_never_negative() {
        val short = listOf(
            Changes.Stage("BIKE_TAXI", "Raheja Vistas", "Habsiguda Metro", min(0.0), min(0.2)),
            Changes.Stage("METRO", "Habsiguda Metro", "Durgam Cheruvu Metro", min(0.2), min(40.0))
        )
        val parts = Changes.parts(short, emptyList(), completed = true)
        assertTrue(parts.all { it.ms >= 0 })
        assertEquals(min(40.0) - min(0.0), parts.sumOf { it.ms })
    }

    @Test fun a_bike_taxi_recorded_in_ten_seconds_started_earlier() {
        val home = GeoPoint(17.4250, 78.5560)
        val station = GeoPoint(17.42018, 78.54055)
        val fixed = Changes.lateStartFix(home, station, "BIKE_TAXI", min(14.0), min(14.0) + 10_000)
        assertNotNull(fixed)
        // 1.7 km straight, 2.2 km by road at 22 km/h: six minutes, and the minute out of the door.
        assertEquals(7.0, (min(14.0) + 10_000 - fixed!!) / 60_000.0, 0.6)
        // A plausible ride is left alone, and so is a metro (it begins at its station).
        assertNull(Changes.lateStartFix(home, station, "BIKE_TAXI", min(0.0), min(9.0)))
        assertNull(Changes.lateStartFix(home, station, "METRO", min(14.0), min(14.0) + 10_000))
        // Ends close together say nothing.
        assertNull(Changes.lateStartFix(home, GeoPoint(17.4252, 78.5561), "CAB", min(0.0), min(0.0) + 5_000))
    }
}
