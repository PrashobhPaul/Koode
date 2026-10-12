package com.trippulse.app

import com.trippulse.app.core.Geo
import com.trippulse.app.domain.CameraRules
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.MapStages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trail drawn along the road it was driven on, and the camera's rules
 * for riding along with it.
 */
class RoadTrailTest {

    /** A road that runs east, then bends north round a block, then east again. */
    private val road = listOf(
        GeoPoint(17.4000, 78.5000), GeoPoint(17.4000, 78.5050), GeoPoint(17.4000, 78.5100),
        GeoPoint(17.4020, 78.5100), GeoPoint(17.4040, 78.5100),
        GeoPoint(17.4040, 78.5150), GeoPoint(17.4040, 78.5200)
    )

    @Test fun a_trail_follows_the_road_round_the_bend_between_two_fixes() {
        val index = MapStages.RoadIndex(road)
        // Two fixes either side of the corner, a few metres off the road.
        val fixes = listOf(GeoPoint(17.40003, 78.5080), GeoPoint(17.40303, 78.51004))
        val trail = index.trail(fixes)
        // The corner itself is on the trail now.
        assertTrue(trail.toString(), trail.any { Geo.haversineM(it, GeoPoint(17.4000, 78.5100)) < 1.0 })
        assertTrue(trail.size >= 3)
        // The same answer as the plain snap, through the index.
        assertEquals(MapStages.alongRoad(road, fixes[0], fixes[1], 45.0, 2.0), index.alongRoad(fixes[0], fixes[1], 45.0, 2.0))
    }

    @Test fun a_move_the_road_does_not_explain_stays_a_straight_line() {
        val index = MapStages.RoadIndex(road)
        // Off the road by 300 m: a side street the plan did not know.
        val off = GeoPoint(17.4030, 78.5050)
        val fixes = listOf(GeoPoint(17.40003, 78.5020), off, GeoPoint(17.40003, 78.5090))
        val trail = index.trail(fixes)
        assertTrue(trail.contains(off))
        assertNull(index.alongRoad(fixes[0], off, 45.0, 2.0))
        // Backwards along the road is not along it either.
        assertNull(index.alongRoad(GeoPoint(17.4000, 78.5090), GeoPoint(17.4000, 78.5020)))
    }

    @Test fun a_snapped_stretch_joins_a_raw_one_without_a_jump() {
        val index = MapStages.RoadIndex(road)
        val fixes = listOf(GeoPoint(17.40003, 78.5010), GeoPoint(17.40003, 78.5040), GeoPoint(17.4030, 78.5050))
        val trail = index.trail(fixes)
        // Starts on the road, ends at the raw point that left it; nothing in between further than the fixes themselves.
        assertTrue(Geo.haversineM(trail.first(), fixes.first()) < 5.0)
        assertEquals(fixes.last(), trail.last())
        for (i in 1 until trail.size) assertTrue(Geo.haversineM(trail[i - 1], trail[i]) < 400.0)
    }

    @Test fun a_line_is_thinned_to_the_points_that_shape_it() {
        val pts = (0..100).map { GeoPoint(17.40 + it * 0.0001, 78.50 + (if (it == 50) 0.001 else 0.0)) }
        val kept = MapStages.simplify(pts, 10.0)
        assertEquals(listOf(0, 50, 100), kept.filter { it == 0 || it == 50 || it == 100 })
        assertTrue(kept.toString(), kept.size <= 5)
        // Within tolerance, only the ends remain.
        assertEquals(listOf(0, 100), MapStages.simplify(pts, 200.0))
        assertEquals(listOf(0, 1), MapStages.simplify(pts.take(2), 1.0))
    }

    @Test fun the_camera_rides_closer_on_foot_and_looks_further_ahead_at_speed() {
        assertTrue(CameraRules.zoomFor(4.0, "WALK") > CameraRules.zoomFor(30.0, "CAB"))
        assertTrue(CameraRules.zoomFor(30.0, "CAB") > CameraRules.zoomFor(90.0, "CAR"))
        assertTrue(CameraRules.zoomFor(null, "FLIGHT") < 10.0)
        assertEquals(40.0, CameraRules.lookAheadM(0.0), 0.01)
        assertTrue(CameraRules.lookAheadM(60.0) > CameraRules.lookAheadM(20.0))
        assertEquals(260.0, CameraRules.lookAheadM(500.0), 0.01)
        // Zoom eases: a step at a time, never a jump.
        assertEquals(15.52, CameraRules.eased(15.5, 17.0), 1e-9)
        assertEquals(15.5, CameraRules.eased(15.5, 15.5), 1e-9)
        assertEquals(15.48, CameraRules.eased(15.5, 14.0), 1e-9)
    }

    @Test fun a_point_ahead_along_a_heading() {
        val p = GeoPoint(17.4, 78.5)
        val north = Geo.offset(p, 0.0, 1000.0)
        assertEquals(1000.0, Geo.haversineM(p, north), 1.0)
        assertTrue(north.lat > p.lat && Math.abs(north.lng - p.lng) < 1e-6)
        val east = Geo.offset(p, 90.0, 500.0)
        assertEquals(500.0, Geo.haversineM(p, east), 1.0)
        assertTrue(east.lng > p.lng && Math.abs(east.lat - p.lat) < 1e-4)
    }

    @Test fun replay_speed_comes_from_the_records_own_times() {
        val pts = listOf(GeoPoint(17.40, 78.50), GeoPoint(17.40, 78.51), GeoPoint(17.40, 78.52))
        val times = listOf(0L, 120_000L, 240_000L)
        val v = CameraRules.replaySpeedKmh(pts, times, 1)
        assertNotNull(v)
        assertEquals(Geo.haversineM(pts[0], pts[1]) / 120_000.0 * 3_600.0, v!!, 0.01)
        assertNull(CameraRules.replaySpeedKmh(pts, emptyList(), 1))
    }
}
