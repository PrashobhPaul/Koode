package com.trippulse.app

import com.trippulse.app.domain.MealClassifier
import com.trippulse.app.domain.Nourishment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The promise being tested: a traveller taps "food" once and Koode works out
 * what meal that was, so nobody has to do paperwork while travelling.
 */
class MealClassifierTest {

    @Test fun morning_food_is_breakfast() {
        assertEquals(Nourishment.BREAKFAST, MealClassifier.classifyFood(8, emptySet()))
    }

    @Test fun midday_food_is_lunch() {
        assertEquals(Nourishment.LUNCH, MealClassifier.classifyFood(13, emptySet()))
    }

    @Test fun night_food_is_dinner() {
        assertEquals(Nourishment.DINNER, MealClassifier.classifyFood(21, emptySet()))
    }

    @Test fun after_midnight_still_counts_as_dinner() {
        assertEquals(Nourishment.DINNER, MealClassifier.classifyFood(1, emptySet()))
    }

    @Test fun the_evening_window_has_no_anchor_meal_so_food_is_a_snack() {
        assertEquals(Nourishment.SNACK, MealClassifier.classifyFood(17, emptySet()))
    }

    /** The rule the product asked for: FIRST food in the window is the meal. */
    @Test fun a_second_helping_in_the_same_window_is_a_snack() {
        val already = setOf(Nourishment.LUNCH)
        assertEquals(Nourishment.SNACK, MealClassifier.classifyFood(14, already))
    }

    @Test fun a_later_window_is_unaffected_by_an_earlier_meal() {
        val already = setOf(Nourishment.BREAKFAST)
        assertEquals(Nourishment.LUNCH, MealClassifier.classifyFood(13, already))
    }

    @Test fun window_boundaries_are_stable() {
        assertEquals(Nourishment.BREAKFAST, MealClassifier.windowFor(4))
        assertEquals(Nourishment.BREAKFAST, MealClassifier.windowFor(10))
        assertEquals(Nourishment.LUNCH, MealClassifier.windowFor(11))
        assertEquals(Nourishment.LUNCH, MealClassifier.windowFor(15))
        assertEquals(Nourishment.SNACK, MealClassifier.windowFor(16))
        assertEquals(Nourishment.SNACK, MealClassifier.windowFor(18))
        assertEquals(Nourishment.DINNER, MealClassifier.windowFor(19))
        assertEquals(Nourishment.DINNER, MealClassifier.windowFor(3))
    }

    @Test fun out_of_range_hours_are_normalised_rather_than_crashing() {
        assertEquals(MealClassifier.windowFor(8), MealClassifier.windowFor(32))
        assertEquals(MealClassifier.windowFor(20), MealClassifier.windowFor(-4))
    }
}
