package com.trippulse.app

import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.RouteDeviationDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviationTest {

    // A short north-south corridor near (10.00, 76.00).
    private val route = listOf(GeoPoint(10.00, 76.00), GeoPoint(10.10, 76.00))
    private val onLine = GeoPoint(10.05, 76.00)
    private val offLine = GeoPoint(10.05, 76.05) // ~5.5 km east of the corridor

    @Test fun no_route_means_no_detection() {
        val d = RouteDeviationDetector()
        assertNull(d.onFix(offLine, listOf(GeoPoint(10.0, 76.0)), 0))
    }

    @Test fun on_corridor_never_flags() {
        val d = RouteDeviationDetector()
        assertNull(d.onFix(onLine, route, 0))
        assertNull(d.onFix(onLine, route, 60_000))
        assertFalse(d.active)
    }

    @Test fun brief_detour_under_persist_does_not_flag() {
        val d = RouteDeviationDetector()
        assertNull(d.onFix(offLine, route, 0))          // off starts
        assertNull(d.onFix(onLine, route, 100_000))     // back before persist window
        assertFalse(d.active)
    }

    @Test fun sustained_off_corridor_flags_then_recovers() {
        val d = RouteDeviationDetector()
        assertNull(d.onFix(offLine, route, 0))          // off since t=0
        val dev = d.onFix(offLine, route, 300_000)      // persisted >= 300s
        assertTrue(dev is RouteDeviationDetector.Signal.Deviated)
        assertTrue(d.active)

        val rejoin = d.onFix(onLine, route, 305_000)    // back inside corridor
        assertTrue(rejoin is RouteDeviationDetector.Signal.Rejoined)
        assertFalse(d.active)
    }
}
