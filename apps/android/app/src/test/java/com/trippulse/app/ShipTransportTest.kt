package com.trippulse.app

import com.trippulse.app.domain.TransportCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShipTransportTest {

    @Test fun ship_is_offered_and_behaves_like_public_transport() {
        val ship = TransportCatalog.profile("SHIP")
        assertEquals("SHIP", ship.key)
        assertTrue(TransportCatalog.ALL.contains(ship))
        assertFalse(ship.isPrivateVehicle)
        assertFalse(ship.asksAboutFuel)
        assertFalse(ship.stopPromptsEnabled)
        assertFalse(ship.deviationEnabled)
        assertTrue(ship.expectsOfflineStretches)
    }

    @Test fun older_builds_reading_ship_still_get_a_profile() {
        // Unknown keys fall back to CAR — the rule that keeps old viewers working.
        assertEquals("CAR", TransportCatalog.profile("HOVERCRAFT").key)
    }
}
