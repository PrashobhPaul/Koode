package com.trippulse.app

import com.trippulse.app.core.TimeFmt
import com.trippulse.app.domain.ClockPreference
import com.trippulse.app.domain.ClockStyle
import com.trippulse.app.domain.DateOrder
import com.trippulse.app.domain.LegalRegime
import com.trippulse.app.domain.Market
import com.trippulse.app.domain.Markets
import com.trippulse.app.domain.Measures
import com.trippulse.app.domain.TollSystem
import com.trippulse.app.domain.UnitPreference
import com.trippulse.app.domain.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** One row per country decides everything that differs by country; a new market is a new row. */
class MarketsTest {

    @Test fun india_is_the_only_country_that_gets_indian_things() {
        val india = Markets.forCountry("IN")
        assertEquals(TollSystem.FASTAG, india.tolls)
        assertTrue(india.canDetectTolls)
        assertEquals("PNR", india.bookingRefLabel)
        assertEquals(ClockStyle.TWELVE_HOUR, india.clock)
        assertEquals("112", india.emergencyNumber)
        // Nobody else inherits them: a country we never listed is not India.
        val chile = Markets.forCountry("CL")
        assertEquals(TollSystem.NONE, chile.tolls)
        assertFalse(chile.canDetectTolls)
        assertEquals("Booking reference", chile.bookingRefLabel)
        assertEquals("CLP", chile.currency.code)
        assertNull(Markets.GENERIC.tollPassName)
    }

    @Test fun the_united_states_and_europe_read_as_their_people_do() {
        val us = Markets.forCountry("us")
        assertEquals(UnitSystem.IMPERIAL, us.units)
        assertEquals(ClockStyle.TWELVE_HOUR, us.clock)
        assertEquals(DateOrder.MDY, us.dateOrder)
        assertEquals("911", us.emergencyNumber)
        assertEquals("Gas", us.fuelWord)
        assertEquals(LegalRegime.US_STATE, us.legal)
        val de = Markets.forCountry("DE")
        assertEquals("Germany", de.name)
        assertEquals(UnitSystem.METRIC, de.units)
        assertEquals(ClockStyle.TWENTY_FOUR_HOUR, de.clock)
        assertEquals("112", de.emergencyNumber)
        assertEquals(LegalRegime.GDPR, de.legal)
        assertEquals("EUR", de.currency.code)
        assertEquals("de", de.locale.language)
        assertEquals(TollSystem.VIGNETTE, Markets.forCountry("AT").tolls)
        assertEquals(LegalRegime.UK_GDPR, Markets.forCountry("GB").legal)
    }

    @Test fun japan_is_ready_as_a_row() {
        val jp = Markets.forCountry("JP")
        assertEquals(ClockStyle.TWENTY_FOUR_HOUR, jp.clock)
        assertEquals(DateOrder.YMD, jp.dateOrder)
        assertEquals("119", jp.emergencyNumber)
        assertEquals("110", jp.policeNumber)
        assertEquals(TollSystem.ETC, jp.tolls)
        assertEquals("ETC", jp.tollPassName)
        assertEquals("JPY", jp.currency.code)
        assertEquals("ja", jp.locale.language)
        assertEquals(LegalRegime.JAPAN_APPI, jp.legal)
    }

    @Test fun the_travellers_own_choices_sit_on_top_of_the_row() {
        val m = Market.resolve("US", UnitPreference.METRIC, ClockPreference.TWENTY_FOUR_HOUR)
        assertEquals(UnitSystem.METRIC, m.units)
        assertEquals(ClockStyle.TWENTY_FOUR_HOUR, m.clock)
        assertEquals("911", m.emergencyNumber)
        // The phone's own clock setting beats the row when nothing is chosen.
        assertEquals(ClockStyle.TWENTY_FOUR_HOUR, Market.resolve("IN", phoneUses24h = true).clock)
        assertEquals(ClockStyle.TWELVE_HOUR, Market.resolve("DE", phoneUses24h = false).clock)
        assertEquals(ClockStyle.TWENTY_FOUR_HOUR, Market.resolve("DE").clock)
        // Nothing known at all: a generic, non-Indian market.
        assertEquals(Markets.GENERIC, Market.resolve(null))
        assertEquals(Markets.GENERIC, Market.resolve("??"))
    }

    @Test fun money_is_written_the_way_the_phone_writes_numbers() {
        assertEquals("₹1,240.00", Measures.resolve("IN", locale = Locale.ENGLISH).money(1240.0))
        assertEquals("$1,240.50", Measures.resolve("US", locale = Locale.US).money(1240.5))
        assertEquals("1.240,50 €", Measures.resolve("DE", locale = Locale.GERMANY).money(1240.5))
        assertEquals("1 240,50 €", Measures.resolve("FR", locale = Locale.FRANCE).money(1240.5).replace(' ', ' '))
        assertEquals("¥1,240", Measures.resolve("JP", locale = Locale.JAPAN).money(1240.0))
        assertEquals("£18.50", Measures.resolve("GB", locale = Locale.UK).money(18.5))
    }

    @Test fun the_clock_and_the_date_follow_the_market() {
        val z = java.time.ZoneId.of("Asia/Kolkata")
        val t = java.time.ZonedDateTime.of(2026, 10, 6, 1, 5, 0, 0, z).toInstant().toEpochMilli()
        try {
            TimeFmt.configure(Locale.ENGLISH, twentyFourHour = false, dateOrder = DateOrder.DMY)
            assertEquals("01:05 AM", TimeFmt.clock(t, z))
            assertEquals("06 Oct 2026", TimeFmt.date(t, z))
            TimeFmt.configure(Locale.GERMANY, twentyFourHour = true, dateOrder = DateOrder.DMY)
            assertEquals("01:05", TimeFmt.clock(t, z))
            assertTrue(TimeFmt.date(t, z), TimeFmt.date(t, z).startsWith("06 Okt"))
            TimeFmt.configure(Locale.US, twentyFourHour = false, dateOrder = DateOrder.MDY)
            assertEquals("Oct 06, 2026", TimeFmt.date(t, z))
            TimeFmt.configure(Locale.JAPAN, twentyFourHour = true, dateOrder = DateOrder.YMD)
            assertEquals("2026/10/06", TimeFmt.date(t, z))
            assertEquals("HH:mm", TimeFmt.clockPattern)
        } finally {
            TimeFmt.configure(Locale.ENGLISH, twentyFourHour = false, dateOrder = DateOrder.DMY)
        }
    }
}
