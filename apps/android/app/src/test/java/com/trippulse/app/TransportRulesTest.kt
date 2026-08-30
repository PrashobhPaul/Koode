package com.trippulse.app

import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.TransportCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport rule engine is what stops the app nagging a train passenger
 * about refuelling, or filing every station halt as a break. These tests pin
 * the behaviour differences the product actually asked for.
 */
class TransportRulesTest {

    @Test fun only_driver_operated_vehicles_are_private() {
        assertTrue(TransportCatalog.CAR.isPrivateVehicle)
        assertTrue(TransportCatalog.BIKE.isPrivateVehicle)
        assertFalse(TransportCatalog.CAB.isPrivateVehicle)
        assertFalse(TransportCatalog.BUS.isPrivateVehicle)
        assertFalse(TransportCatalog.TRAIN.isPrivateVehicle)
        assertFalse(TransportCatalog.FLIGHT.isPrivateVehicle)
    }

    @Test fun refuelling_is_only_ever_asked_of_private_vehicles() {
        assertTrue(TransportCatalog.CAR.asksAboutFuel)
        assertFalse(TransportCatalog.TRAIN.asksAboutFuel)
        assertFalse(TransportCatalog.BUS.asksAboutFuel)
        assertFalse(TransportCatalog.FLIGHT.asksAboutFuel)
    }

    /** A halt on public transport is the timetable, not a break the traveller chose. */
    @Test fun wellbeing_counts_as_a_break_only_in_a_private_vehicle() {
        assertTrue(TransportCatalog.CAR.wellbeingIsBreak)
        assertFalse(TransportCatalog.TRAIN.wellbeingIsBreak)
        assertFalse(TransportCatalog.BUS.wellbeingIsBreak)
        assertFalse(TransportCatalog.CAB.wellbeingIsBreak)
    }

    /** Deviation is meaningless on rails and noise on a fixed bus route. */
    @Test fun route_deviation_is_disabled_where_it_cannot_mean_anything() {
        assertTrue(TransportCatalog.CAR.deviationEnabled)
        assertFalse(TransportCatalog.TRAIN.deviationEnabled)
        assertFalse(TransportCatalog.BUS.deviationEnabled)
        assertFalse(TransportCatalog.FLIGHT.deviationEnabled)
    }

    @Test fun stop_prompts_only_fire_where_the_traveller_is_driving() {
        assertTrue(TransportCatalog.CAR.stopPromptsEnabled)
        assertFalse(TransportCatalog.TRAIN.stopPromptsEnabled)
        assertFalse(TransportCatalog.BUS.stopPromptsEnabled)
    }

    @Test fun public_transport_offers_boarding_milestones_worded_for_its_vehicle() {
        val train = TransportCatalog.TRAIN.quickActions
        assertEquals(
            listOf(EventTypes.BOARDED, EventTypes.TRANSIT_HALTED, EventTypes.TRANSIT_RESUMED, EventTypes.DEBOARDED),
            train.map { it.eventType }
        )
        assertTrue(train.any { it.timelineText == "Boarded the train" })
        assertTrue(train.any { it.timelineText == "Train halted" })
        assertTrue(train.any { it.timelineText == "Deboarded the train" })

        val bus = TransportCatalog.BUS.quickActions
        assertTrue(bus.any { it.timelineText == "Boarded the bus" })
        assertTrue(bus.any { it.timelineText == "Bus halted" })

        val flight = TransportCatalog.FLIGHT.quickActions
        assertTrue(flight.any { it.timelineText == "Boarded the flight" })
    }

    @Test fun a_private_vehicle_keeps_the_original_note_set() {
        val car = TransportCatalog.CAR.quickActions.map { it.eventType }
        assertTrue(car.contains(EventTypes.PASSENGER_JOINED))
        assertTrue(car.contains(EventTypes.MEDICINE))
        assertTrue(car.contains(EventTypes.VEHICLE_ISSUE))
        assertFalse(car.contains(EventTypes.BOARDED))
    }

    @Test fun public_transport_defaults_to_the_battery_saving_cadence() {
        assertEquals(
            com.trippulse.app.core.LocationCadence.SAVER,
            TransportCatalog.TRAIN.defaultCadence
        )
        assertEquals(
            com.trippulse.app.core.LocationCadence.BALANCED,
            TransportCatalog.CAR.defaultCadence
        )
    }

    @Test fun an_unknown_mode_falls_back_to_car_rather_than_crashing() {
        assertEquals(TransportCatalog.CAR, TransportCatalog.profile("HOVERCRAFT"))
        assertEquals(TransportCatalog.CAR, TransportCatalog.profile(null))
    }
}
