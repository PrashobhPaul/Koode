package com.trippulse.app

import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.ui.map.Vehicle3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Vehicle3DTest {

    private val kochi = GeoPoint(9.9312, 76.2673)
    private val modes = listOf("CAR", "BIKE", "CAB", "BUS", "TRAIN", "FLIGHT", "SHIP")

    @Test fun every_mode_builds_closed_solid_rings() {
        modes.forEach { mode ->
            val placed = Vehicle3D.place(mode, kochi, 30.0, metersPerPixel = 2.0)
            assertTrue("$mode has parts", placed.solids.isNotEmpty())
            placed.solids.forEach { s ->
                assertEquals("$mode ring closed", s.ring.first(), s.ring.last())
                assertTrue("$mode top above base", s.topM > s.baseM)
            }
        }
    }

    @Test fun nose_points_along_the_bearing() {
        val north = Vehicle3D.place("CAR", kochi, 0.0, 2.0).solids.first().ring
        assertTrue(north.maxOf { it.lat } - kochi.lat > kochi.lng - north.minOf { it.lng })
        val east = Vehicle3D.place("CAR", kochi, 90.0, 2.0).solids.first().ring
        assertTrue(east.maxOf { it.lng } - kochi.lng > east.maxOf { it.lat } - kochi.lat)
    }

    @Test fun vehicle_keeps_screen_size_as_zoom_changes() {
        val near = Vehicle3D.place("BUS", kochi, 0.0, 1.0).lengthM
        val far = Vehicle3D.place("BUS", kochi, 0.0, 10.0).lengthM
        assertEquals(10.0, far / near, 1e-9)
    }

    @Test fun flights_float_only_when_airborne() {
        val parked = Vehicle3D.place("FLIGHT", kochi, 0.0, 2.0, airborne = false)
        val flying = Vehicle3D.place("FLIGHT", kochi, 0.0, 2.0, airborne = true)
        assertEquals(0.0, parked.solids.minOf { it.baseM }, 1e-9)
        assertTrue(flying.solids.minOf { it.baseM } > 0.0)
    }

    @Test fun unknown_mode_falls_back_to_the_car() {
        assertEquals(Vehicle3D.model("CAR"), Vehicle3D.model("HOVERCRAFT"))
    }

    @Test fun heading_interpolation_takes_the_short_way_round() {
        assertEquals(0.0, Vehicle3D.lerpBearing(350.0, 10.0, 0.5), 1e-9)
        assertEquals(180.0, Vehicle3D.lerpBearing(170.0, 190.0, 0.5), 1e-9)
    }

    @Test fun great_circle_starts_and_ends_at_the_airports() {
        val hyd = GeoPoint(17.2403, 78.4294); val cok = GeoPoint(10.1520, 76.4019)
        val arc = Vehicle3D.greatCircle(hyd, cok, 32)
        assertEquals(33, arc.size)
        assertEquals(hyd.lat, arc.first().lat, 1e-6); assertEquals(cok.lng, arc.last().lng, 1e-6)
        assertEquals(180.0, Vehicle3D.bearing(hyd, GeoPoint(10.0, 78.4294)), 1e-6)
    }
}
