package com.trippulse.app

import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.SummaryCalculator
import com.trippulse.app.domain.TripEvent
import org.junit.Assert.assertEquals
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

class SummaryTest {

    private val zone = ZoneId.of("UTC")
    private val base = Instant.parse("2026-01-15T08:00:00Z").toEpochMilli()
    private fun at(hours: Double) = base + (hours * 3_600_000).toLong()

    private fun ev(type: String, tMs: Long, payload: Map<String, Any?> = emptyMap()) =
        TripEvent(
            eventId = "e-$type-$tMs", tripId = "t1", type = type, eventTimeMs = tMs,
            lat = 10.0, lng = 76.0, accuracyM = null, source = EventSource.DRIVER_CONFIRMATION,
            payload = payload
        )

    private val events = listOf(
        ev(EventTypes.STOP_STARTED, at(1.0)),
        ev(EventTypes.STOP_ENDED, at(1.0 + 20.0 / 60), mapOf("durationSeconds" to 1200)),
        ev(EventTypes.FOOD_REPORTED, at(1.0 + 20.0 / 60)),
        ev(EventTypes.WATER_REPORTED, at(1.0 + 20.0 / 60)),
        ev(EventTypes.TOILET_REPORTED, at(1.0 + 20.0 / 60)),
        ev(EventTypes.STOP_STARTED, at(3.0)),
        ev(EventTypes.STOP_ENDED, at(3.0 + 15.0 / 60), mapOf("durationSeconds" to 900)),
        ev(EventTypes.REST_REPORTED, at(3.0 + 15.0 / 60)),
        ev(EventTypes.FUEL_STOP, at(3.0 + 15.0 / 60))
    )

    private val summary =
        SummaryCalculator.compute(events, distanceCoveredM = 300_000.0, startedAtMs = base, endedAtMs = at(5.0), zone = zone)

    @Test fun counts_are_correct() {
        assertEquals(2, summary.stops)
        assertEquals(1, summary.foodBreaks)
        assertEquals(1, summary.waterConfirmations)
        assertEquals(1, summary.toiletBreaks)
        assertEquals(1, summary.restBreaks)
        assertEquals(1, summary.fuelStops)
    }

    @Test fun total_and_driving_time() {
        assertEquals(5 * 3600L, summary.totalSeconds)
        // driving = total - confirmed stop durations (1200 + 900)
        assertEquals(5 * 3600L - 2100L, summary.drivingSeconds)
    }

    @Test fun longest_break_is_the_bigger_stop() {
        assertEquals(1200L, summary.longestBreakSeconds)
    }

    @Test fun longest_leg_is_final_leg() {
        // 11:15 -> 13:00 == 1h45m == 6300s
        assertEquals(6300L, summary.longestLegSeconds)
    }

    @Test fun distance_in_km() {
        assertEquals(300.0, summary.distanceKm, 0.001)
    }

    @Test fun single_calendar_day() {
        assertEquals(1, summary.days)
    }
}
