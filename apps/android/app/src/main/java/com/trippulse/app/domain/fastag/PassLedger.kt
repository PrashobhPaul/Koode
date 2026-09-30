package com.trippulse.app.domain.fastag

/**
 * The rules for an annual-pass balance, kept pure so they are testable without
 * a device. The balance is the user's own live number: they set the baseline,
 * qualifying crossings decrement it, and the user can correct it at any time —
 * a correction simply becomes the new baseline. Koode never reconstructs an
 * unknown historical balance and never decrements below zero.
 */
object PassLedger {

    /** Only an explicit annual-pass crossing consumes a pass unit. */
    fun qualifies(passType: TollPassType): Boolean = passType == TollPassType.ANNUAL_PASS

    /**
     * The balance after a recorded crossing: one less for a qualifying
     * annual-pass crossing (floored at zero), unchanged otherwise.
     */
    fun next(balance: Int, passType: TollPassType): Int =
        if (qualifies(passType)) (balance - 1).coerceAtLeast(0) else balance

    /**
     * The money balance after a toll debit: the debited amount subtracted
     * (floored at zero), unchanged when the SMS named no amount. Used for an
     * ordinary prepaid FASTag, where the balance is rupees rather than
     * crossings. As with [next], the user's own number is always authoritative
     * and a correction simply becomes the new baseline.
     */
    fun amountAfter(balance: Double, debited: Double?): Double =
        if (debited != null && debited > 0.0) (balance - debited).coerceAtLeast(0.0) else balance
}
