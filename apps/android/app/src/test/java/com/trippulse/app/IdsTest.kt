package com.trippulse.app

import com.trippulse.app.core.TripCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {

    @Test fun trip_id_has_expected_shape() {
        val id = TripCredentials.newTripId()
        assertTrue(id.matches(Regex("TP-[A-Z2-9]{4}-[A-Z2-9]{4}")))
    }

    @Test fun secret_has_expected_shape() {
        val s = TripCredentials.newSecret()
        assertTrue(s.matches(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}")))
    }

    @Test fun credentials_avoid_ambiguous_characters() {
        val joined = TripCredentials.newTripId() + TripCredentials.newSecret()
        // no I, L, O, 0, 1
        assertFalse(joined.any { it == 'I' || it == 'L' || it == 'O' || it == '0' || it == '1' })
    }

    @Test fun access_key_is_deterministic() {
        val a = TripCredentials.accessKey("TP-ABCD-EFGH", "JKMN-PQRS-TUVW")
        val b = TripCredentials.accessKey("TP-ABCD-EFGH", "JKMN-PQRS-TUVW")
        assertEquals(a, b)
        assertEquals(64, a.length) // SHA-256 hex
    }

    @Test fun access_key_is_case_insensitive_via_normalize() {
        val a = TripCredentials.accessKey("tp-abcd-efgh", "jkmn-pqrs-tuvw")
        val b = TripCredentials.accessKey("TP-ABCD-EFGH", "JKMN-PQRS-TUVW")
        assertEquals(a, b)
    }

    @Test fun different_secret_yields_different_key() {
        val a = TripCredentials.accessKey("TP-ABCD-EFGH", "JKMN-PQRS-TUVW")
        val b = TripCredentials.accessKey("TP-ABCD-EFGH", "JKMN-PQRS-TUVX")
        assertNotEquals(a, b)
    }

    @Test fun generated_trip_ids_are_unique_across_a_batch() {
        val ids = (1..200).map { TripCredentials.newTripId() }.toSet()
        assertTrue(ids.size >= 199) // effectively no collisions
    }
}
