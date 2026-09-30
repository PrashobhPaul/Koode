package com.trippulse.app.domain

/**
 * The journey analytics a traveller approves for the people following the
 * journey: how far, how long, how many stops and breaks. Never money — the
 * expense record stays on the traveller's phone.
 *
 * Built only from non-financial facts, and checked with the same rule the
 * server applies ([isFinancial]), so a future field can't slip money out by
 * accident: the server would refuse the whole document.
 */
object ApprovedAnalytics {

    fun build(
        summary: TripSummary,
        origin: String,
        destination: String,
        startedAtMs: Long,
        endedAtMs: Long,
        travelMode: String,
        tollsCrossed: Int
    ): Map<String, Any?> = mapOf(
        "origin" to origin,
        "destination" to destination,
        "startedAtMs" to startedAtMs,
        "endedAtMs" to endedAtMs,
        "travelMode" to travelMode,
        "distanceKm" to summary.distanceKm,
        "drivingSeconds" to summary.drivingSeconds,
        "totalSeconds" to summary.totalSeconds,
        "stops" to summary.stops,
        "foodBreaks" to summary.foodBreaks,
        "waterConfirmations" to summary.waterConfirmations,
        "toiletBreaks" to summary.toiletBreaks,
        "restBreaks" to summary.restBreaks,
        "fuelStops" to summary.fuelStops,
        "teaCoffee" to summary.teaCoffee,
        "snacks" to summary.snacks,
        "longestLegSeconds" to summary.longestLegSeconds,
        "longestBreakSeconds" to summary.longestBreakSeconds,
        "days" to summary.days,
        "tollsCrossed" to tollsCrossed
    )

    private val MONEY_KEY = Regex(
        "(amount|cost|price|fare|expens|spend|money|rupee|currency|balance|payment|paid|inr)",
        RegexOption.IGNORE_CASE
    )

    /**
     * The server's rule (tp_is_financial): any money-shaped key, at any depth,
     * or a rupee sign in any value.
     */
    fun isFinancial(doc: Map<String, Any?>): Boolean = doc.any { (k, v) -> isFinancialEntry(k, v) }

    private fun isFinancialEntry(key: String, value: Any?): Boolean =
        MONEY_KEY.containsMatchIn(key) || isFinancialValue(value)

    private fun isFinancialValue(value: Any?): Boolean = when (value) {
        is Map<*, *> -> value.any { (k, v) -> isFinancialEntry(k.toString(), v) }
        is Iterable<*> -> value.any { isFinancialValue(it) }
        is String -> value.contains('₹')
        else -> false
    }
}
