package com.trippulse.app

import com.trippulse.app.data.routing.GoogleMapsLink
import com.trippulse.app.ui.SharedPlaceInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPlaceTest {

    @Test fun lookup_goes_from_most_specific_to_broadest() {
        assertEquals(
            listOf(
                "LuLu Mall, NH 66, Edappally, Kochi",
                "NH 66, Edappally, Kochi",
                "Edappally, Kochi",
                "Kochi",
                "LuLu Mall"
            ),
            GoogleMapsLink.lookupCandidates("LuLu Mall, NH 66, Edappally, Kochi")
        )
    }

    @Test fun arabic_comma_splits_parts_too() {
        val c = GoogleMapsLink.lookupCandidates("Twigs Beauty Lounge\u060C Street\u060C Amman 11821")
        assertEquals("Amman 11821", c[2])
        assertEquals("Twigs Beauty Lounge", GoogleMapsLink.displayName("Twigs Beauty Lounge\u060C Street\u060C Amman 11821"))
    }

    @Test fun a_real_copied_link_is_recognised_but_carries_no_coordinates() {
        // Shape of what Google Maps "Copy link" gives today (verified against a live link).
        val copied = "https://maps.app.goo.gl/QS9xeZqTY7BzB6Vq6?g_st=com.google.maps.preview.copy"
        assertTrue(SharedPlaceInbox.looksLikePlace(copied))
        val expanded = GoogleMapsLink.parse("https://www.google.com/maps?q=LuLu+Mall,+Edappally,+Kochi&ftid=0x3b080c:0x49921c")
        assertNull(expanded.point)
        assertEquals("LuLu Mall, Edappally, Kochi", expanded.name)
    }

    @Test fun a_dropped_pin_link_is_exact() {
        val p = GoogleMapsLink.parse("https://www.google.com/maps?q=10.027482,76.307913")
        assertEquals(10.027482, p.point!!.lat, 1e-9)
    }

    @Test fun ordinary_clipboard_text_is_ignored() {
        assertFalse(SharedPlaceInbox.looksLikePlace("see you at 6"))
        assertFalse(SharedPlaceInbox.looksLikePlace("https://example.com/maps/place/x"))
        assertFalse(SharedPlaceInbox.looksLikePlace(null))
    }

    @Test fun the_same_share_is_never_applied_twice() {
        SharedPlaceInbox.lastConsumed = null
        assertTrue(SharedPlaceInbox.offer("https://maps.app.goo.gl/AbC123"))
        val first = SharedPlaceInbox.take()
        assertEquals("https://maps.app.goo.gl/AbC123", first?.first)
        assertEquals("https://maps.app.goo.gl/AbC123", SharedPlaceInbox.lastConsumed)
        assertNull(SharedPlaceInbox.take())
    }
}
