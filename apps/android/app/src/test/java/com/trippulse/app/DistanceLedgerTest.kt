package com.trippulse.app

import com.trippulse.app.domain.DistanceLedger
import com.trippulse.app.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceLedgerTest {

    private val t0 = 1_700_000_000_000L
    // ~1 km of latitude per 0.009 degrees
    private fun p(sec: Long, km: Double, speedMps: Double? = null) =
        DistanceLedger.Point(t0 + sec * 1000, 12.0 + km * 0.009, 77.0, speedMps)

    private fun ledger(points: List<DistanceLedger.Point>) = DistanceLedger.reconstruct(points, 8.0, 1.27)

    @Test fun moving_samples_add_up() {
        // 60 km/h: 1 km every 60 s, ten samples
        val pts = (0..10L).map { p(it * 60, it.toDouble()) }
        val r = ledger(pts)
        assertEquals(10_000.0, r.totalM, 60.0)
        assertTrue(r.gaps.isEmpty())
    }

    @Test fun a_parked_phone_does_not_wander_into_kilometres() {
        // 20 s samples jittering 15 m: 2.7 km/h, below the moving threshold
        val pts = (0..300L).map { p(it * 20, if (it % 2 == 0L) 0.0 else 0.015) }
        assertEquals(0.0, ledger(pts).totalM, 1.0)
    }

    @Test fun a_silence_is_credited_as_road_distance() {
        val pts = listOf(p(0, 0.0), p(60, 1.0), p(3 * 3600, 101.0), p(3 * 3600 + 60, 102.0))
        val r = ledger(pts)
        assertEquals(1, r.gaps.size)
        assertEquals(100_000.0 * 1.12, r.gaps[0].metres, 200.0)
        assertEquals(2_000.0 + 100_000.0 * 1.12, r.totalM, 300.0)
        assertEquals(r.gaps[0].metres, r.estimatedM, 0.0)
    }

    @Test fun a_silence_in_a_car_park_is_not_a_drive() {
        val pts = listOf(p(0, 0.0), p(5 * 3600, 0.1))
        val r = ledger(pts)
        assertTrue(r.gaps.isEmpty())
        assertEquals(0.0, r.totalM, 0.0)
    }

    @Test fun the_planned_route_caps_the_credit_but_never_below_the_straight_line() {
        val a = GeoPoint(12.0, 77.0); val b = GeoPoint(12.9, 77.0) // ~100 km
        assertEquals(112_000.0, DistanceLedger.gapCredit(a, b, 1.27), 300.0)
        assertEquals(105_000.0, DistanceLedger.gapCredit(a, b, 1.27, roomM = 105_000.0), 1.0)
        assertEquals(100_000.0, DistanceLedger.gapCredit(a, b, 1.27, roomM = 20_000.0), 300.0)
        assertEquals(0.0, DistanceLedger.gapCredit(a, GeoPoint(12.001, 77.0), 1.27), 0.0)
    }

    @Test fun a_short_hop_bends_more_than_a_long_road() {
        assertEquals(1.27, DistanceLedger.roadFactor(2_000.0, 1.27), 1e-9)
        assertEquals(1.12, DistanceLedger.roadFactor(240_000.0, 1.27), 1e-9)
        val mid = DistanceLedger.roadFactor(55_000.0, 1.27)
        assertTrue(mid > 1.12 && mid < 1.27)
        // A configured factor already below the highway figure is left alone.
        assertEquals(1.05, DistanceLedger.roadFactor(500_000.0, 1.05), 1e-9)
    }

    @Test fun the_live_count_is_kept_when_it_knows_more() {
        val r = ledger(listOf(p(0, 0.0), p(60, 1.0)))
        assertEquals(5_000.0, DistanceLedger.reconcile(5_000.0, r), 0.0)
        assertEquals(r.totalM, DistanceLedger.reconcile(10.0, r), 0.0)
    }
}
