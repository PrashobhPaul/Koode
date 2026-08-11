package com.trippulse.app

import com.trippulse.app.domain.Fix
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.StopDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopDetectorTest {

    private val point = GeoPoint(10.0, 76.0)
    private fun fix(tMs: Long, speedMps: Float, p: GeoPoint = point) =
        Fix(point = p, accuracyM = 5f, speedMps = speedMps, bearing = null, timeMs = tMs, batteryPct = 80)

    @Test fun driving_starts_when_moving() {
        val d = StopDetector()
        val m = d.onFix(fix(0, 10f)) // 36 km/h
        assertTrue(m is StopDetector.Movement.DrivingStarted)
    }

    @Test fun traffic_light_stop_is_not_a_break() {
        val d = StopDetector()
        d.onFix(fix(0, 10f))                 // driving
        assertNull(d.onFix(fix(1_000, 0f)))  // becomes candidate, no emit
        // 60s stationary is a traffic light, well under the 300s confirm window
        assertNull(d.onTick(61_000))
    }

    @Test fun genuine_multi_minute_stop_is_detected_then_restart() {
        val d = StopDetector()
        d.onFix(fix(0, 10f))
        d.onFix(fix(1_000, 0f))              // candidate at t=1s
        // matures at >= 300s of dwell
        val started = d.onTick(301_000)
        assertTrue(started is StopDetector.Movement.StopStarted)
        assertTrue(d.isStationary())

        val ended = d.onFix(fix(302_000, 10f)) // rolls again
        assertTrue(ended is StopDetector.Movement.StopEnded)
        val dur = (ended as StopDetector.Movement.StopEnded).durationS
        // stop began at t=1s, ended at t=302s -> ~301s
        assertEquals(301L, dur)
    }

    @Test fun confirmed_stop_becomes_long_stop_after_threshold() {
        val d = StopDetector()
        d.onFix(fix(0, 10f))
        d.onFix(fix(1_000, 0f))
        d.onTick(301_000)                    // StopStarted
        val long = d.onTick(1_000 + 7_200_000) // 2h dwell
        assertTrue(long is StopDetector.Movement.LongStop)
    }

    @Test fun stop_start_reports_when_dwell_matures_on_fixes() {
        val d = StopDetector()
        d.onFix(fix(0, 10f))
        d.onFix(fix(1_000, 0f))
        // a later stationary fix beyond the confirm window should also mature it
        val started = d.onFix(fix(305_000, 0f))
        assertTrue(started is StopDetector.Movement.StopStarted)
    }
}
