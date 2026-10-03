package com.trippulse.app

import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.TollPlazas
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TollPlazas.Plaza
import com.trippulse.app.domain.TollPlazas.Recent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tolls from location: a plaza counts once when the vehicle's path goes
 * through it, never for passing nearby, never twice for one plaza.
 */
class TollPlazasTest {

    // Paliyekkara, NH 544: two booths across the carriageway, ~40 m apart.
    private val boothA = Plaza("n1", 10.37420, 76.30300, "Paliyekkara Toll Plaza")
    private val boothB = Plaza("n2", 10.37420, 76.30340, "Paliyekkara Toll Plaza")
    // Another plaza ~60 km north.
    private val other = Plaza("n3", 10.91000, 76.20000, "Kumbalam Toll Plaza")
    private val index = TollPlazas.Index(listOf(boothA, boothB, other))
    private val t0 = 1_759_000_000_000L

    @Test fun plazas_on_the_road_of_a_silence_are_found_and_timed_by_their_share_of_it() {
        // A road south to north through Paliyekkara, then on past Kumbalam, over two hours of silence.
        val road = listOf(
            com.trippulse.app.domain.GeoPoint(10.30000, 76.30320),
            com.trippulse.app.domain.GeoPoint(10.37240, 76.30320),
            com.trippulse.app.domain.GeoPoint(10.37600, 76.30320),
            com.trippulse.app.domain.GeoPoint(10.60000, 76.25000),
            com.trippulse.app.domain.GeoPoint(10.90990, 76.20000),
            com.trippulse.app.domain.GeoPoint(10.91010, 76.20000),
            com.trippulse.app.domain.GeoPoint(11.00000, 76.20000)
        )
        val found = TollPlazas.alongPath(index, road, t0, t0 + 2 * 3600_000L, emptyList())
        assertEquals(listOf("Paliyekkara Toll Plaza", "Kumbalam Toll Plaza"), found.map { it.plaza.name })
        // Paliyekkara is about a tenth of the way, Kumbalam most of the way.
        assertTrue(found[0].atMs in (t0 + 5 * 60_000L)..(t0 + 25 * 60_000L))
        assertTrue(found[1].atMs in (t0 + 90 * 60_000L)..(t0 + 115 * 60_000L))
        // A plaza already recorded nearby is not counted again.
        val again = TollPlazas.alongPath(index, road, t0, t0 + 2 * 3600_000L, listOf(Recent(boothA.lat, boothA.lng, t0 + 10 * 60_000L)))
        assertEquals(listOf("Kumbalam Toll Plaza"), again.map { it.plaza.name })
        // A road that keeps away from the plazas finds nothing.
        val away = listOf(com.trippulse.app.domain.GeoPoint(10.30000, 76.40000), com.trippulse.app.domain.GeoPoint(11.00000, 76.40000))
        assertTrue(TollPlazas.alongPath(index, away, t0, t0 + 3600_000L, emptyList()).isEmpty())
    }

    @Test fun driving_through_a_plaza_between_two_fixes_counts_it() {
        // 400 m apart, straddling the booths: no fix is at the plaza itself.
        val hit = TollPlazas.crossed(index, 10.37240, 76.30320, 10.37600, 76.30320, t0, emptyList())
        assertEquals("Paliyekkara Toll Plaza", hit?.name)
    }

    @Test fun passing_nearby_on_another_road_does_not() {
        // A parallel road ~300 m east of the booths.
        assertNull(TollPlazas.crossed(index, 10.37240, 76.30600, 10.37600, 76.30600, t0, emptyList()))
    }

    @Test fun one_plaza_is_one_toll_however_many_booths_or_fixes() {
        val recent = listOf(Recent(boothA.lat, boothA.lng, t0))
        // Crawling through the queue: another segment through the other booth.
        assertNull(TollPlazas.crossed(index, 10.37400, 76.30340, 10.37450, 76.30340, t0 + 60_000, recent))
        // The same plaza much later (the return trip) counts again.
        assertEquals(
            "Paliyekkara Toll Plaza",
            TollPlazas.crossed(index, 10.37600, 76.30320, 10.37240, 76.30320, t0 + 4 * 3_600_000, recent)?.name
        )
        // A different plaza straight after counts.
        assertEquals(
            "Kumbalam Toll Plaza",
            TollPlazas.crossed(index, 10.90900, 76.20000, 10.91100, 76.20000, t0 + 60_000, recent)?.name
        )
    }

    @Test fun an_unnamed_booth_takes_its_plazas_name() {
        val unnamed = Plaza("n7", 11.20000, 76.00000, null)
        val named = Plaza("n8", 11.20150, 76.00000, "Walayar Toll Plaza")   // ~170 m away, same plaza
        val idx = TollPlazas.Index(listOf(unnamed, named))
        val hit = TollPlazas.crossed(idx, 11.19900, 75.99995, 11.19990, 75.99995, t0, emptyList())
        assertEquals("n7", hit?.id)
        assertEquals("Walayar Toll Plaza", hit?.name)
    }

    @Test fun nothing_is_inferred_across_a_gps_gap() {
        // 60 km in one jump (tunnel, phone off): the plaza may lie on it, but we can't know.
        assertNull(TollPlazas.crossed(index, 10.37000, 76.30320, 10.91100, 76.20000, t0, emptyList()))
    }

    @Test fun only_fixes_accurate_enough_to_place_a_lane_are_used() {
        assertTrue(TollPlazas.usable(12.0))
        assertTrue(TollPlazas.usable(75.0))
        assertFalse(TollPlazas.usable(180.0))
        assertFalse(TollPlazas.usable(null))
    }

    @Test fun the_plaza_list_round_trips_including_names_with_commas() {
        val list = listOf(boothA, Plaza("w9", 12.9, 77.5, "Toll Plaza, Hosur Road"), Plaza("n4", 20.1, 73.2, null))
        assertEquals(list, TollPlazas.decode(TollPlazas.encode(list)))
        assertTrue(TollPlazas.decode("garbage\n1,2").isEmpty())
    }

    @Test fun the_index_finds_only_what_is_near() {
        assertEquals(2, index.near(10.37420, 76.30320, 200.0).size)
        assertEquals(0, index.near(12.0, 77.0, 5_000.0).size)
    }

    @Test fun car_and_bike_can_log_a_missed_toll_public_transport_cannot() {
        fun offers(p: com.trippulse.app.domain.TransportProfile) = p.quickActions.any { it.eventType == EventTypes.TOLL_CROSSED }
        assertTrue(offers(TransportCatalog.CAR))
        assertTrue(offers(TransportCatalog.BIKE))
        assertFalse(offers(TransportCatalog.TRAIN))
        assertFalse(offers(TransportCatalog.BUS))
    }
}
