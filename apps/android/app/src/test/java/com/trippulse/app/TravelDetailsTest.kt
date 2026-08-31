package com.trippulse.app

import com.trippulse.app.domain.DetailKeys
import com.trippulse.app.domain.LegDetails
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TravelDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app insists on knowing about a vehicle, and what it merely offers
 * to record.
 *
 * The asymmetry is the whole point: in a private vehicle these details are the
 * safety information someone repeats down a phone, so they are mandatory. On
 * public transport the same fields are conveniences, and a passenger settling
 * into a seat must never be blocked by a form.
 */
class TravelDetailsTest {

    // ---- what each mode asks -------------------------------------------

    @Test fun private_vehicles_require_every_field() {
        val fields = TravelDetails.fieldsFor(TransportCatalog.CAR.key)
        assertEquals(4, fields.size)
        assertTrue("all private-vehicle fields are mandatory", fields.all { it.required })

        val keys = fields.map { it.key }
        assertTrue(keys.contains(DetailKeys.VEHICLE_NAME))
        assertTrue(keys.contains(DetailKeys.VEHICLE_TYPE))
        assertTrue(keys.contains(DetailKeys.FUEL_TYPE))
        assertTrue(keys.contains(DetailKeys.REGISTRATION))
    }

    @Test fun a_bike_is_asked_the_same_as_a_car() {
        assertEquals(
            TravelDetails.fieldsFor(TransportCatalog.CAR.key).map { it.key },
            TravelDetails.fieldsFor(TransportCatalog.BIKE.key).map { it.key }
        )
    }

    @Test fun public_transport_asks_but_never_insists() {
        for (mode in listOf(
            TransportCatalog.CAB.key, TransportCatalog.BUS.key,
            TransportCatalog.TRAIN.key, TransportCatalog.FLIGHT.key
        )) {
            val fields = TravelDetails.fieldsFor(mode)
            assertTrue("$mode should ask something", fields.isNotEmpty())
            assertTrue("$mode must not require anything", fields.none { it.required })
            assertTrue("$mode is always complete", TravelDetails.isComplete(mode, emptyMap()))
        }
    }

    @Test fun a_train_is_asked_for_its_coach_a_car_is_not() {
        val train = TravelDetails.fieldsFor(TransportCatalog.TRAIN.key).map { it.key }
        assertTrue(train.contains(DetailKeys.COACH))
        assertTrue(train.contains(DetailKeys.PNR))

        val car = TravelDetails.fieldsFor(TransportCatalog.CAR.key).map { it.key }
        assertFalse("a coach number means nothing in a car", car.contains(DetailKeys.COACH))
        assertFalse(car.contains(DetailKeys.PNR))
    }

    @Test fun a_car_is_asked_about_fuel_and_a_train_is_not() {
        val car = TravelDetails.fieldsFor(TransportCatalog.CAR.key).map { it.key }
        assertTrue(car.contains(DetailKeys.FUEL_TYPE))
        assertFalse(
            TravelDetails.fieldsFor(TransportCatalog.TRAIN.key).map { it.key }
                .contains(DetailKeys.FUEL_TYPE)
        )
    }

    @Test fun operator_and_vehicle_lists_end_in_other() {
        // The lists are starting points, not gates: a traveller on a bus
        // company we have never heard of must still be able to say so. Fuel
        // is the deliberate exception -- see FUEL_TYPES.
        for (mode in TransportCatalog.ALL.map { it.key }) {
            TravelDetails.fieldsFor(mode)
                .filter { it.isChoice && it.key != DetailKeys.FUEL_TYPE }
                .forEach { field ->
                    assertTrue(
                        mode + "/" + field.key + " must offer an escape hatch",
                        field.options.last().equals("Other", ignoreCase = true)
                    )
                }
        }
    }

    // ---- fuel units ------------------------------------------------------

    @Test fun a_charge_is_measured_in_kilowatt_hours_not_litres() {
        assertEquals("kWh", TravelDetails.fuelUnit("Electric"))
        assertTrue(TravelDetails.isElectric("Electric"))
    }

    @Test fun fuel_unit_ignores_case() {
        // This is the bug these tests were written to catch: the picker stores
        // the label the traveller saw ("Electric"), and the screen used to
        // compare it against "ELECTRIC". An electric car was logging litres.
        for (spelling in listOf("Electric", "ELECTRIC", "electric", " Electric ")) {
            assertEquals("reading $spelling", "kWh", TravelDetails.fuelUnit(spelling))
        }
    }

    @Test fun cng_is_weighed_and_everything_else_is_poured() {
        assertEquals("kg", TravelDetails.fuelUnit("CNG"))
        assertEquals("L", TravelDetails.fuelUnit("Petrol"))
        assertEquals("L", TravelDetails.fuelUnit("Diesel"))
        assertEquals("L", TravelDetails.fuelUnit("Hybrid"))
        // Unknown or unset: litres is the overwhelmingly common case and the
        // one a traveller would assume from an unlabelled box.
        assertEquals("L", TravelDetails.fuelUnit(null))
        assertEquals("L", TravelDetails.fuelUnit(""))
    }

    // ---- completeness ---------------------------------------------------

