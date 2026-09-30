package com.trippulse.app

import com.trippulse.app.domain.Expenses
import com.trippulse.app.domain.Expenses.Category
import com.trippulse.app.domain.Expenses.ItemStatus
import com.trippulse.app.domain.Expenses.Opportunity
import com.trippulse.app.domain.Expenses.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expenses are captured when they happen, never from a driver on the move,
 * always skippable — and unknown is never zero.
 */
class ExpensesTest {

    private val t0 = 1_759_000_000_000L
    private fun opp(cat: Category, status: Status = Status.PENDING, label: String = cat.label) =
        Opportunity("id-${cat.name}-${status.name}", cat, t0, label, status)

    @Test fun a_driver_on_the_move_is_never_asked() {
        assertFalse(Expenses.safeToAsk(driver = true, moving = true))
        assertTrue(Expenses.safeToAsk(driver = true, moving = false))
        assertTrue(Expenses.safeToAsk(driver = false, moving = true))
    }

    @Test fun skipped_is_not_no_expense_and_unknown_is_not_zero() {
        val list = Expenses.checklist(
            recorded = listOf(Category.FUEL to 2800.0),
            opportunities = listOf(opp(Category.FOOD, Status.DEFERRED), opp(Category.CAB, Status.UNKNOWN)),
            passCoveredCrossings = 0
        )
        assertEquals(1, list.needsAttention)
        assertFalse(list.canApprove)
        assertEquals(1, list.unknownCount)
        assertEquals(2800.0, list.recordedTotal, 0.0)
        assertEquals("Recorded trip expenses", list.headline)
        assertEquals(ItemStatus.NEEDS_AMOUNT, list.items.first { it.category == Category.FOOD }.status)
        assertEquals(ItemStatus.UNKNOWN, list.items.first { it.category == Category.CAB }.status)
    }

    @Test fun no_expense_is_a_real_answer() {
        val list = Expenses.checklist(emptyList(), listOf(opp(Category.ACCOMMODATION, Status.NO_EXPENSE)), 0)
        assertTrue(list.canApprove)
        assertTrue(list.complete)
        assertEquals(ItemStatus.NO_EXPENSE, list.items.single().status)
        assertEquals("✓ All detected expenses accounted for", list.statusLine)
    }

    @Test fun annual_pass_tolls_are_crossings_never_money() {
        val list = Expenses.checklist(listOf(Category.FUEL to 3200.0), emptyList(), passCoveredCrossings = 7)
        val toll = list.items.first { it.category == Category.TOLL }
        assertEquals(ItemStatus.COVERED_BY_ANNUAL_PASS, toll.status)
        assertEquals(0.0, toll.amount, 0.0)
        assertEquals("7 annual-pass crossings", toll.reason)
        assertEquals(3200.0, list.recordedTotal, 0.0)
        assertEquals(7, list.passCrossings)
    }

    @Test fun paid_tolls_are_money() {
        val list = Expenses.checklist(listOf(Category.TOLL to 95.0, Category.TOLL to 85.0), emptyList(), 0)
        val toll = list.items.single()
        assertEquals(ItemStatus.RECORDED, toll.status)
        assertEquals(180.0, toll.amount, 0.0)
    }

    @Test fun fares_follow_the_mode_and_own_vehicles_have_none() {
        assertEquals(Category.CAB, Category.fareFor("CAB"))
        assertEquals(Category.METRO, Category.fareFor("METRO"))
        assertEquals(Category.TRAIN, Category.fareFor("TRAIN"))
        assertNull(Category.fareFor("CAR"))
        assertNull(Category.fareFor("BIKE"))
        assertEquals(Category.ACCOMMODATION, Category.fromType("STAY"))
        assertEquals(Category.FUEL, Category.fromType("FUEL"))
        assertEquals(Category.OTHER, Category.fromType("mystery"))
    }

    @Test fun duplicates_are_not_asked_twice() {
        val existing = listOf(opp(Category.CAB, Status.PENDING, "Cab · Airport → Station"))
        assertTrue(Expenses.isDuplicate(existing, Category.CAB, "Cab · Airport → Station", t0 + 5 * 60_000))
        assertFalse(Expenses.isDuplicate(existing, Category.CAB, "Cab · Station → Home", t0 + 5 * 60_000))
        val answered = listOf(opp(Category.FOOD, Status.RECORDED, "Food · Salem"))
        assertTrue(Expenses.isDuplicate(answered, Category.FOOD, "Food · Salem", t0 + 10 * 60_000))
        assertFalse(Expenses.isDuplicate(answered, Category.FOOD, "Food · Salem", t0 + 5 * 60 * 60_000))
    }

    @Test fun opportunities_survive_a_restart_with_their_original_time() {
        val list = listOf(
            Opportunity("a", Category.FOOD, t0, "Food · Hotel Aryaas", Status.DEFERRED, null, true),
            Opportunity("b", Category.CAB, t0 + 1, "Cab · Airport → Station", Status.RECORDED, 650.0, true)
        )
        assertEquals(list, Expenses.decode(Expenses.encode(list)))
        assertTrue(Expenses.decode("junk").isEmpty())
    }

    @Test fun questions_never_assume_payment() {
        assertEquals("Did you pay for the room?", Expenses.question(opp(Category.ACCOMMODATION)))
        assertEquals("How much did you spend on food?", Expenses.question(opp(Category.FOOD)))
    }
}
