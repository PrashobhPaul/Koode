package com.trippulse.app

import com.trippulse.app.core.TripCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Credentials are the app's single sharpest usability edge: a number read out
 * over the phone by one person and typed in by another, often an elderly one.
 * These tests pin the properties that make that survivable.
 */
class IdsTest {

    @Test fun journey_id_is_the_prefix_plus_digits_only() {
        val id = TripCredentials.newTripId()
        assertTrue(
            "unexpected id shape: $id",
            id.matches(Regex("TP-[1-9][0-9]{${TripCredentials.CODE_LENGTH - 1}}"))
        )
    }

    @Test fun passcode_is_six_digits() {
        val p = TripCredentials.newPasscode()
        assertTrue(p.matches(Regex("[0-9]{${TripCredentials.PASSCODE_LENGTH}}")))
        assertTrue(TripCredentials.isCompletePasscode(p))
    }

    @Test fun credentials_contain_no_separators_a_paste_could_clip() {
        val joined = TripCredentials.newTripId().removePrefix(TripCredentials.PREFIX) +
            TripCredentials.newPasscode()
        assertFalse(joined.any { it == '-' || it == '_' || it == ' ' })
        assertTrue(joined.all { it.isDigit() })
    }

    // ---- resolve: every plausible thing a follower might type or paste ----

    @Test fun resolve_accepts_bare_digits() {
        assertEquals("TP-40381927", TripCredentials.resolve("40381927"))
    }

    @Test fun resolve_accepts_the_full_printed_form() {
        assertEquals("TP-40381927", TripCredentials.resolve("TP-40381927"))
    }

    @Test fun resolve_accepts_a_grouped_paste_with_spaces() {
        assertEquals("TP-40381927", TripCredentials.resolve("  TP-4038 1927 "))
    }

    @Test fun resolve_accepts_a_clipped_paste_that_lost_the_dash() {
        assertEquals("TP-40381927", TripCredentials.resolve("TP40381927"))
        assertEquals("TP-40381927", TripCredentials.resolve("tp40381927"))
        assertEquals("TP-40381927", TripCredentials.resolve("TP_40381927"))
        assertEquals("TP-40381927", TripCredentials.resolve("TP 4038 1927"))
    }

    @Test fun resolve_rejects_nothing_usable() {
        assertNull(TripCredentials.resolve("   "))
        assertNull(TripCredentials.resolve("TP-"))
    }

    /** Journeys created by older builds used letters; they must still resolve. */
    @Test fun resolve_preserves_legacy_alphanumeric_ids() {
        assertEquals("TP-ABCD-EFGH", TripCredentials.resolve("tp-abcd-efgh"))
    }

    // ---- access key ----

    @Test fun access_key_is_deterministic() {
        val a = TripCredentials.accessKey("TP-40381927", "204815")
        val b = TripCredentials.accessKey("TP-40381927", "204815")
        assertEquals(a, b)
        assertEquals(64, a.length) // SHA-256 hex
    }

    @Test fun access_key_still_matches_legacy_credentials() {
        val a = TripCredentials.accessKey("tp-abcd-efgh", "jkmn-pqrs-tuvw")
        val b = TripCredentials.accessKey("TP-ABCD-EFGH", "JKMN-PQRS-TUVW")
        assertEquals(a, b)
    }

    @Test fun different_passcode_yields_different_key() {
        val a = TripCredentials.accessKey("TP-40381927", "204815")
        val b = TripCredentials.accessKey("TP-40381927", "204816")
        assertNotEquals(a, b)
    }

    @Test fun generated_ids_are_unique_across_a_batch() {
        val ids = (1..300).map { TripCredentials.newTripId() }.toSet()
        assertTrue("collisions in a 300-id batch: ${300 - ids.size}", ids.size >= 298)
    }

    @Test fun pretty_groups_the_digits_for_reading_aloud() {
        assertEquals("TP-4038 1927", TripCredentials.pretty("TP-40381927"))
    }
}
