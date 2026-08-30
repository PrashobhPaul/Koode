package com.trippulse.app

import com.trippulse.app.domain.Measures
import com.trippulse.app.domain.MoneyFormat
import com.trippulse.app.domain.UnitPreference
import com.trippulse.app.domain.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Region intelligence needs no model — a country code and the JDK's own ISO
 * tables are enough. These tests pin that it actually works, in both
 * directions, and that an explicit setting always beats detection.
 */
class UnitsTest {

    @Test fun india_gets_rupees_and_kilometres() {
        val m = Measures.resolve("IN")
        assertEquals("INR", m.money.code)
        assertEquals("₹", m.money.symbol)
        assertEquals(UnitSystem.METRIC, m.units)
        assertEquals("km", m.distanceUnit)
        assertEquals("km/h", m.speedUnit)
    }

    @Test fun the_united_states_gets_dollars_and_miles() {
        val m = Measures.resolve("US")
        assertEquals("USD", m.money.code)
        assertEquals("$", m.money.symbol)
        assertEquals(UnitSystem.IMPERIAL, m.units)
        assertEquals("mi", m.distanceUnit)
        assertEquals("mph", m.speedUnit)
    }

    @Test fun the_eurozone_gets_euros_and_kilometres() {
        val m = Measures.resolve("DE")
        assertEquals("EUR", m.money.code)
        assertEquals("€", m.money.symbol)
        assertEquals(UnitSystem.METRIC, m.units)
    }

    /** Britain is the awkward one: metric everywhere except the road. */
    @Test fun britain_gets_pounds_and_miles() {
        val m = Measures.resolve("GB")
        assertEquals("GBP", m.money.code)
        assertEquals(UnitSystem.IMPERIAL, m.units)
    }

    @Test fun the_gulf_gets_its_own_currency() {
        assertEquals("AED", Measures.resolve("AE").money.code)
        assertEquals("SAR", Measures.resolve("SA").money.code)
    }

    @Test fun an_unknown_country_falls_back_to_rupees_rather_than_failing() {
        assertEquals(MoneyFormat.RUPEE.code, Measures.resolve(null).money.code)
        assertEquals(MoneyFormat.RUPEE.code, Measures.resolve("ZZ").money.code)
    }

    // ---- explicit settings win ----

    @Test fun a_chosen_unit_system_overrides_the_region() {
        val m = Measures.resolve("US", unitPreference = UnitPreference.METRIC)
        assertEquals(UnitSystem.METRIC, m.units)
        assertEquals("USD", m.money.code)   // currency untouched by the unit choice
    }

    @Test fun a_pinned_currency_overrides_the_region() {
        val m = Measures.resolve("US", currencyOverride = "INR")
        assertEquals("INR", m.money.code)
        assertEquals(UnitSystem.IMPERIAL, m.units)  // units untouched by the currency choice
    }

    @Test fun a_nonsense_currency_code_is_ignored_rather_than_crashing() {
        assertEquals("USD", Measures.resolve("US", currencyOverride = "XYZZY").money.code)
    }

    // ---- formatting ----

    @Test fun distance_is_printed_in_the_right_unit() {
        assertEquals("412 km", Measures.INDIA.distance(412_000.0))
        assertEquals("12.5 km", Measures.INDIA.distance(12_500.0))
        assertEquals("256 mi", Measures.resolve("US").distance(412_000.0))
    }

    @Test fun speed_converts_for_imperial_readers() {
        assertEquals("80 km/h", Measures.INDIA.speed(80.0))
        assertEquals("49 mph", Measures.resolve("US").speed(80.0))
    }

    @Test fun money_is_grouped_and_carries_the_local_symbol() {
        assertEquals("₹1,240.00", Measures.INDIA.money(1240.0))
        assertEquals("$18.50", Measures.resolve("US").money(18.5))
    }

    /** Yen and similar have no minor unit; printing "¥1,240.00" would be wrong. */
    @Test fun a_zero_decimal_currency_prints_no_decimals() {
        val m = Measures.resolve("JP")
        assertEquals(0, m.money.fractionDigits)
        assertEquals("¥1,240", m.money(1240.0))
    }

    @Test fun cost_per_distance_uses_the_readers_unit() {
        val india = Measures.INDIA.costPerDistance(2000.0, 400_000.0)
        assertEquals("₹5.00 / km", india)
        val us = Measures.resolve("US").costPerDistance(2000.0, 400_000.0)
        assertTrue("expected a per-mile rate, got $us", us!!.endsWith("/ mi"))
    }

    @Test fun efficiency_speaks_the_local_idiom() {
        assertEquals("16.0 km/L", Measures.INDIA.efficiency(400_000.0, 25.0))
        val mpg = Measures.resolve("US").efficiency(400_000.0, 25.0)
        assertTrue("expected mpg, got $mpg", mpg!!.endsWith("mpg"))
    }

    @Test fun rates_are_null_rather_than_infinite_when_there_is_nothing_to_divide() {
        assertEquals(null, Measures.INDIA.costPerDistance(0.0, 400_000.0))
        assertEquals(null, Measures.INDIA.costPerDistance(2000.0, 0.0))
        assertEquals(null, Measures.INDIA.efficiency(400_000.0, 0.0))
        assertEquals(null, Measures.INDIA.electricEfficiency(0.0, 20.0))
    }
}
