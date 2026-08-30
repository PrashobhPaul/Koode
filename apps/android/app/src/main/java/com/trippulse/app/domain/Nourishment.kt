package com.trippulse.app.domain

/**
 * What a traveller consumed at a stop or on board.
 *
 * The product promise is that nobody should have to think about paperwork while
 * travelling: one tap says "I ate", and the app works out whether that was
 * breakfast, lunch, dinner or a snack. [MealClassifier] owns that inference.
 */
enum class Nourishment(val key: String, val emoji: String, val label: String) {
    BREAKFAST("BREAKFAST", "🍛", "Breakfast"),
    LUNCH("LUNCH", "🍛", "Lunch"),
    DINNER("DINNER", "🍽", "Dinner"),
    SNACK("SNACK", "🍪", "Snack"),
    TEA_COFFEE("TEA_COFFEE", "☕", "Tea / coffee"),
    WATER("WATER", "💧", "Water");

    /** True for the three anchor meals — the ones the clock can infer. */
    val isMainMeal: Boolean get() = this == BREAKFAST || this == LUNCH || this == DINNER

    companion object {
        fun fromKey(key: String?): Nourishment? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Turns "the traveller logged food at 13:40" into "that was lunch".
 *
 * The rule the product asks for, exactly: the first food logged inside a meal
 * window IS that window's meal; anything after it in the same window is a
 * snack. An explicit snack / tea-coffee tap always wins over the inference —
 * the app guesses only when the traveller didn't say.
 *
 * Pure, framework-free and unit-tested (MealClassifierTest).
 */
object MealClassifier {

    /** Window boundaries, chosen for Indian travel days and stated once here. */
    const val BREAKFAST_FROM = 4
    const val BREAKFAST_UNTIL = 11      // 04:00–10:59
    const val LUNCH_UNTIL = 16          // 11:00–15:59
    const val EVENING_UNTIL = 19        // 16:00–18:59  (tea / snack window)
    const val DINNER_UNTIL = 4          // 19:00–03:59 (wraps past midnight)

    /** The meal this hour belongs to, ignoring what has already been logged. */
    fun windowFor(localHour: Int): Nourishment {
        val h = ((localHour % 24) + 24) % 24
        // Each window is stated as a closed range rather than a chain of
        // "less than" tests, because the dinner window WRAPS past midnight: an
        // open-ended `h < LUNCH_UNTIL` silently swallows 00:00–03:59 and calls
        // a 1 a.m. meal lunch.
        return when (h) {
            in BREAKFAST_FROM until BREAKFAST_UNTIL -> Nourishment.BREAKFAST
            in BREAKFAST_UNTIL until LUNCH_UNTIL -> Nourishment.LUNCH
            in LUNCH_UNTIL until EVENING_UNTIL -> Nourishment.SNACK
            else -> Nourishment.DINNER          // 19:00–03:59
        }
    }

    /**
     * Classifies a plain "I had food" tap.
     *
     * @param localHour           traveller-local hour, 0..23
     * @param alreadyLoggedToday  meals already recorded for this calendar day
     */
    fun classifyFood(localHour: Int, alreadyLoggedToday: Set<Nourishment>): Nourishment {
        val window = windowFor(localHour)
        // The evening window has no anchor meal — food there is a snack unless
        // the traveller explicitly said otherwise.
        if (!window.isMainMeal) return Nourishment.SNACK
        // First food in this window is the meal; a second helping is a snack.
        return if (window in alreadyLoggedToday) Nourishment.SNACK else window
    }

    /**
     * A friendly one-line description for the timeline, e.g.
     * "Lunch" or "Snack (evening)".
     */
    fun describe(kind: Nourishment, localHour: Int): String = when (kind) {
        Nourishment.SNACK ->
            if (windowFor(localHour) == Nourishment.SNACK) "Evening snack" else "Snack"
        else -> kind.label
    }
}
