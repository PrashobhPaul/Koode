package com.trippulse.app

import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.StageSense
import com.trippulse.app.domain.StageSense.Fix
import com.trippulse.app.domain.StageSense.Leg
import com.trippulse.app.domain.StageSense.Pace
import com.trippulse.app.domain.StageSense.Reason
import com.trippulse.app.domain.TransitNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The commute of 12 October, as the phone saw it: a bike taxi from Raheja
 * Vistas to Habsiguda, the metro to Durgam Cheruvu, a bike taxi to the
 * office -- and "got off" tapped only at the office, so the record said the
 * metro went there. The movement says otherwise, and Koode reads it.
 */
class StageSenseTest {

    private val t0 = 1_791_788_160_000L // 12 Oct, 10:36 IST
    private fun min(m: Double) = t0 + (m * 60_000L).toLong()

    private val raheja = GeoPoint(17.4250, 78.5560)
    private val habsiguda = GeoPoint(17.42018, 78.54055)
    private val tarnaka = GeoPoint(17.42780, 78.52780)
    private val mettuguda = GeoPoint(17.43920, 78.51660)
    private val secunderabad = GeoPoint(17.43380, 78.50180)
    private val ameerpet = GeoPoint(17.43720, 78.44800)
    private val madhapur = GeoPoint(17.44820, 78.39170)
    private val durgamCheruvu = GeoPoint(17.44400, 78.38370)
    private val hitec = GeoPoint(17.44740, 78.38140)
    private val office = GeoPoint(17.43230, 78.38770)

    /** A network with the stations the ride passes and the track between them as straight runs. */
    private val net: TransitNetwork = TransitNetwork.parse(
        listOf(habsiguda, tarnaka, mettuguda, secunderabad, ameerpet, madhapur, durgamCheruvu, hitec).mapIndexed { i, p ->
            "S|%.5f|%.5f|%s|Hyderabad Metro|M".format(p.lat, p.lng, listOf("Habsiguda", "Tarnaka", "Mettuguda", "Secunderabad East", "Ameerpet", "Madhapur", "Durgam Cheruvu", "HITEC City")[i])
        }.joinToString("\n") + "\nL|Hyderabad Metro|Blue|blue|0 1:1500 2:1700 3:1600 4:5700 5:6000 6:900 7:450\n"
    )

