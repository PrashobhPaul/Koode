package com.trippulse.app

import com.trippulse.app.domain.PlaceResolver
import com.trippulse.app.domain.PlaceResolver.SavedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule for naming a journey location: the user's saved label wins, then
 * a real geocoded name, then a neutral fallback — never a coordinate, never a
 * placeholder. These pin that precedence so no screen can drift from it.
 */
class PlaceResolverTest {

    // Home at (17.400, 78.500). ~0.001° ≈ 111 m.
    private val home = SavedPlace("Home", 17.400, 78.500)
    private val office = SavedPlace("Office", 12.970, 77.590)
    private val places = listOf(home, office)

    // ---- saved-place matching ----

    @Test fun a_coordinate_on_a_saved_place_takes_its_label() {
        assertEquals("Home", PlaceResolver.nearestSavedLabel(places, 17.4005, 78.5003))
    }

    @Test fun a_coordinate_far_from_every_saved_place_matches_none() {
        assertNull(PlaceResolver.nearestSavedLabel(places, 17.420, 78.500)) // ~2.2 km away
    }

    @Test fun the_nearest_saved_place_wins_when_several_are_in_range() {
        val near = SavedPlace("Corner shop", 17.4009, 78.5000)  // ~100 m
        val label = PlaceResolver.nearestSavedLabel(listOf(home, near), 17.4008, 78.5000)
        assertEquals("Corner shop", label)
    }

    @Test fun a_blank_saved_label_is_never_returned() {
        assertNull(PlaceResolver.nearestSavedLabel(listOf(SavedPlace("  ", 17.400, 78.500)), 17.400, 78.500))
    }

    // ---- the canonical label() precedence ----

    @Test fun a_saved_label_beats_the_geocoder() {
        assertEquals("Home", PlaceResolver.label(savedLabel = "Home", geocoded = "Thrissur, Kerala"))
    }

    @Test fun the_geocoded_name_is_used_when_there_is_no_saved_match() {
        assertEquals("Lulu Mall", PlaceResolver.label(savedLabel = null, geocoded = "Lulu Mall"))
    }

    @Test fun a_failed_geocode_becomes_the_neutral_fallback() {
        assertEquals(PlaceResolver.FALLBACK_LABEL, PlaceResolver.label(savedLabel = null, geocoded = null))
    }

    @Test fun a_coordinate_string_is_never_a_label() {
        assertEquals(PlaceResolver.FALLBACK_LABEL, PlaceResolver.label(null, "17.3850, 78.4867"))
    }

    @Test fun a_placeholder_geocode_becomes_the_fallback() {
        assertEquals(PlaceResolver.FALLBACK_LABEL, PlaceResolver.label(null, "Current location"))
        assertEquals(PlaceResolver.FALLBACK_LABEL, PlaceResolver.label(null, "Pinned destination"))
    }

    // ---- placeholder detection ----

    @Test fun forbidden_placeholders_are_recognised_in_any_case() {
        for (p in listOf(
            "Current location", "current location", "CURRENT LOCATION",
            "Pinned location", "pinned location",
            "Pinned destination", "Unknown location", "location unknown", "En route", ""
        )) {
            assertTrue("'$p' should be a placeholder", PlaceResolver.isPlaceholder(p))
        }
        assertTrue(PlaceResolver.isPlaceholder(null))
        assertTrue(PlaceResolver.isPlaceholder("17.3850, 78.4867"))
        assertTrue(PlaceResolver.isPlaceholder("-8.4, -34.9"))
    }

    @Test fun a_real_place_name_is_not_a_placeholder() {
        assertFalse(PlaceResolver.isPlaceholder("Home"))
        assertFalse(PlaceResolver.isPlaceholder("Kukatpally, Hyderabad"))
        assertFalse(PlaceResolver.isPlaceholder("Gate 3, Airport Road"))
    }

    // ---- display() for stored (historical) labels ----

    @Test fun a_meaningful_stored_label_is_preserved_untouched() {
        assertEquals("Amma's house", PlaceResolver.display("Amma's house", savedLabel = "Home"))
    }

    @Test fun a_stored_placeholder_is_replaced_by_a_saved_match() {
        assertEquals("Home", PlaceResolver.display("Current location", savedLabel = "Home"))
    }

    @Test fun a_stored_coordinate_with_no_saved_match_becomes_the_fallback() {
        assertEquals(PlaceResolver.FALLBACK_LABEL, PlaceResolver.display("17.3850, 78.4867", savedLabel = null))
    }
}
