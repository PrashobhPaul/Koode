package com.trippulse.app

import com.trippulse.app.domain.DetailKeys
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TravelDetails
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

    @Test fun ship_asks_for_a_seat_and_pnr_and_insists() {
        val fields = TravelDetails.fieldsFor("SHIP")
        val keys = fields.map { it.key }
        assertTrue("ship asks for a seat/cabin", keys.contains(DetailKeys.SEAT))
        assertTrue("ship asks for a PNR", keys.contains(DetailKeys.PNR))
        assertFalse("ship not complete with nothing filled", TravelDetails.isComplete("SHIP", emptyMap()))
    }

    @Test fun older_builds_reading_ship_still_get_a_profile() {
        // Unknown keys fall back to CAR — the rule that keeps old viewers working.
        assertEquals("CAR", TransportCatalog.profile("HOVERCRAFT").key)
    }
}
