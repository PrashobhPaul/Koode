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
        // Only the leading edge is trimmed: someone typing "Highway dhaba" has
        // to be able to type the space after "Highway".
        return cleaned.replace(Regex(" {2,}"), " ").trimStart().take(max)
    }

    /**
     * The same rules, plus a trailing trim — used at the moment a value is
     * stored rather than while it is being typed, so nothing is persisted with
     * a stray space left over from a stripped digit.
     */
    fun itemTextForStorage(raw: String, max: Int = ITEM_MAX): String =
        itemText(raw, max).trim()

    /**
     * Money amount: digits with at most one decimal point and two decimals.
     * Never returns a value the parser below would reject.
     */
    fun amountText(raw: String): String = decimalText(raw, maxDecimals = 2)

    /**
     * Quantity (litres / kWh): same shape as an amount but finer, because
     * "32.456 L" off a fuel receipt is a real number people type.
     */
    fun quantityText(raw: String): String = decimalText(raw, maxDecimals = 3)

    /**
     * Digits with at most one decimal point and [maxDecimals] places after it.
     *
     * Extra points are dropped rather than rejected — someone fumbling the
     * keypad gets a sane number instead of a field that refuses to change.
     */
    private fun decimalText(raw: String, maxDecimals: Int, maxWhole: Int = 9): String {
        val filtered = raw.filter { it.isDigit() || it == '.' }
        val firstDot = filtered.indexOf('.')
        val normalized = if (firstDot < 0) filtered else
            filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).filter { it.isDigit() }
        val parts = normalized.split('.')
        val whole = parts[0].take(maxWhole)
        val fraction = parts.getOrNull(1)?.take(maxDecimals)
        return if (fraction == null) whole else "$whole.$fraction"
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
