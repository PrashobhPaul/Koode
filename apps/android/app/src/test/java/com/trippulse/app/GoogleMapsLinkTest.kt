package com.trippulse.app

import com.trippulse.app.data.routing.GoogleMapsLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleMapsLinkTest {

    private fun near(expected: Double, actual: Double?) = assertEquals(expected, actual!!, 1e-6)

    @Test fun place_pin_data_wins_over_camera_centre() {
        val url = "https://www.google.com/maps/place/LuLu+Mall,+Kochi/@10.0290,76.3050,17z/" +
            "data=!3m1!4b1!4m6!3m5!1s0x3b080c8e94a07a07:0x49921cdfae82660!8m2!3d10.0274822!4d76.3079131!16s"
        val p = GoogleMapsLink.parse(url)
        near(10.0274822, p.point?.lat); near(76.3079131, p.point?.lng)
        assertEquals("LuLu Mall, Kochi", p.name)
    }

    @Test fun camera_centre_used_when_no_pin_data() {
        val p = GoogleMapsLink.parse("https://www.google.com/maps/@10.5276,76.2144,15z")
        near(10.5276, p.point?.lat); near(76.2144, p.point?.lng)
    }

    @Test fun q_and_query_parameters_with_coordinates() {
        near(17.385, GoogleMapsLink.parse("https://maps.google.com/?q=17.385,78.4867").point?.lat)
        near(78.4867, GoogleMapsLink.parse("https://www.google.com/maps/search/?api=1&query=17.385,78.4867").point?.lng)
        near(9.9312, GoogleMapsLink.parse("https://maps.google.com/maps?ll=9.9312,76.2673&z=12").point?.lat)
    }

    @Test fun name_only_link_has_no_point_but_keeps_the_name() {
        val p = GoogleMapsLink.parse("https://www.google.com/maps/search/?api=1&query=Thrissur%20Round")
        assertNull(p.point)
        assertEquals("Thrissur Round", p.name)
    }

    @Test fun consent_redirect_is_unwrapped() {
        val inner = java.net.URLEncoder.encode("https://www.google.com/maps/@48.8584,2.2945,17z", "UTF-8")
        val p = GoogleMapsLink.parse("https://consent.google.com/m?continue=$inner&gl=FR")
        near(48.8584, p.point?.lat)
    }

    @Test fun shared_text_yields_url_and_name() {
        val shared = "LuLu Mall\nhttps://maps.app.goo.gl/AbCdEf12345"
        assertEquals("https://maps.app.goo.gl/AbCdEf12345", GoogleMapsLink.extractUrl(shared))
        assertEquals("LuLu Mall", GoogleMapsLink.sharedName(shared))
        assertNull(GoogleMapsLink.sharedName("https://maps.app.goo.gl/AbCdEf12345"))
    }

    @Test fun non_google_links_are_ignored() {
        assertNull(GoogleMapsLink.extractUrl("see https://example.com/maps/place/x"))
        assertNull(GoogleMapsLink.extractUrl("no link here"))
    }

    @Test fun raw_coordinates_are_accepted_and_nonsense_rejected() {
        val p = GoogleMapsLink.coordinates("10.5276, 76.2144")
        assertNotNull(p); near(76.2144, p?.lng)
        assertNull(GoogleMapsLink.coordinates("95.0, 76.0"))
        assertNull(GoogleMapsLink.coordinates("0.0, 0.0"))
        assertNull(GoogleMapsLink.coordinates("Thrissur"))
    }
}
