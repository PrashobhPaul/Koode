package com.trippulse.app

import com.trippulse.app.domain.fastag.PassLedger
import com.trippulse.app.domain.fastag.TollPassType
import com.trippulse.app.domain.fastag.TollSmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FASTag SMS parser turns a toll message into a factual crossing and
 * ignores everything else. These pin the extraction and, crucially, that
 * non-toll bank messages never become journey events.
 */
class TollSmsTest {

    private val t0 = 1_759_000_000_000L

    @Test fun an_icici_annual_pass_crossing_is_parsed() {
        val c = TollSmsParser.parse(
            "Your Vehicle TS07FZ1868 crossed Paliyekkara Toll Plaza on 27-09-2026 05:02 under Annual Pass. -ICICI",
            t0
        )
        assertNotNull(c); c!!
        assertEquals("Paliyekkara Toll Plaza", c.plaza)
        assertEquals("TS07FZ1868", c.vehicle)
        assertEquals(TollPassType.ANNUAL_PASS, c.passType)
        assertEquals("ICICI", c.issuer)
    }

    @Test fun a_per_trip_debit_crossing_from_another_bank_is_parsed() {
        val c = TollSmsParser.parse(
            "HDFC Bank: Rs.85.00 debited for FASTag toll at Edappally Toll Plaza on 27/09/2026 14:30. Vehicle KL07AB1234.",
            t0
        )
        assertNotNull(c); c!!
        assertEquals("Edappally Toll Plaza", c.plaza)
        assertEquals("KL07AB1234", c.vehicle)
        assertEquals(TollPassType.PER_TRIP, c.passType)
        assertEquals("HDFC", c.issuer)
    }

    @Test fun a_hyphenated_plate_is_normalised() {
        val c = TollSmsParser.parse(
            "FASTag: Vehicle KL-08-AC-1234 crossed Kumbalam Toll Plaza on 01-01-2026 09:15",
            t0
        )
        assertEquals("KL08AC1234", c?.vehicle)
    }

    @Test fun a_missing_timestamp_falls_back_to_the_receive_time() {
        val c = TollSmsParser.parse(
            "Your FASTag: Vehicle TS07FZ1868 crossed Paliyekkara Toll Plaza. Thank you.",
            t0
        )
        assertNotNull(c)
        assertEquals(t0, c!!.crossedAtMs)
    }

    @Test fun a_crossing_with_no_plaza_but_a_vehicle_still_parses() {
        val c = TollSmsParser.parse(
            "FASTag toll transaction on your Vehicle TS07FZ1868 was successful on 27-09-2026 05:02",
            t0
        )
        assertNotNull(c)
        assertNull(c!!.plaza)
        assertEquals("TS07FZ1868", c.vehicle)
    }

    @Test fun a_recharge_alert_is_not_a_crossing() {
        assertNull(TollSmsParser.parse("Your FASTag has been recharged with Rs 500. Balance Rs 512.", t0))
    }

    @Test fun a_low_balance_warning_is_not_a_crossing() {
        assertNull(TollSmsParser.parse("Low balance on your FASTag. Please recharge to avoid inconvenience.", t0))
    }

    @Test fun an_unrelated_bank_sms_is_ignored() {
        assertNull(TollSmsParser.parse("Rs.2000 credited to your account XX1234 on 27-09-2026. Avl bal Rs.5000.", t0))
        assertNull(TollSmsParser.parse("123456 is your OTP for login. Do not share it.", t0))
    }

    @Test fun the_same_crossing_yields_a_stable_dedup_key() {
        val a = TollSmsParser.parse("Vehicle TS07FZ1868 crossed Paliyekkara Toll Plaza on 27-09-2026 05:02", t0)!!
        val b = TollSmsParser.parse("Your Vehicle TS07FZ1868 crossed PALIYEKKARA TOLL PLAZA on 27-09-2026 05:02 Annual Pass", t0)!!
        assertEquals(a.dedupKey(), b.dedupKey())
    }

    // ---- pass ledger ----

    @Test fun only_annual_pass_crossings_qualify() {
        assertTrue(PassLedger.qualifies(TollPassType.ANNUAL_PASS))
        assertTrue(!PassLedger.qualifies(TollPassType.PER_TRIP))
        assertTrue(!PassLedger.qualifies(TollPassType.UNKNOWN))
    }

    @Test fun a_qualifying_crossing_decrements_by_one() {
        assertEquals(199, PassLedger.next(200, TollPassType.ANNUAL_PASS))
        assertEquals(144, PassLedger.next(145, TollPassType.ANNUAL_PASS))
    }

    @Test fun a_non_qualifying_crossing_leaves_the_balance_unchanged() {
        assertEquals(200, PassLedger.next(200, TollPassType.PER_TRIP))
        assertEquals(200, PassLedger.next(200, TollPassType.UNKNOWN))
    }

    @Test fun the_balance_never_goes_below_zero() {
        assertEquals(0, PassLedger.next(0, TollPassType.ANNUAL_PASS))
    }

    // ---- debited-amount extraction (for a money-balance FASTag) ----

    @Test fun a_debited_amount_is_extracted_for_a_per_trip_crossing() {
        val c = TollSmsParser.parse(
            "HDFC Bank: Rs.85.00 debited for FASTag toll at Edappally Toll Plaza on 27/09/2026 14:30. Vehicle KL07AB1234.",
            t0
        )
        assertNotNull(c)
        assertEquals(85.0, c!!.amount!!, 0.001)
    }

    @Test fun an_amount_after_the_debit_verb_is_also_extracted() {
        val amt = TollSmsParser.extractAmount(
            "FASTag toll crossed at Nettoor Toll Plaza. Deducted Rs 45.50 on 01-01-2026 09:15. Vehicle KL01AA0001."
        )
        assertEquals(45.5, amt!!, 0.001)
    }

    @Test fun an_annual_pass_crossing_carries_no_amount() {
        val c = TollSmsParser.parse(
            "Your Vehicle TS07FZ1868 crossed Paliyekkara Toll Plaza on 27-09-2026 05:02 under Annual Pass. -ICICI",
            t0
        )
        assertNull(c!!.amount)
    }

    @Test fun a_balance_or_recharge_figure_is_not_read_as_a_toll_amount() {
        // A crossing SMS that also mentions the remaining balance must not treat
        // that balance as the debited toll.
        val amt = TollSmsParser.extractAmount(
            "Toll crossed at Kumbalam Toll Plaza. Rs.60 deducted. Avl bal Rs.940."
        )
        assertEquals(60.0, amt!!, 0.001)
    }

    // ---- money-balance ledger ----

    @Test fun a_toll_debit_subtracts_from_the_money_balance() {
        assertEquals(915.0, PassLedger.amountAfter(1000.0, 85.0), 0.001)
    }

    @Test fun a_money_balance_with_no_debit_is_unchanged() {
        assertEquals(1000.0, PassLedger.amountAfter(1000.0, null), 0.001)
        assertEquals(1000.0, PassLedger.amountAfter(1000.0, 0.0), 0.001)
    }

    @Test fun a_money_balance_never_goes_below_zero() {
        assertEquals(0.0, PassLedger.amountAfter(30.0, 85.0), 0.001)
    }
}
