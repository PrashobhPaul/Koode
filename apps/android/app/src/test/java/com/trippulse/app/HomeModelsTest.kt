package com.trippulse.app

import com.trippulse.app.domain.FollowSnapshot
import com.trippulse.app.domain.PersonMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure pieces behind the redesigned Home: the per-person mark that ties
 * a name to the same letter and colour everywhere, and the cached snapshot
 * that puts a followed journey on the map without a network call.
 */
class HomeModelsTest {

    // ---- person marks ------------------------------------------------------

    @Test fun a_name_always_yields_the_same_letter_and_colour() {
        assertEquals(PersonMark.of("Amma"), PersonMark.of("Amma"))
        assertEquals("A", PersonMark.of("amma").initial)
    }

    @Test fun case_and_padding_do_not_change_a_persons_colour() {
        assertEquals(PersonMark.of("Amma").argb, PersonMark.of("  AMMA ").argb)
    }

    @Test fun the_initial_skips_leading_symbols() {
        assertEquals("N", PersonMark.of("  @nima").initial)
        assertEquals("•", PersonMark.of("").initial)
        assertEquals("•", PersonMark.of(null).initial)
    }

    // ---- follow snapshots --------------------------------------------------

    private val meta = mapOf<String, Any?>(
        "ownerName" to "Amma", "transportMode" to "CAR", "destination" to "Thrissur",
        "originLat" to 9.93, "originLng" to 76.26, "destLat" to 10.52, "destLng" to 76.21
    )
    private val state = mapOf<String, Any?>(
        "lat" to 10.1, "lng" to 76.3, "distanceRemainingM" to 42_000.0,
        "etaLikely" to 1_759_000_000_000L, "lastLocationAt" to 1_758_999_000_000L,
        "sosActive" to false
    )

    @Test fun a_snapshot_round_trips_through_storage() {
        val snap = FollowSnapshot.from(meta, state)
        assertEquals(snap, FollowSnapshot.decode(snap.encode()))
        assertTrue(snap.hasPosition)
        assertEquals("Amma", snap.ownerName)
        assertEquals(42_000.0, snap.distanceRemainingM!!, 0.0)
    }

    @Test fun no_fix_yet_means_no_position_not_a_made_up_one() {
        val snap = FollowSnapshot.from(meta, mapOf("sosActive" to false))
        assertFalse(snap.hasPosition)
        assertNull(FollowSnapshot.decode(snap.encode())!!.lat)
    }

    @Test fun a_blank_owner_name_is_treated_as_unknown() {
        assertNull(FollowSnapshot.from(meta + ("ownerName" to "  "), state).ownerName)
    }

    @Test fun garbage_in_storage_reads_as_no_snapshot() {
        assertNull(FollowSnapshot.decode("not json"))
        assertNull(FollowSnapshot.decode(null))
    }
}
