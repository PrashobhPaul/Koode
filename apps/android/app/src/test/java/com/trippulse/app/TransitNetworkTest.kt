package com.trippulse.app

import com.trippulse.app.core.Geo
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.TransitNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Metro rides named by their stations and measured along the track, against
 * the network bundled with the app (OpenStreetMap data).
 */
class TransitNetworkTest {

    private fun asset(name: String): String? = listOf(
        "src/main/assets/$name", "app/src/main/assets/$name", "apps/android/app/src/main/assets/$name",
        "/home/user/Koode/apps/android/app/src/main/assets/$name"
    ).map(::File).firstOrNull { it.exists() }?.readText()

    private val net: TransitNetwork by lazy { TransitNetwork.parse(asset("metro_network.txt"), asset("rail_stations.txt")) }

    // Stations of Hyderabad Metro's Blue Line, as mapped.
    private val habsiguda = GeoPoint(17.42018, 78.54055)
    private val tarnaka = GeoPoint(17.42830, 78.52844)
    private val yusufguda = GeoPoint(17.43511, 78.42732)
    private val ameerpet = GeoPoint(17.43529, 78.44478)
    private val miyapur = GeoPoint(17.49654, 78.37303)
    private val raidurg = GeoPoint(17.44218, 78.37718)

    /** [p] moved [m] metres north. */
    private fun north(p: GeoPoint, m: Double) = GeoPoint(p.lat + m / 110_540.0, p.lng)

    @Test fun the_bundled_network_covers_the_south_indian_metros() {
        val networks = net.stations.map { it.network }.toSet()
        listOf("Hyderabad Metro", "Kochi Metro", "Namma Metro", "Chennai Metro").forEach {
            assertTrue("$it is missing", it in networks)
        }
        assertTrue(net.stations.size > 500)
    }

    @Test fun getting_off_at_habsiguda_is_habsiguda_not_the_area_around_it() {
        // At the gate, 150 m from the platforms.
        assertEquals("Habsiguda Metro", net.stationLabel(north(habsiguda, 150.0), listOf("METRO")))
        assertEquals("Tarnaka Metro", net.stationLabel(north(tarnaka, 100.0), listOf("METRO")))
        // A bike taxi stage alone names nothing after a station.
        assertNull(net.stationLabel(habsiguda, listOf("BIKE_TAXI")))
        // Nowhere near a station.
        assertNull(net.stationLabel(north(habsiguda, 2_000.0), listOf("METRO")))
    }

    @Test fun a_ride_is_measured_along_the_track_never_the_straight_line() {
        val ride = net.rideM(yusufguda, habsiguda)
        assertNotNull(ride)
        val straight = Geo.haversineM(yusufguda, habsiguda)
        // Eleven stops along the Blue Line, its bends included: about 14.7 km.
        assertTrue("ride $ride vs straight $straight", ride!! > straight * 1.1)
        assertEquals(14_700.0, ride, 400.0)
        // The other way round is the same ride, within the two tracks' difference.
        assertEquals(ride, net.rideM(habsiguda, yusufguda)!!, 200.0)
    }

    @Test fun a_ride_changes_lines_at_an_interchange() {
        // Miyapur is on the Red Line, Raidurg on the Blue: change at Ameerpet.
        val ride = net.ride(net.nearestMetro(miyapur)!!, net.nearestMetro(raidurg)!!)
        assertNotNull(ride)
        assertTrue(ride!!.stations.any { net.stations[it].name == "Ameerpet" })
        assertTrue(ride.metres > Geo.haversineM(miyapur, ameerpet) + Geo.haversineM(ameerpet, raidurg) * 0.95)
    }

    @Test fun kochi_metro_rides_too() {
        val aluva = GeoPoint(10.11010, 76.34952)
        val edappally = GeoPoint(10.02549, 76.30798)
        val ride = net.rideM(aluva, edappally)
        assertNotNull(ride)
        assertTrue(ride!! >= Geo.haversineM(aluva, edappally))
    }

    @Test fun a_ride_is_drawn_through_its_stations() {
        val drawn = net.followLine(listOf(north(yusufguda, 50.0), north(habsiguda, 80.0)))
        assertTrue(drawn.size > 10)
        assertEquals(north(yusufguda, 50.0), drawn.first())
        assertEquals(north(habsiguda, 80.0), drawn.last())
        assertTrue(drawn.any { Geo.haversineM(it, ameerpet) < 50 })
        // Not at a station: drawn as recorded.
        val loose = listOf(north(habsiguda, 3_000.0), north(habsiguda, 4_000.0))
        assertTrue(net.followLine(loose) === loose)
    }

    @Test fun train_stages_are_named_after_their_railway_station() {
        assertEquals("Secunderabad Junction", net.stationLabel(GeoPoint(17.4342, 78.5025), listOf("TRAIN")))
        assertEquals("Kacheguda station", net.stationLabel(GeoPoint(17.3895, 78.4990), listOf("TRAIN")))
    }

    @Test fun malformed_rows_are_skipped_not_fatal() {
        val n = TransitNetwork.parse(
            """
            # comment
            S|17.0|78.0|One|Test
            S|bad|78.0|Broken|Test
            S|17.01|78.0|Two|Test
            L|Test|Line|red|0 1:1200 x:5 9:100
            """.trimIndent()
        )
        assertEquals(2, n.stations.size)
        assertEquals(1200.0, n.ride(0, 1)!!.metres, 0.1)
        assertEquals(1200.0, n.ride(1, 0)!!.metres, 0.1)  // one direction mapped serves both
        assertTrue(TransitNetwork.parse(null).isEmpty)
    }
}