    /** Fixes every [stepS] seconds from [a] to [b] over [fromMin]..[toMin], at the pace that implies. */
    private fun run(a: GeoPoint, b: GeoPoint, fromMin: Double, toMin: Double, stepS: Int = 10): List<Fix> {
        val n = ((toMin - fromMin) * 60 / stepS).toInt().coerceAtLeast(1)
        return (0..n).map { i ->
            val f = i / n.toDouble()
            Fix(min(fromMin) + (min(toMin) - min(fromMin)) * i / n, a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
        }
    }

    /** Standing at [p] with the GPS wandering a little. */
    private fun stand(p: GeoPoint, fromMin: Double, toMin: Double, stepS: Int = 15): List<Fix> {
        val n = ((toMin - fromMin) * 60 / stepS).toInt().coerceAtLeast(1)
        return (0..n).map { i ->
            val w = if (i % 2 == 0) 0.00012 else -0.00010
            Fix(min(fromMin) + (min(toMin) - min(fromMin)) * i / n, p.lat + w, p.lng - w, speedMps = 0.0)
        }
    }

    private fun ride(vararg legs: Pair<GeoPoint, GeoPoint>, fromMin: Double, minutesEach: Double): List<Fix> {
        var at = fromMin
        return legs.flatMap { (a, b) -> run(a, b, at, at + minutesEach).also { at += minutesEach } }
    }

    /** The whole morning: the stand for the bike taxi, the ride, the metro, the stand at Durgam Cheruvu, the bike taxi, the office. */
    private fun morning(): List<Fix> =
        stand(raheja, 0.0, 7.0) +
            run(raheja, habsiguda, 7.0, 15.0) +
            stand(habsiguda, 15.0, 19.0) +
            ride(habsiguda to tarnaka, tarnaka to mettuguda, mettuguda to secunderabad, secunderabad to ameerpet, ameerpet to madhapur, madhapur to durgamCheruvu, fromMin = 19.0, minutesEach = 5.5) +
            stand(durgamCheruvu, 52.0, 58.0) +
            run(durgamCheruvu, office, 58.0, 64.0) +
            stand(office, 64.0, 70.0)

    private fun legs(metroEndsAt: GeoPoint, metroEndMin: Double) = listOf(
        Leg(0, "BIKE_TAXI", "Raheja Vistas, Nacharam", raheja, "Habsiguda Metro", habsiguda, min(0.0), min(19.0)),
        Leg(1, "METRO", "Habsiguda Metro", habsiguda, if (metroEndsAt == office) "Qualizeal Office" else "Durgam Cheruvu Metro", metroEndsAt, min(19.0), min(metroEndMin))
    )

    @Test fun the_record_is_cut_into_stands_and_moves_at_their_pace() {
        val eps = StageSense.episodes(morning())
        val paces = eps.map { it.pace }
        assertEquals(paces.toString(), listOf(Pace.STILL, Pace.RIDING, Pace.STILL, Pace.RIDING, Pace.STILL, Pace.RIDING, Pace.STILL), paces)
        assertEquals(7, eps[0].ms / 60_000L)
        assertTrue(eps[1].kmh > 12)
        assertEquals(6, eps[4].ms / 60_000L)
    }

    @Test fun a_walk_reads_as_a_walk_and_a_signal_stop_is_not_a_stand() {
        val walk = run(durgamCheruvu, office, 0.0, 22.0, stepS = 8)   // 1.9 km in 22 min
        assertEquals(listOf(Pace.WALKING), StageSense.episodes(walk).map { it.pace })
        // A minute at a red light inside a ride is part of the ride.
        val withSignal = run(raheja, habsiguda, 0.0, 4.0) + stand(habsiguda, 4.0, 5.0) + run(habsiguda, tarnaka, 5.0, 9.0)
        assertEquals(listOf(Pace.RIDING), StageSense.episodes(withSignal).map { it.pace })
    }

    @Test fun the_metro_ended_at_durgam_cheruvu_and_a_bike_taxi_took_over() {
        val splits = StageSense.splits(legs(office, 64.0), morning(), net, lastMileRide = "BIKE_TAXI")
        assertEquals(1, splits.size)
        val s = splits[0]
        assertEquals(1, s.legIndex)
        assertEquals(Reason.LEFT_LINE, s.reason)
        assertEquals("Durgam Cheruvu Metro", s.placeName)
        assertEquals("BIKE_TAXI", s.mode)
        assertEquals(Pace.RIDING, s.pace)
        // It ended when the train reached the station, 52 minutes in.
        assertTrue("${(s.atMs - t0) / 60_000.0}", (s.atMs - t0) in min(51.7) - t0..min(52.3) - t0)
        // And the six minutes standing there were the wait for the bike taxi.
        val w = s.wait
        assertNotNull(w)
        assertEquals(6, w!!.minutes)
        assertEquals("BIKE_TAXI", w.forMode)
        assertEquals("Waited 6 min at Durgam Cheruvu Metro for a bike taxi", StageSense.waitText(w))
        assertEquals("Off the metro at Durgam Cheruvu Metro; by bike taxi from there (noticed from the movement)", StageSense.splitText(s, "METRO"))
    }

    @Test fun walked_from_the_station_when_the_pace_says_so() {
        val fixes = stand(raheja, 0.0, 7.0) + run(raheja, habsiguda, 7.0, 15.0) +
            ride(habsiguda to tarnaka, tarnaka to mettuguda, mettuguda to secunderabad, secunderabad to ameerpet, ameerpet to madhapur, madhapur to durgamCheruvu, fromMin = 15.0, minutesEach = 5.5) +
            run(durgamCheruvu, office, 48.0, 70.0, stepS = 8)
        val s = StageSense.splits(legs(office, 70.0), fixes, net, "BIKE_TAXI").single()
        assertEquals("WALK", s.mode)
        assertEquals(Pace.WALKING, s.pace)
        assertNull(s.wait)
        assertEquals("Off the metro at Durgam Cheruvu Metro; on foot from there (noticed from the movement)", StageSense.splitText(s, "METRO"))
    }

    @Test fun a_ride_ended_by_a_tap_at_its_station_is_left_alone() {
        val splits = StageSense.splits(legs(durgamCheruvu, 52.0) + listOf(
            Leg(2, "BIKE_TAXI", "Durgam Cheruvu Metro", durgamCheruvu, "Qualizeal Office", office, min(52.0), min(64.0))
        ), morning(), net, "BIKE_TAXI")
        assertTrue(splits.toString(), splits.isEmpty())
    }

    @Test fun a_ride_still_on_its_line_is_not_ended() {
        // Half way, between Secunderabad and Ameerpet: nothing has left the line.
        val fixes = ride(habsiguda to tarnaka, tarnaka to mettuguda, mettuguda to secunderabad, fromMin = 19.0, minutesEach = 5.5) +
            run(secunderabad, ameerpet, 35.5, 38.0)
        val open = Leg(1, "METRO", "Habsiguda Metro", habsiguda, "Qualizeal Office", office, min(19.0), null)
        assertTrue(StageSense.splits(listOf(open), fixes, net, "BIKE_TAXI").isEmpty())
    }

    @Test fun the_waits_are_told_where_the_stages_began() {
        val fixes = morning()
        val lg = legs(office, 64.0)
        val splits = StageSense.splits(lg, fixes, net, "BIKE_TAXI")
        val waits = StageSense.waits(lg, fixes, splits)
        assertEquals(waits.map { StageSense.waitText(it) }.toString(), 3, waits.size)
        assertEquals("Waited 7 min at Raheja Vistas, Nacharam for a bike taxi", StageSense.waitText(waits[0]))
        assertEquals("Waited 4 min at Habsiguda Metro for the metro", StageSense.waitText(waits[1]))
        assertEquals("Waited 6 min at Durgam Cheruvu Metro for a bike taxi", StageSense.waitText(waits[2]))
        assertEquals(listOf(0, 1, 2), waits.map { it.legIndex })
    }

    @Test fun a_cab_that_never_came_reads_as_the_walk_it_was() {
        val farther = GeoPoint(17.4160, 78.5560) // a kilometre south
        val fixes = stand(raheja, 0.0, 9.0) + run(raheja, farther, 9.0, 21.0, stepS = 8) + stand(farther, 21.0, 24.0)
        val cab = Leg(0, "CAB", "Raheja Vistas, Nacharam", raheja, "Qualizeal Office", office, min(0.0), min(24.0))
        val s = StageSense.splits(listOf(cab), fixes, net, "BIKE_TAXI").single()
        assertEquals(Reason.WALKED_INSTEAD, s.reason)
        assertEquals("WALK", s.mode)
        assertEquals("Waited 9 min at Raheja Vistas, Nacharam for a cab, then walked", StageSense.waitText(s.wait!!))
        // Told once, as the split's own wait, not again as the stage's.
        assertEquals(1, StageSense.waits(listOf(cab), fixes, listOf(s)).size)
        // A cab that did come, after a walk to the corner, is a cab.
        val came = stand(raheja, 0.0, 9.0) + run(raheja, farther, 9.0, 21.0, stepS = 8) + run(farther, office, 21.0, 45.0)
        assertTrue(StageSense.splits(listOf(cab.copy(completedAtMs = min(45.0))), came, net, "BIKE_TAXI").isEmpty())
    }

    @Test fun the_last_mile_is_made_the_way_the_journey_already_went() {
        val lg = legs(office, 64.0)
        assertEquals("BIKE_TAXI", StageSense.usualRide(lg, before = 1, default = "CAB"))
        assertEquals("CAB", StageSense.usualRide(lg.drop(1), before = 1, default = "CAB"))
    }

    @Test fun how_far_off_the_line_a_place_is() {
        assertEquals(0.0, net.offLineM(tarnaka, TransitNetwork.Kind.METRO)!!, 1.0)
        // On the straight run between two stations.
        val mid = GeoPoint((tarnaka.lat + mettuguda.lat) / 2, (tarnaka.lng + mettuguda.lng) / 2)
        assertTrue(net.offLineM(mid, TransitNetwork.Kind.METRO)!! < 5.0)
        // The office is well over a kilometre from the line.
        assertTrue(net.offLineM(office, TransitNetwork.Kind.METRO)!! > 1_000.0)
        assertNull(net.offLineM(GeoPoint(12.97, 77.59), TransitNetwork.Kind.METRO))
    }
}
