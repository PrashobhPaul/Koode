package com.trippulse.app

import com.trippulse.app.domain.Freshness
import com.trippulse.app.domain.JourneyHealth
import com.trippulse.app.domain.JourneyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyHealthTest {

    private val now = 1_700_000_000_000L
    private val h = 3_600_000L

    private fun base(
        journey: String = JourneyStatus.DRIVING.name,
        freshness: Freshness = Freshness.LIVE,
        localHour: Int = 14
    ) = JourneyHealth.Inputs(
        nowMs = now, journey = journey, freshness = freshness,
        foodAtMs = now - h, waterAtMs = now - h, drivingSinceMs = now - h,
        startedAtMs = now - 2 * h, localHour = localHour
    )

    @Test fun calm_daytime_drive_is_normal() {
        val r = JourneyHealth.evaluate(base())
        assertEquals(JourneyHealth.Level.NORMAL, r.level)
        assertEquals("Journey progressing normally", r.headline)
    }

    @Test fun sos_is_concern() {
        val r = JourneyHealth.evaluate(base().copy(sosActive = true))
        assertEquals(JourneyHealth.Level.CONCERN, r.level)
        assertTrue(r.reasons.any { it.contains("SOS") })
    }

    @Test fun offline_is_concern() {
        val r = JourneyHealth.evaluate(base(freshness = Freshness.OFFLINE))
        assertEquals(JourneyHealth.Level.CONCERN, r.level)
    }

    @Test fun long_unexplained_stop_is_concern() {
        val r = JourneyHealth.evaluate(
            base(journey = JourneyStatus.LONG_STOP.name).copy(stopStartedAtMs = now - 2 * h)
        )
        assertEquals(JourneyHealth.Level.CONCERN, r.level)
    }

    @Test fun confirmed_overnight_stop_is_not_concern() {
        val r = JourneyHealth.evaluate(
            base(journey = JourneyStatus.LONG_STOP.name)
                .copy(stopStartedAtMs = now - 2 * h, overnightType = "HOTEL")
        )
        assertEquals(JourneyHealth.Level.NORMAL, r.level)
    }

    @Test fun low_battery_is_attention() {
        val r = JourneyHealth.evaluate(base().copy(batteryPct = 15))
        assertEquals(JourneyHealth.Level.ATTENTION, r.level)
        assertTrue(r.reasons.any { it.contains("battery") })
    }

    @Test fun late_night_driving_is_attention() {
        val r = JourneyHealth.evaluate(base(localHour = 1))
        assertEquals(JourneyHealth.Level.ATTENTION, r.level)
        assertTrue(r.reasons.any { it.contains("late at night") })
    }

    @Test fun long_food_gap_is_attention_with_factual_wording() {
        val r = JourneyHealth.evaluate(base().copy(foodAtMs = now - 6 * h, startedAtMs = now - 7 * h))
        assertEquals(JourneyHealth.Level.ATTENTION, r.level)
        // factual "No food logged", never a medical claim
        assertTrue(r.reasons.any { it.startsWith("No food logged") })
    }

    @Test fun public_transport_never_flags_continuous_driving() {
        // 5h "driving" on a train is just the train doing its job
        val r = JourneyHealth.evaluate(base().copy(drivingSinceMs = now - 5 * h, privateVehicle = false))
        assertEquals(JourneyHealth.Level.NORMAL, r.level)
    }

    @Test fun private_vehicle_flags_continuous_driving() {
        val r = JourneyHealth.evaluate(base().copy(drivingSinceMs = now - 5 * h))
        assertEquals(JourneyHealth.Level.ATTENTION, r.level)
        assertTrue(r.reasons.any { it.contains("without a break") })
    }

    @Test fun flight_offline_window_is_not_concern() {
        // in the expected flying window, silence == flight mode, not danger
        val r = JourneyHealth.evaluate(base(freshness = Freshness.OFFLINE).copy(offlineExpected = true))
        assertEquals(JourneyHealth.Level.NORMAL, r.level)
    }

    @Test fun arrived_is_always_calm() {
        val r = JourneyHealth.evaluate(
            base(journey = JourneyStatus.ARRIVED.name, freshness = Freshness.OFFLINE).copy(sosActive = false)
        )
        assertEquals(JourneyHealth.Level.NORMAL, r.level)
        assertEquals("Arrived safely", r.headline)
    }
}