    @Test fun a_half_described_car_is_not_complete() {
        val partial = mapOf(
            DetailKeys.VEHICLE_NAME to "White Swift",
            DetailKeys.VEHICLE_TYPE to "Hatchback"
        )
        assertFalse(TravelDetails.isComplete(TransportCatalog.CAR.key, partial))

        val missing = TravelDetails.missingRequired(TransportCatalog.CAR.key, partial).map { it.key }
        assertEquals(listOf(DetailKeys.FUEL_TYPE, DetailKeys.REGISTRATION), missing)
    }

    @Test fun blank_is_not_an_answer() {
        // A field submitted as spaces has not been filled in, whatever the
        // text box contains.
        val blanked = mapOf(
            DetailKeys.VEHICLE_NAME to "White Swift",
            DetailKeys.VEHICLE_TYPE to "Hatchback",
            DetailKeys.FUEL_TYPE to "Petrol",
            DetailKeys.REGISTRATION to "   "
        )
        assertFalse(TravelDetails.isComplete(TransportCatalog.CAR.key, blanked))
    }

    @Test fun a_fully_described_car_is_complete() {
        val full = mapOf(
            DetailKeys.VEHICLE_NAME to "White Swift",
            DetailKeys.VEHICLE_TYPE to "Hatchback",
            DetailKeys.FUEL_TYPE to "Petrol",
            DetailKeys.REGISTRATION to "KL08AC1234"
        )
        assertTrue(TravelDetails.isComplete(TransportCatalog.CAR.key, full))
        assertTrue(TravelDetails.missingRequired(TransportCatalog.CAR.key, full).isEmpty())
    }

    @Test fun an_unknown_mode_falls_back_to_car_and_so_insists() {
        // Unknown keys resolve to CAR everywhere else in the app; the safe
        // reading of "we don't know what this is" is the strict one.
        assertFalse(TravelDetails.isComplete("HOVERCRAFT", emptyMap()))
    }

    // ---- the one-line summary -------------------------------------------

    @Test fun summary_describes_a_car_by_what_someone_would_say_aloud() {
        val summary = TravelDetails.summary(
            TransportCatalog.CAR.key,
            mapOf(
                DetailKeys.VEHICLE_NAME to "White Swift",
                DetailKeys.VEHICLE_TYPE to "Hatchback",
                DetailKeys.REGISTRATION to "KL08AC1234"
            )
        )
        assertEquals("White Swift · Hatchback · KL08AC1234", summary)
    }

    @Test fun summary_describes_a_train_by_its_operator_and_seat() {
        val summary = TravelDetails.summary(
            TransportCatalog.TRAIN.key,
            mapOf(
                DetailKeys.OPERATOR to "12626",
                DetailKeys.COACH to "S3",
                DetailKeys.SEAT to "42",
                DetailKeys.PNR to "8912345678"
            )
        )
        // The PNR is deliberately absent: it is a booking secret, and this
        // line is shown on the journey screen and printed in the timeline.
        assertEquals("12626 · S3 · 42", summary)
    }

    @Test fun summary_skips_what_was_never_filled_in() {
        assertEquals(
            "Uber",
            TravelDetails.summary(
                TransportCatalog.CAB.key,
                mapOf(DetailKeys.OPERATOR to "Uber")
            )
        )
        assertEquals("", TravelDetails.summary(TransportCatalog.BUS.key, emptyMap()))
    }

    // ---- storage round-trip ---------------------------------------------

    @Test fun details_survive_a_round_trip() {
        val values = mapOf(
            DetailKeys.OPERATOR to "Kallada",
            DetailKeys.SEAT to "A1",
            DetailKeys.PNR to "PNR123"
        )
        assertEquals(values, LegDetails.fromJson(LegDetails.toJson(values)))
    }

    @Test fun nothing_worth_storing_stores_nothing() {
        // A null column beats "{}": an untouched optional field should cost
        // the row nothing at all.
        assertNull(LegDetails.toJson(emptyMap()))
        assertNull(LegDetails.toJson(mapOf(DetailKeys.SEAT to "  ")))
    }

    @Test fun blanks_do_not_come_back_as_answers() {
        val json = LegDetails.toJson(
            mapOf(DetailKeys.OPERATOR to "Ola", DetailKeys.REGISTRATION to "")
        )
        assertEquals(mapOf(DetailKeys.OPERATOR to "Ola"), LegDetails.fromJson(json))
    }

    @Test fun unreadable_storage_is_treated_as_empty_not_as_a_crash() {
        // A leg whose details cannot be parsed still has to render. Losing a
        // coach number is a blemish; refusing to draw the journey is not.
        assertEquals(emptyMap<String, String>(), LegDetails.fromJson("{not json"))
        assertEquals(emptyMap<String, String>(), LegDetails.fromJson(null))
        assertEquals(emptyMap<String, String>(), LegDetails.fromJson(""))
    }

    @Test fun summaryOf_reads_straight_from_storage() {
        val json = LegDetails.toJson(
            mapOf(DetailKeys.OPERATOR to "Uber", DetailKeys.REGISTRATION to "KL01AB1234")
        )
        assertEquals("Uber", LegDetails.summaryOf(TransportCatalog.CAB.key, json))
    }
}
