package com.trippulse.app.core

import java.util.Locale

/**
 * Field-level input rules, kept in one place so every screen enforces the same
 * thing and no screen invents its own variant.
 *
 * The money tracker in particular has a hard contract from the product: an
 * expense *item* is text, an expense *amount* is a number. Enforcing that as
 * the user types — rather than validating after the fact — is what stops the
 * two ever being confused in an exported statement.
 */
object InputRules {

    /** Longest an expense item label may be. */
    const val ITEM_MAX = 48

    /** Digits only — used by the journey code and passcode fields. */
    fun digits(raw: String, max: Int = Int.MAX_VALUE): String =
        raw.filter { it.isDigit() }.take(max)

    /**
     * Expense item: letters, spaces and the handful of separators a real
     * receipt line uses. Digits and currency symbols are dropped so an amount
     * can never leak into the item column.
     */
    fun itemText(raw: String, max: Int = ITEM_MAX): String {
        val cleaned = raw.filter { ch ->
            ch.isLetter() || ch == ' ' || ch == '-' || ch == '/' || ch == '&' ||
                ch == '(' || ch == ')' || ch == '.' || ch == ','
        }
        return cleaned.replace(Regex(" {2,}"), " ").trimStart().take(max)
    }

    /**
     * Money amount: digits with at most one decimal point and two decimals.
     * Never returns a value the parser below would reject.
     */
    fun amountText(raw: String): String {
        val filtered = raw.filter { it.isDigit() || it == '.' }
        val firstDot = filtered.indexOf('.')
        val normalized = if (firstDot < 0) filtered else
            filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).filter { it.isDigit() }
        val parts = normalized.split('.')
        val whole = parts[0].take(9)
        val fraction = parts.getOrNull(1)?.take(2)
        return if (fraction == null) whole else "$whole.$fraction"
    }

    /** Quantity (litres / kWh): same shape as an amount but up to three decimals. */
    fun quantityText(raw: String): String {
        val amount = amountText(raw)
        val parts = amount.split('.')
        val fraction = parts.getOrNull(1)
        return if (fraction == null) parts[0] else "${parts[0]}.${fraction.take(3)}"
    }

    /** Parses money that already passed [amountText]; null when not usable. */
    fun parseAmount(text: String): Double? =
        text.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it > 0.0 }

    /** True when both halves of an expense line are valid. */
    fun isValidExpense(item: String, amount: String): Boolean =
        item.trim().isNotBlank() && parseAmount(amount) != null

    /** Phone numbers: digits plus a single leading '+'. */
    fun phoneText(raw: String, max: Int = 16): String {
        val plus = raw.trimStart().startsWith("+")
        val body = raw.filter { it.isDigit() }.take(max)
        return if (plus) "+$body" else body
    }

    /** Title-cases a free-text label for display without touching storage. */
    fun titleCase(raw: String): String = raw.trim().split(" ").joinToString(" ") { word ->
        if (word.isEmpty()) word
        else word.substring(0, 1).uppercase(Locale.ROOT) + word.substring(1)
    }
}
