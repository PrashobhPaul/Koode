package com.trippulse.app

import com.trippulse.app.domain.EtaEngine
import com.trippulse.app.domain.EtaMode
import com.trippulse.app.domain.JourneyStatus
import com.trippulse.app.domain.TripConfig
import com.trippulse.app.domain.WellbeingTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaEngineTest {

    private val cfg = TripConfig.DEFAULT
    private val now = 1_000_000_000_000L

    private fun inputs(
        travelS: Long, distM: Double, journey: JourneyStatus = JourneyStatus.DRIVING,
        drivingSinceMs: Long? = now, overnight: Boolean = false, provider: String = "osrm"
    ) = EtaEngine.Inputs(
        nowMs = now, remainingTravelSeconds = travelS, remainingDistanceM = distM,
        journey = journey, wellbeing = WellbeingTimes(), drivingSinceMs = drivingSinceMs,
        overnightPending = overnight, distanceCoveredM = 0.0, confidenceProvider = provider
    )

    @Test fun arrived_yields_arrived_mode() {
        val f = EtaEngine.forecast(cfg, inputs(0, 0.0, journey = JourneyStatus.ARRIVED))
        assertEquals(EtaMode.ARRIVED, f.mode)
        assertEquals(now, f.mostLikelyMs)
    }

    @Test fun overnight_yields_pending_with_null_times() {
        val f = EtaEngine.forecast(cfg, inputs(3600, 100_000.0, journey = JourneyStatus.OVERNIGHT))
        assertEquals(EtaMode.OVERNIGHT_PENDING, f.mode)
        assertNull(f.lowMs); assertNull(f.highMs); assertNull(f.mostLikelyMs)
    }

    @Test fun overnight_pending_flag_also_triggers_pending() {
        val f = EtaEngine.forecast(cfg, inputs(3600, 100_000.0, overnight = true))
        assertEquals(EtaMode.OVERNIGHT_PENDING, f.mode)
    }

    @Test fun long_trip_enforces_minimum_break_buffer() {
        // 6h of remaining driving over 300 km
        val f = EtaEngine.forecast(cfg, inputs(6 * 3600, 300_000.0))
        assertEquals(EtaMode.NORMAL, f.mode)
        val bd = f.breakdown
        assertNotNull(bd)
        assertTrue(
            "break budget should meet the long-trip minimum",
            bd!!.breakBudgetSeconds >= cfg.minimumLongTripBreakBudgetMin * 60
        )
    }

    @Test fun range_is_ordered_low_le_likely_le_high() {
        val f = EtaEngine.forecast(cfg, inputs(4 * 3600, 220_000.0))
        assertTrue(f.lowMs!! <= f.mostLikelyMs!!)
        assertTrue(f.mostLikelyMs!! <= f.highMs!!)
    }

    @Test fun breakdown_includes_road_travel_component_first() {
        val f = EtaEngine.forecast(cfg, inputs(2 * 3600, 120_000.0))
        val comps = f.breakdown!!.components
        assertEquals("Road travel", comps.first().label)
    }

    @Test fun realistic_eta_exceeds_pure_travel_time_on_long_trip() {
        val travelS = 6 * 3600L
        val f = EtaEngine.forecast(cfg, inputs(travelS, 300_000.0))
        val remainingMs = f.mostLikelyMs!! - now
        assertTrue("realistic ETA must add breaks + buffer", remainingMs > travelS * 1000)
    }

    @Test fun fallback_provider_is_low_confidence() {
        val f = EtaEngine.forecast(cfg, inputs(3 * 3600, 150_000.0, provider = "fallback"))
        assertEquals("LOW", f.confidence)
    }
}
