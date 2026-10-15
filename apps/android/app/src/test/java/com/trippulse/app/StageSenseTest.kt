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
        assertEquals("BIKE_TAXI", StageSense.usualRide(lg, near = 1, default = "CAB"))
        assertEquals("CAB", StageSense.usualRide(lg.drop(1), near = 1, default = "CAB"))
    }

    // ---- the evening of the same day: "metro" chosen at the office, a bike taxi to the station ----

    /** The stand at the office, the bike taxi to Durgam Cheruvu, four minutes on the platform, the metro to Habsiguda, the stand there, the bike taxi home. */
    private fun evening(): List<Fix> =
        stand(office, 0.0, 4.0) +
            run(office, durgamCheruvu, 4.0, 10.0) +
            stand(durgamCheruvu, 10.0, 14.0) +
            ride(durgamCheruvu to madhapur, madhapur to ameerpet, ameerpet to secunderabad, secunderabad to mettuguda, mettuguda to tarnaka, tarnaka to habsiguda, fromMin = 14.0, minutesEach = 5.5) +
            stand(habsiguda, 47.0, 51.0) +
            run(habsiguda, raheja, 51.0, 59.0) +
            stand(raheja, 59.0, 62.0)

    /** As recorded: the metro from the office (no one tapped the bike taxi), then the bike taxi tapped at Habsiguda. */
    private fun eveningLegs() = listOf(
        Leg(0, "METRO", "Qualizeal Office", office, "Habsiguda Metro", habsiguda, min(0.0), min(47.0)),
        Leg(1, "BIKE_TAXI", "Habsiguda Metro", habsiguda, "Raheja Vistas, Nacharam", raheja, min(47.0), min(62.0))
    )

    @Test fun the_metro_began_at_durgam_cheruvu_and_a_bike_taxi_got_there() {
        val s = StageSense.splits(eveningLegs(), evening(), net, "BIKE_TAXI").single()
        assertEquals(0, s.legIndex)
        assertEquals(Reason.BEFORE_LINE, s.reason)
        assertEquals("Durgam Cheruvu Metro", s.placeName)
        assertEquals("BIKE_TAXI", s.mode)
        assertEquals(Pace.RIDING, s.pace)
        // It is told from when the station was reached, 10 minutes in: the bike taxi ended at its door,
        // and the four minutes on the platform are the change between the two, part of neither.
        assertTrue("${(s.atMs - t0) / 60_000.0}", (s.atMs - t0) in min(9.5) - t0..min(10.1) - t0)
        assertNull(s.wait)
        assertEquals("By bike taxi from Qualizeal Office to Durgam Cheruvu Metro (noticed from the movement)", StageSense.firstMileText(s, "Qualizeal Office"))
        assertEquals("On the metro from Durgam Cheruvu Metro (noticed from the movement)", StageSense.splitText(s, "METRO"))
    }

    @Test fun the_stand_at_the_office_was_for_the_bike_taxi_not_the_metro() {
        val lg = eveningLegs()
        val fixes = evening()
        val splits = StageSense.splits(lg, fixes, net, "BIKE_TAXI")
        // Before the split is applied: the record still says the metro left the office.
        val before = StageSense.waits(lg, fixes, splits)
        assertEquals(before.map { StageSense.waitText(it) }.toString(), 2, before.size)
        assertEquals("Waited 4 min at Qualizeal Office for a bike taxi", StageSense.waitText(before[0]))
        assertEquals("Waited 4 min at Habsiguda Metro for a bike taxi", StageSense.waitText(before[1]))
        // Applied, as the app records it: the way there, the ride from its station, the ride home.
        val s = splits.single()
        val applied = listOf(
            Leg(0, "BIKE_TAXI", "Qualizeal Office", office, "Durgam Cheruvu Metro", durgamCheruvu, min(0.0), s.atMs),
            Leg(1, "METRO", "Durgam Cheruvu Metro", durgamCheruvu, "Habsiguda Metro", habsiguda, s.atMs, min(47.0)),
            Leg(2, "BIKE_TAXI", "Habsiguda Metro", habsiguda, "Raheja Vistas, Nacharam", raheja, min(47.0), min(62.0))
        )
        val after = StageSense.waits(applied, fixes, StageSense.shifted(splits))
        assertEquals(after.map { StageSense.waitText(it) }.toString(), 3, after.size)
        assertEquals("Waited 4 min at Qualizeal Office for a bike taxi", StageSense.waitText(after[0]))
        assertEquals("Waited 4 min at Durgam Cheruvu Metro for the metro", StageSense.waitText(after[1]))
        assertEquals("Waited 4 min at Habsiguda Metro for a bike taxi", StageSense.waitText(after[2]))
        assertEquals(listOf(0, 1, 2), after.map { it.legIndex })
        // And the ride from its station reads as beginning there: nothing more to split.
        assertTrue(StageSense.splits(applied, fixes, net, "BIKE_TAXI").isEmpty())
    }

    @Test fun a_station_passed_on_the_road_is_not_where_the_metro_was_boarded() {
        // The bike taxi goes by Madhapur station and on along the road under the line to Durgam Cheruvu, where the wait is.
        val fixes = stand(office, 0.0, 4.0) + run(office, madhapur, 4.0, 9.0) + run(madhapur, durgamCheruvu, 9.0, 11.0) + stand(durgamCheruvu, 11.0, 15.0) +
            ride(durgamCheruvu to madhapur, madhapur to ameerpet, ameerpet to secunderabad, secunderabad to mettuguda, mettuguda to tarnaka, tarnaka to habsiguda, fromMin = 15.0, minutesEach = 5.5) +
            stand(habsiguda, 48.0, 51.0)
        val lg = listOf(Leg(0, "METRO", "Qualizeal Office", office, "Habsiguda Metro", habsiguda, min(0.0), min(51.0)))
        val s = StageSense.splits(lg, fixes, net, "BIKE_TAXI").single()
        assertEquals(Reason.BEFORE_LINE, s.reason)
        assertEquals("Durgam Cheruvu Metro", s.placeName)
        // From reaching Durgam Cheruvu, where the wait was: Madhapur, passed on the road, is not it.
        assertTrue("${(s.atMs - t0) / 60_000.0}", (s.atMs - t0) in min(10.2) - t0..min(11.1) - t0)
    }

    @Test fun a_bike_taxi_that_passed_a_station_and_turned_away_did_not_get_off_the_metro() {
        // Chosen "metro" at the office, but the fixes go by Madhapur station and back: no boarding, so nothing is read as leaving the line.
        val fixes = stand(office, 0.0, 4.0) + run(office, madhapur, 4.0, 9.0) + run(madhapur, office, 9.0, 14.0) + stand(office, 14.0, 20.0)
        val lg = listOf(Leg(0, "METRO", "Qualizeal Office", office, "Qualizeal Office", office, min(0.0), min(20.0)))
        assertTrue(StageSense.splits(lg, fixes, net, "BIKE_TAXI").isEmpty())
    }

    @Test fun a_ride_from_the_office_is_read_once_the_line_is_followed() {
        val open = Leg(0, "METRO", "Qualizeal Office", office, "Raheja Vistas, Nacharam", raheja, min(0.0), null)
        // Standing on the platform: nothing yet.
        val onPlatform = stand(office, 0.0, 4.0) + run(office, durgamCheruvu, 4.0, 10.0) + stand(durgamCheruvu, 10.0, 14.0)
        assertTrue(StageSense.splits(listOf(open), onPlatform, net, "BIKE_TAXI").isEmpty())
        // Past the next station: the ride began at Durgam Cheruvu, by bike taxi to there.
        val pastMadhapur = onPlatform + run(durgamCheruvu, madhapur, 14.0, 16.0) + run(madhapur, ameerpet, 16.0, 19.0)
        val s = StageSense.splits(listOf(open), pastMadhapur, net, "BIKE_TAXI").single()
        assertEquals(Reason.BEFORE_LINE, s.reason)
        assertEquals("Durgam Cheruvu Metro", s.placeName)
        assertEquals("BIKE_TAXI", s.mode)
    }

    @Test fun a_short_walk_into_the_station_is_not_a_stage() {
        // "Metro" tapped at the station gate, 300 m from the platform: the walk in is the station, the ride is read from the start.
        val gate = GeoPoint(durgamCheruvu.lat - 0.0027, durgamCheruvu.lng)
        val fixes = run(gate, durgamCheruvu, 0.0, 4.0, stepS = 8) + stand(durgamCheruvu, 4.0, 7.0) +
            ride(durgamCheruvu to madhapur, madhapur to ameerpet, fromMin = 7.0, minutesEach = 5.5) + run(ameerpet, office, 18.0, 30.0) + stand(office, 30.0, 34.0)
        val lg = listOf(Leg(0, "METRO", "Durgam Cheruvu gate", gate, "Qualizeal Office", office, min(0.0), min(34.0)))
        val s = StageSense.splits(lg, fixes, net, "BIKE_TAXI").single()
        // No first mile; the ride left the line at Ameerpet and went on by road.
        assertEquals(Reason.LEFT_LINE, s.reason)
        assertEquals("Ameerpet Metro", s.placeName)
    }

    @Test fun splits_are_renumbered_to_the_stages_as_they_will_stand() {
        val w = StageSense.Wait(1, min(0.0), min(5.0), durgamCheruvu, "Durgam Cheruvu Metro", "BIKE_TAXI", false)
        val splits = listOf(
            StageSense.Split(0, min(14.0), durgamCheruvu, "Durgam Cheruvu Metro", "BIKE_TAXI", Reason.BEFORE_LINE, Pace.RIDING, null),
            StageSense.Split(0, min(47.0), habsiguda, "Habsiguda Metro", "BIKE_TAXI", Reason.LEFT_LINE, Pace.RIDING, w),
            StageSense.Split(2, min(60.0), raheja, "Raheja Vistas", "WALK", Reason.WALKED_INSTEAD, Pace.WALKING, w.copy(legIndex = 2))
        )
        // The ride's end is put in before its beginning, both before anything earlier.
        assertEquals(listOf(Reason.WALKED_INSTEAD, Reason.LEFT_LINE, Reason.BEFORE_LINE), StageSense.applyOrder(splits).map { it.reason })
        // Stages once applied: 0 the way there, 1 the metro, 2 the last mile, 3 whatever was next, 4 the walked "cab".
        val sh = StageSense.shifted(splits)
        assertEquals(listOf(1, 1, 4), sh.map { it.legIndex })
        assertEquals(listOf(null, 2, 4), sh.map { it.wait?.legIndex })
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

    // ---- the way to the station, live: reached, waited at, boarded ----

    /** Home to Habsiguda by bike taxi in 8 minutes, then the platform. */
    private fun toHabsiguda(standUntil: Double): List<Fix> = run(raheja, habsiguda, 0.0, 8.0) + stand(habsiguda, 8.0, standUntil)

    @Test fun waiting_at_the_station_is_reaching_it_not_a_metro_ride() {
        val now = min(12.0)
        val a = StageSense.reachedStation(raheja, office, toHabsiguda(12.0), TransitNetwork.Kind.METRO, net, now)
        assertNotNull(a)
        assertEquals("Habsiguda Metro", a!!.placeName)
        assertEquals(Pace.RIDING, a.pace)
        // From when the station was reached: the bike taxi ended at its door.
        assertTrue("${(a.atMs - t0) / 60_000.0}", (a.atMs - t0) in min(7.5) - t0..min(8.1) - t0)
        assertEquals("BIKE_TAXI", StageSense.firstMileMode(a.pace, "BIKE_TAXI"))
    }

    @Test fun two_minutes_by_a_station_is_a_light_not_a_wait() {
        assertNull(StageSense.reachedStation(raheja, office, toHabsiguda(10.0), TransitNetwork.Kind.METRO, net, min(10.0)))
        // Riding through, past the station: not there at all.
        val through = run(raheja, habsiguda, 0.0, 8.0) + run(habsiguda, tarnaka, 8.0, 12.0)
        assertNull(StageSense.reachedStation(raheja, office, through, TransitNetwork.Kind.METRO, net, min(12.0)))
        // The station the journey set off from, or is going to, is not one reached on the way.
        assertNull(StageSense.reachedStation(habsiguda, office, stand(habsiguda, 0.0, 6.0), TransitNetwork.Kind.METRO, net, min(6.0)))
    }

    @Test fun a_train_caught_at_once_is_boarded_where_the_station_was_reached() {
        // A minute on the platform, then the train to Tarnaka: no three-minute wait to read, the line followed instead.
        val fixes = run(raheja, habsiguda, 0.0, 8.0) + stand(habsiguda, 8.0, 9.0) + ride(habsiguda to tarnaka, fromMin = 9.0, minutesEach = 3.0)
        val a = StageSense.boardedAt(raheja, fixes, TransitNetwork.Kind.METRO, net)
        assertNotNull(a)
        assertEquals("Habsiguda Metro", a!!.placeName)
        assertTrue("${(a.atMs - t0) / 60_000.0}", (a.atMs - t0) in min(7.5) - t0..min(8.1) - t0)
    }

    // ---- a change of line, on any network ----

    private val miyapur = GeoPoint(17.49654, 78.37303)
    private val kukatpally = GeoPoint(17.48460, 78.41180)

    /** The Blue Line from Habsiguda west, and the Red Line from Ameerpet north to Miyapur: the two meet at Ameerpet. */
    private val lines: TransitNetwork = TransitNetwork.parse(
        listOf(
            "Habsiguda" to habsiguda, "Tarnaka" to tarnaka, "Mettuguda" to mettuguda, "Secunderabad East" to secunderabad,
            "Ameerpet" to ameerpet, "Madhapur" to madhapur, "Kukatpally" to kukatpally, "Miyapur" to miyapur
        ).joinToString("\n") { (n, p) -> "S|%.5f|%.5f|%s|Hyderabad Metro|M".format(p.lat, p.lng, n) } +
            "\nL|Hyderabad Metro|Blue Line|blue|0 1:1500 2:1700 3:1600 4:5700 5:6000" +
            "\nL|Hyderabad Metro|Red Line|red|4 6:5000 7:4500\n"
    )

    @Test fun a_change_from_the_blue_line_to_the_red_is_read_from_the_stations_passed() {
        // Blue Line to Ameerpet, five minutes changing platforms, the Red Line to Miyapur.
        val fixes = ride(habsiguda to tarnaka, tarnaka to mettuguda, mettuguda to secunderabad, secunderabad to ameerpet, fromMin = 0.0, minutesEach = 4.0) +
            stand(ameerpet, 16.0, 21.0) + ride(ameerpet to kukatpally, kukatpally to miyapur, fromMin = 21.0, minutesEach = 6.0)
        val leg = Leg(0, "METRO", "Habsiguda Metro", habsiguda, "Miyapur Metro", miyapur, min(0.0), min(33.0))
        val c = StageSense.lineChanges(leg, fixes, lines).single()
        assertEquals("Blue Line", c.fromLine)
        assertEquals("Red Line", c.toLine)
        assertEquals("Ameerpet Metro", c.placeName)
        // From reaching Ameerpet: the walk between platforms and the wait are the change.
        assertTrue("${(c.atMs - t0) / 60_000.0}", (c.atMs - t0) in min(15.0) - t0..min(16.1) - t0)
        // Told as a change between two rides of one metro, not a ride of its own.
        val stages = listOf(
            com.trippulse.app.domain.Changes.Stage("METRO", "Habsiguda Metro", "Ameerpet Metro", min(0.0), c.atMs, line = "Blue Line"),
            com.trippulse.app.domain.Changes.Stage("METRO", "Ameerpet Metro", "Miyapur Metro", c.atMs, min(33.0), line = "Red Line")
        )
        val step = com.trippulse.app.domain.Changes.parts(stages, listOf(com.trippulse.app.domain.Changes.Wait(min(16.0), min(21.0))), completed = false)
            .filterIsInstance<com.trippulse.app.domain.Changes.Part.Step>().single { it.change.kind == com.trippulse.app.domain.Changes.Kind.CHANGE }
        assertTrue(step.change.ofLine)
        assertEquals("Off the Blue Line at Ameerpet Metro, onto the Red Line after a wait", com.trippulse.app.domain.Changes.text(step.change))
    }

    @Test fun a_ride_that_stays_on_its_line_or_has_not_gone_on_has_no_change() {
        // Habsiguda to Madhapur through Ameerpet: the Blue Line all the way.
        val stayed = ride(habsiguda to tarnaka, tarnaka to mettuguda, mettuguda to secunderabad, secunderabad to ameerpet, ameerpet to madhapur, fromMin = 0.0, minutesEach = 4.0)
        assertTrue(StageSense.lineChanges(Leg(0, "METRO", "Habsiguda Metro", habsiguda, "Madhapur Metro", madhapur, min(0.0), min(20.0)), stayed, lines).isEmpty())
        // Still at Ameerpet, the change not yet made: nothing to tell yet.
        val atInterchange = ride(habsiguda to tarnaka, tarnaka to mettuguda, mettuguda to secunderabad, secunderabad to ameerpet, fromMin = 0.0, minutesEach = 4.0) + stand(ameerpet, 16.0, 19.0)
        assertTrue(StageSense.lineChanges(Leg(0, "METRO", "Habsiguda Metro", habsiguda, "Miyapur Metro", miyapur, min(0.0), null), atInterchange, lines).isEmpty())
    }
}
