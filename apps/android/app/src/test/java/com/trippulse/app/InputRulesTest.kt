package com.trippulse.app

import com.trippulse.app.core.InputRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The money tracker's contract: the item is text, the amount is a number.
 * Enforcing it as the user types is what keeps an exported statement honest.
 */
class InputRulesTest {

    @Test fun an_item_keeps_words_and_drops_digits() {
        // While typing, the trailing space survives so the next word can be
        // typed; storage trims it.
        assertEquals("Highway dhaba lunch ", InputRules.itemText("Highway dhaba lunch 450"))
        assertEquals("Highway dhaba lunch", InputRules.itemTextForStorage("Highway dhaba lunch 450"))
        assertEquals("Diesel top-up", InputRules.itemTextForStorage("Diesel top-up ₹2300"))
    }

    @Test fun an_item_being_typed_keeps_the_space_after_a_word() {
        assertEquals("Highway ", InputRules.itemText("Highway "))
    }

    @Test fun an_item_collapses_runs_of_spaces() {
        assertEquals("Toll gate", InputRules.itemText("Toll     gate"))
    }

    @Test fun an_amount_keeps_digits_and_one_decimal_point() {
        assertEquals("450", InputRules.amountText("₹450"))
        assertEquals("450.75", InputRules.amountText("450.75"))
        // Extra points are dropped, keeping the first: 4.5075 -> two decimals.
        assertEquals("4.50", InputRules.amountText("4.5.0.75"))
    }

    @Test fun an_amount_never_keeps_more_than_two_decimals() {
        assertEquals("12.34", InputRules.amountText("12.3456"))
    }

    @Test fun a_quantity_may_carry_three_decimals_for_litres() {
        assertEquals("32.456", InputRules.quantityText("32.4567"))
    }

    @Test fun parsing_rejects_blank_zero_and_nonsense() {
        assertNull(InputRules.parseAmount(""))
        assertNull(InputRules.parseAmount("0"))
        assertNull(InputRules.parseAmount("abc"))
        assertEquals(450.0, InputRules.parseAmount("450")!!, 0.0001)
    }

    @Test fun an_expense_needs_both_halves() {
        assertTrue(InputRules.isValidExpense("Lunch", "450"))
        assertFalse(InputRules.isValidExpense("", "450"))
        assertFalse(InputRules.isValidExpense("Lunch", ""))
        assertFalse(InputRules.isValidExpense("Lunch", "0"))
    }

    @Test fun digits_helper_truncates_to_the_field_length() {
        assertEquals("403819", InputRules.digits("TP-40381927", 6))
        assertEquals("40381927", InputRules.digits("TP-4038 1927"))
    }

    @Test fun a_phone_keeps_a_single_leading_plus() {
        assertEquals("+919812345678", InputRules.phoneText("+91 98123 45678"))
        assertEquals("9812345678", InputRules.phoneText("98123-45678"))
    }
}
