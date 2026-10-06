package com.trippulse.app

import com.trippulse.app.domain.MapStages
import com.trippulse.app.domain.Pictures
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TripConfig
import com.trippulse.app.domain.forMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStagesTest {

    // Cab from the office, a walk to the station, the metro home.
    private val stages = MapStages.of(listOf(1_000L to "CAB", 5_000L to "WALK", 8_000L to "METRO", null to "CAB"))

    @Test fun stages_are_the_legs_that_started() {
        assertEquals(listOf("CAB", "WALK", "METRO"), stages.map { it.mode })
    }

    @Test fun the_mode_at_a_moment_is_the_stage_begun_by_then() {
        assertEquals("CAB", MapStages.modeAt(stages, 500L, "CAR")) // the record began a moment before the leg
        assertEquals("CAB", MapStages.modeAt(stages, 4_999L, "CAR"))
        assertEquals("WALK", MapStages.modeAt(stages, 5_000L, "CAR"))
        assertEquals("METRO", MapStages.modeAt(stages, 99_000L, "CAR"))
        assertEquals("CAR", MapStages.modeAt(emptyList(), 99_000L, "CAR"))
    }

    @Test fun the_trail_splits_where_the_mode_changed_and_stays_joined() {
        val times = listOf(1_000L, 2_000L, 4_000L, 5_500L, 6_000L, 8_500L, 9_000L)
        val runs = MapStages.runs(times, stages, "CAB")
        assertEquals(listOf("CAB", "WALK", "METRO"), runs.map { it.mode })
        assertEquals(MapStages.Run(0, 3, "CAB"), runs[0])
        assertEquals(MapStages.Run(3, 5, "WALK"), runs[1])
        assertEquals(MapStages.Run(5, 6, "METRO"), runs[2])
        assertTrue(MapStages.onFoot(runs[1].mode))
        assertFalse(MapStages.onFoot(runs[2].mode))
    }

    @Test fun one_stage_or_no_times_is_one_line() {
        assertEquals(listOf(MapStages.Run(0, 2, "CAR")), MapStages.runs(listOf(1L, 2L, 3L), emptyList(), "CAR"))
        assertEquals(listOf(MapStages.Run(0, 3, "CAB")), MapStages.runs(emptyList(), stages, "CAB", size = 4))
        assertTrue(MapStages.runs(listOf(1L), stages, "CAB").isEmpty())
    }

    @Test fun points_past_the_record_belong_to_the_latest_stage() {
        val runs = MapStages.runs(listOf(6_000L, 7_000L, Long.MAX_VALUE), stages, "CAB")
        // The walk runs up to the live point, where the metro takes over.
        assertEquals(listOf(MapStages.Run(0, 2, "WALK")), runs)
        assertEquals("METRO", MapStages.modeAt(stages, Long.MAX_VALUE, "CAB"))
    }

    @Test fun a_replay_paces_itself() {
        // Six hours at a fix every 15 s: the whole journey in 25 seconds.
        val n = 1441
        val base = MapStages.replayBaseStep(n)
        val frames = (n - 1) / base
        assertEquals(MapStages.REPLAY_WHOLE_MS / MapStages.REPLAY_FRAME_MS, frames.toLong())
        // A short record is not stretched past six seconds' worth of frames... nor squeezed below.
        assertEquals(19f / (MapStages.REPLAY_MIN_MS / MapStages.REPLAY_FRAME_MS), MapStages.replayBaseStep(20), 1e-4f)
        assertEquals(0f, MapStages.replayBaseStep(1), 0f)
        // A four-minute walk inside the drive gets its two and a half seconds.
        val runs = listOf(MapStages.Run(0, 700, "CAR"), MapStages.Run(700, 716, "WALK"), MapStages.Run(716, 1440, "METRO"))
        assertEquals(base, MapStages.replayStep(100f, runs, base), 0f)
        val walk = MapStages.replayStep(705f, runs, base)
        assertTrue(walk < base)
        assertEquals(MapStages.REPLAY_STAGE_MS / MapStages.REPLAY_FRAME_MS, (16 / walk).toLong())
    }

    // An L-shaped road: 1 km north, then 1 km east.
    private val corner = com.trippulse.app.domain.GeoPoint(17.459, 78.40)
    private val road = (0..10).map { com.trippulse.app.domain.GeoPoint(17.45 + it * 0.0009, 78.40) } +
        (1..10).map { com.trippulse.app.domain.GeoPoint(17.459, 78.40 + it * 0.00094) }

    @Test fun between_two_fixes_the_vehicle_follows_the_road_round_the_bend() {
        val before = com.trippulse.app.domain.GeoPoint(17.4581, 78.40002)   // 100 m before the corner
        val after = com.trippulse.app.domain.GeoPoint(17.45902, 78.40094)   // 100 m after it
        val path = MapStages.alongRoad(road, before, after)!!
        assertTrue("goes through the corner", path.any { com.trippulse.app.core.Geo.haversineM(it, corner) < 1.0 })
        val (mid, heading) = MapStages.pointAlong(path, 0.5)
        assertTrue("halfway is at the corner, not cut across it", com.trippulse.app.core.Geo.haversineM(mid, corner) < 10.0)
        assertTrue(heading != null)
        // Early on it heads north, late on it heads east.
        assertEquals(0.0, MapStages.pointAlong(path, 0.2).second!!, 2.0)
        assertEquals(90.0, MapStages.pointAlong(path, 0.8).second!!, 2.0)
    }

    @Test fun the_road_is_used_only_when_it_explains_the_move() {
        val onRoad = com.trippulse.app.domain.GeoPoint(17.4581, 78.40002)
        val farOff = com.trippulse.app.domain.GeoPoint(17.4581, 78.4100)   // a kilometre east of the road
        assertEquals(null, MapStages.alongRoad(road, onRoad, farOff))
        // Backwards along the road is not this road.
        val later = com.trippulse.app.domain.GeoPoint(17.459, 78.405)
        assertEquals(null, MapStages.alongRoad(road, later, onRoad))
        // No road: no path.
        assertEquals(null, MapStages.alongRoad(emptyList(), onRoad, later))
    }

    @Test fun followers_get_the_next_two_kilometres() {
        val here = com.trippulse.app.domain.GeoPoint(17.4500, 78.40001)
        val ahead = MapStages.roadAhead(road, here, lengthM = 1_500.0)
        assertTrue(ahead.size in 3..40)
        val len = (1 until ahead.size).sumOf { com.trippulse.app.core.Geo.haversineM(ahead[it - 1], ahead[it]) }
        assertTrue("about the length asked for: $len", len in 1_400.0..1_700.0)
        assertTrue(MapStages.roadAhead(road, here, maxPoints = 5).size <= 5)
    }

    @Test fun a_live_glide_lasts_as_long_as_the_gap_between_fixes() {
        assertEquals(15_000L, MapStages.glideMs(15_000L))
        assertEquals(MapStages.GLIDE_MIN_MS, MapStages.glideMs(null))
        assertEquals(MapStages.GLIDE_MAX_MS, MapStages.glideMs(600_000L))
        assertEquals(MapStages.GLIDE_MIN_MS, MapStages.glideMs(200L))
    }

    @Test fun each_stretch_looks_like_what_it_was_travelled_on() {
        assertEquals(MapStages.ROAD, MapStages.look("CAB"))
        assertEquals(MapStages.ROAD, MapStages.look("BUS"))
        assertEquals(MapStages.ROAD, MapStages.look("BIKE"))
        assertEquals(MapStages.CYCLE_LANE, MapStages.look("CYCLE"))
        assertEquals(MapStages.RAIL, MapStages.look("METRO"))
        assertEquals(MapStages.RAIL, MapStages.look("TRAIN"))
        assertEquals(MapStages.WATER, MapStages.look("FERRY"))
        assertEquals(MapStages.WATER, MapStages.look("SHIP"))
        assertEquals(MapStages.FOOT, MapStages.look("WALK"))
        assertEquals(MapStages.AIR, MapStages.look("FLIGHT"))
        // Every mode has a look; an unknown one is a road.
        TransportCatalog.ALL.forEach { assertTrue(MapStages.look(it.key).isNotBlank()) }
        assertEquals(MapStages.ROAD, MapStages.look("HOVERCRAFT"))
    }

    @Test fun the_road_ahead_starts_where_the_traveller_is() {
        val road = (0..10).map { com.trippulse.app.domain.GeoPoint(17.40 + it * 0.01, 78.40) }
        val here = com.trippulse.app.domain.GeoPoint(17.452, 78.401)
        val ahead = MapStages.ahead(road, here)
        assertEquals(here, ahead.first())
        assertEquals(road.last(), ahead.last())
        // Nothing already travelled is drawn ahead.
        assertTrue(ahead.drop(1).all { it.lat > 17.452 })
        assertEquals(road, MapStages.ahead(road, null))
    }

    @Test fun a_cycle_is_its_own_mode_with_its_own_picture_and_pace() {
        val cycle = TransportCatalog.profile("CYCLE")
        assertEquals("CYCLE", cycle.key)
        assertFalse(cycle.asksAboutFuel)
        assertEquals("cycle", Pictures.mode("CYCLE"))
        assertTrue(TransportCatalog.COMMUTE.any { it.key == "CYCLE" })
        assertEquals(5.0, TripConfig().forMode("CYCLE").restartSpeedKmh, 0.0)
        assertEquals("Bicycle", cycle.label)
        // A bike with an engine is called a motorbike: elsewhere a "bike" has pedals.
        assertEquals("Motorbike", TransportCatalog.label("BIKE"))
    }

    @Test fun a_ferry_is_a_crossing_and_a_cruise_is_a_voyage() {
        val ferry = TransportCatalog.profile("FERRY")
        val ship = TransportCatalog.profile("SHIP")
        assertEquals("FERRY", ferry.key)
        assertEquals("Cruise / ship", ship.label)
        // A ferry is boarded like a metro; a cruise insists on its cabin and booking.
        assertTrue(com.trippulse.app.domain.TravelDetails.isComplete("FERRY", emptyMap()))
        assertFalse(com.trippulse.app.domain.TravelDetails.isComplete("SHIP", emptyMap()))
        assertFalse(ferry.expectsOfflineStretches)
        assertTrue(ship.expectsOfflineStretches)
        assertTrue(TransportCatalog.COMMUTE.any { it.key == "FERRY" })
        assertEquals("ferry", Pictures.mode("FERRY"))
        assertEquals("by ferry", com.trippulse.app.domain.report.JourneyStory.byMode("FERRY"))
        assertEquals("by motorbike", com.trippulse.app.domain.report.JourneyStory.byMode("BIKE"))
        assertEquals("by bicycle", com.trippulse.app.domain.report.JourneyStory.byMode("CYCLE"))
        assertEquals("Sailed 3.2 km", com.trippulse.app.domain.report.JourneyStory.stretch("FERRY", 3_200.0))
        // Every commute change is one tap: none of them stops at a form.
        TransportCatalog.COMMUTE.forEach { assertTrue(it.key, com.trippulse.app.domain.TravelDetails.isComplete(it.key, emptyMap())) }
    }
}
