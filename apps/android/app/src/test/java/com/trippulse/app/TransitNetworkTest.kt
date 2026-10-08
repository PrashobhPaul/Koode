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

    /** A country's file, from wherever the tests run (the module, or the repository). */
    private fun country(cc: String): String? = listOf(
        "src/main/assets/transit/$cc.txt", "../../../web/data/transit/$cc.txt",
        "apps/android/app/src/main/assets/transit/$cc.txt", "web/data/transit/$cc.txt",
        "/home/user/Koode/web/data/transit/$cc.txt"
    ).map(::File).firstOrNull { it.exists() }?.readText()

    private val net: TransitNetwork by lazy { TransitNetwork.parse(country("IN")) }

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
        // A rebuild whose ferry query failed once dropped these.
        val water = net.stations.filter { it.kind == TransitNetwork.Kind.WATER && it.network.startsWith("Kochi Water Metro") }
        assertTrue("Kochi Water Metro has ${water.size} terminals", water.size >= 10)
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
            S|17.0|78.0|One|Test Metro|M
            S|bad|78.0|Broken|Test Metro|M
            S|17.02|78.0|Two|Test Metro|M
            L|Test Metro|Line|red|0 2:2400 x:5 9:100
            T|17.5|78.5|Somewhere Junction
            """.trimIndent()
        )
        assertEquals(2, n.stations.size)
        assertEquals(1, n.rail.size)
        // Station numbers are the file's own: the broken row still counts.
        assertEquals(2400.0, n.ride(0, 1)!!.metres, 0.1)
        assertEquals(2400.0, n.ride(1, 0)!!.metres, 0.1)  // one direction mapped serves both
        assertTrue(TransitNetwork.parse(null).isEmpty)
    }

    @Test fun two_countries_combine_without_mixing_their_lines() {
        val a = "S|17.0|78.0|One|A Metro|M\nS|17.02|78.0|Two|A Metro|M\nL|A Metro|L|x|0 1:2400"
        val b = "S|40.0|-73.0|Uno|B Subway|M\nS|40.02|-73.0|Dos|B Subway|M\nL|B Subway|L|x|0 1:2300"
        val n = TransitNetwork.combine(listOf(a, b))
        assertEquals(4, n.stations.size)
        assertEquals(2300.0, n.ride(2, 3)!!.metres, 0.1)
        assertNull(n.ride(0, 2))
    }

    @Test fun labels_say_what_riders_say() {
        fun st(name: String, network: String, kind: TransitNetwork.Kind = TransitNetwork.Kind.METRO) =
            TransitNetwork.Station(0.0, 0.0, name, network, kind)
        assertEquals("Habsiguda Metro", TransitNetwork.label(st("Habsiguda", "Hyderabad Metro")))
        assertEquals("Times Sq-42 St station", TransitNetwork.label(st("Times Sq-42 St", "NYC Subway")))
        assertEquals("Oxford Circus station", TransitNetwork.label(st("Oxford Circus", "London Underground")))
        assertEquals("Vyttila Water Metro", TransitNetwork.label(st("Vyttila", "Kochi Water Metro", TransitNetwork.Kind.WATER)))
        assertEquals("Ernakulam Boat Jetty", TransitNetwork.label(st("Ernakulam Boat Jetty", "KSWTD", TransitNetwork.Kind.WATER)))
    }
}
