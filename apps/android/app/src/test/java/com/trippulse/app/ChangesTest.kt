package com.trippulse.app

import com.trippulse.app.domain.Changes
import com.trippulse.app.domain.Changes.Kind
import com.trippulse.app.domain.Changes.Part
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.StageSense
import com.trippulse.app.domain.TransitNetwork
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

    // ---- the evening of 14 October: Start pressed on the metro near Begumpet ----

    private val begumpet = GeoPoint(17.43720, 78.46000)
    private val ameerpetArea = GeoPoint(17.43680, 78.45900)   // what "current location" was geocoded as
    private val habsiguda = GeoPoint(17.42018, 78.54055)
    private val raheja = GeoPoint(17.4250, 78.5560)
    private val net = TransitNetwork.parse(
        "S|17.43720|78.46000|Begumpet|Hyderabad Metro|M\nS|17.42018|78.54055|Habsiguda|Hyderabad Metro|M\nL|Hyderabad Metro|Blue|blue|0 1:9000\n"
    )

    @Test fun a_bike_taxi_of_seconds_on_the_metro_line_was_a_tap_put_right() {
        // Seven seconds, from where the train was to the station: not travelled.
        assertTrue(Changes.misTap(ameerpetArea, begumpet, min(0.0), min(0.0) + 7_000, "METRO", net))
        // Ends at the same place, whatever came next: a tap put right.
        assertTrue(Changes.misTap(raheja, GeoPoint(17.4251, 78.5561), min(0.0), min(0.0) + 7_000, "CAB", null))
        // Ten seconds from home 1.7 km off the line: travelled, only late (lateStartFix puts it back).
        assertTrue(!Changes.misTap(raheja, habsiguda, min(0.0), min(0.0) + 10_000, "METRO", net))
        // A real ride of minutes is never folded.
        assertTrue(!Changes.misTap(ameerpetArea, begumpet, min(0.0), min(3.0), "METRO", net))
    }

    private fun move(fromMin: Double, toMin: Double, pace: StageSense.Pace) =
        StageSense.Episode(min(fromMin), min(toMin), pace, 0.0, begumpet, begumpet, 0.0)

    @Test fun changes_are_measured_from_the_movement_where_it_shows_them() {
        val evening = listOf(
            Changes.Stage("METRO", "Begumpet Metro", "Habsiguda Metro", min(0.0), min(17.0)),
            Changes.Stage("BIKE_TAXI", "Habsiguda Metro", "Raheja Vistas", min(17.0), min(33.0))
        )
        // On the train from the first fix; off it at 15, standing and walking out till the bike taxi went at 20; off it at 31.
        val moves = listOf(
            move(0.0, 15.0, StageSense.Pace.RIDING),
            move(15.0, 20.0, StageSense.Pace.STILL),
            move(20.0, 31.0, StageSense.Pace.RIDING),
            move(31.0, 33.0, StageSense.Pace.WALKING)
        )
        val parts = Changes.parts(evening, emptyList(), completed = true, moves = moves)
        val s = steps(parts)
        // Started on board: no walk out. The change and the walk in are what the movement shows.
        assertEquals(listOf(Kind.CHANGE, Kind.ARRIVE), s.map { it.kind })
        assertEquals(min(15.0), s[0].fromMs)
        assertEquals(min(20.0), s[0].toMs)
        assertTrue(s.all { it.measured })
        assertEquals(2L, s[1].ms / 60_000)
        assertEquals(min(33.0) - min(0.0), parts.sumOf { it.ms })
        // Where the fixes show nothing, the floor stands in.
        assertEquals(listOf(Kind.LEAVE, Kind.CHANGE, Kind.ARRIVE), steps(Changes.parts(evening, emptyList(), completed = true)).map { it.kind })
    }
}
