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
