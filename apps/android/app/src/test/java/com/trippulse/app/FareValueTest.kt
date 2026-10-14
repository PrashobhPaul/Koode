package com.trippulse.app

import com.trippulse.app.domain.FareValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fares of 14 October: ₹77 for two short bike taxis, ₹65 for 20 km of metro. */
class FareValueTest {

    private val rides = listOf(
        Triple("BIKE_TAXI", 2_100.0 as Double?, 10 * 60_000L),
        Triple("METRO", 20_500.0 as Double?, 37 * 60_000L),
        Triple("BIKE_TAXI", 1_900.0 as Double?, 9 * 60_000L)
    )
    private val fares = mapOf("BIKE_TAXI" to 77.0, "METRO" to 65.0)
    private val money = { x: Double -> "₹%.2f".format(x) }
    private val perKm = { c: Double, m: Double -> "₹%.2f / km".format(c / (m / 1000)) }

    @Test fun each_paid_way_gets_its_distance_time_and_share() {
        val v = FareValue.values(rides, fares)
        assertEquals(listOf("BIKE_TAXI", "METRO"), v.map { it.mode })
        val bike = v[0]
        assertEquals(4_000.0, bike.metres!!, 0.1)
        assertEquals(19 * 60_000L, bike.rideMs)
        assertEquals(4_000.0 / 24_500.0, bike.distanceShare!!, 0.001)
        assertEquals(77.0 / 142.0, bike.costShare, 0.001)
        assertEquals(19.25, bike.perMetre!! * 1000, 0.01)
        assertEquals(65.0 / 20.5, v[1].perMetre!! * 1000, 0.01)
    }

    @Test fun the_value_is_told_not_just_the_spend() {
        val v = FareValue.values(rides, fares)
        assertEquals("The bike taxi was 54% of the fare for 16% of the distance.", FareValue.insight(v))
        val lines = FareValue.lines(v, 24_500.0, 62 * 60_000L, 142.0, money, perKm)
        assertEquals("The bike taxi took 54% of the fare for 16% of the distance: ₹19.25 / km, against ₹3.17 / km by metro.", lines[0])
        assertTrue(lines[1], lines[1].startsWith("All the way by bike taxi at that rate: about ₹471.63"))
        assertTrue(lines[1], lines[1].contains("min at its pace, against 62 min door to door"))
        assertTrue(lines[1], lines[1].endsWith("This journey cost ₹142.00, ₹329.63 less."))
        assertEquals("A minute riding cost ₹4.05 by bike taxi, ₹1.76 by metro.", lines[2])
    }

    @Test fun no_comparison_without_two_priced_ways() {
        val one = FareValue.values(rides.take(1), mapOf("BIKE_TAXI" to 40.0))
        assertNull(FareValue.insight(one))
        val lines = FareValue.lines(one, 2_100.0, 12 * 60_000L, 40.0, money, perKm)
        assertEquals("By bike taxi: ₹19.05 / km.", lines[0])
        // A walk costs nothing and is not a fare.
        assertTrue(FareValue.values(listOf(Triple("WALK", 900.0 as Double?, 600_000L)), fares).isEmpty())
    }
}
