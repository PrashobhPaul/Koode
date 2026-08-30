package com.trippulse.app

import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.JourneyAnalytics
import com.trippulse.app.domain.Nourishment
import com.trippulse.app.domain.TripEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The analyser is the difference between collecting data and understanding a
 * journey, so it gets tested on the questions a traveller would actually ask.
 */
class JourneyAnalyticsTest {

    private val zone = ZoneId.of("UTC")
    private val base = Instant.parse("2026-03-04T06:00:00Z").toEpochMilli()
    private fun at(hours: Double) = base + (hours * 3_600_000).toLong()

    private fun ev(type: String, tMs: Long, payload: Map<String, Any?> = emptyMap()) =
        TripEvent(
            eventId = "e-$type-$tMs", tripId = "t1", type = type, eventTimeMs = tMs,
            lat = 10.0, lng = 76.0, accuracyM = null, source = EventSource.DRIVER_CONFIRMATION,
            payload = payload
        )

    // A six-hour drive with two stops totalling one hour.
    private val events = listOf(
        ev(EventTypes.TRIP_STARTED, base),
        ev(EventTypes.STOP_STARTED, at(2.0)),
        ev(EventTypes.STOP_ENDED, at(2.5), mapOf("durationSeconds" to 1800)),
        ev(EventTypes.BREAK_CHECKPOINT, at(2.5)),
        ev(EventTypes.FOOD_REPORTED, at(2.5), mapOf("meal" to Nourishment.BREAKFAST.key)),
        ev(EventTypes.WATER_REPORTED, at(2.5)),
        ev(EventTypes.STOP_STARTED, at(4.0)),
        ev(EventTypes.STOP_ENDED, at(4.5), mapOf("durationSeconds" to 1800)),
        ev(EventTypes.BREAK_CHECKPOINT, at(4.5)),
        ev(EventTypes.TEA_COFFEE_REPORTED, at(4.5)),
        ev(EventTypes.FUEL_STOP, at(4.5))
    )

    private val expenses = listOf(
        JourneyAnalytics.ExpenseInput("FUEL", "Diesel", 3000.0, 40.0, "L", at(4.5)),
        JourneyAnalytics.ExpenseInput("FOOD", "Breakfast", 600.0, null, null, at(2.5)),
        JourneyAnalytics.ExpenseInput("OTHER", "Toll", 400.0, null, null, at(1.0))
    )

    private val report = JourneyAnalytics.analyse(
        JourneyAnalytics.Inputs(
            events = events,
            distanceCoveredM = 400_000.0,
            startedAtMs = base,
            endedAtMs = at(6.0),
            expenses = expenses,
            transportMode = "CAR",
            topSpeedKmh = 104.0,
            zone = zone
        )
    )

    // ---- time ----

    @Test fun moving_time_is_total_minus_the_confirmed_stops() {
        assertEquals(6 * 3600L, report.totalSeconds)
        assertEquals(3600L, report.stoppedSeconds)
        assertEquals(5 * 3600L, report.movingSeconds)
    }

    @Test fun the_moving_share_is_the_headline_ratio() {
        assertEquals(5.0 / 6.0, report.movingShare, 0.001)
    }

    @Test fun the_longest_unbroken_stretch_is_found() {
        // 06:00->08:00 = 2h, 08:30->10:00 = 1.5h, 10:30->12:00 = 1.5h
        assertEquals(2 * 3600L, report.longestLegSeconds)
    }

    // ---- pace ----

    @Test fun average_speed_uses_moving_time_and_overall_uses_everything() {
        assertEquals(80.0, report.averageMovingSpeedKmh, 0.01)   // 400 km / 5 h
        assertEquals(66.67, report.overallSpeedKmh, 0.01)        // 400 km / 6 h
    }

    @Test fun top_speed_is_carried_through_when_the_log_kept_one() {
        assertEquals(104.0, report.topSpeedKmh!!, 0.001)
    }

    // ---- breaks ----

    @Test fun breaks_and_their_cadence_are_counted() {
        assertEquals(2, report.stops)
        assertEquals(2, report.breakCount)
        assertEquals(2 * 3600L, report.averageGapBetweenBreaksSeconds)  // 08:30 -> 10:30
    }

    @Test fun a_single_break_yields_no_cadence_rather_than_a_wrong_one() {
        val single = JourneyAnalytics.analyse(
            JourneyAnalytics.Inputs(
                events = listOf(ev(EventTypes.BREAK_CHECKPOINT, at(1.0))),
                distanceCoveredM = 10_000.0,
                startedAtMs = base, endedAtMs = at(2.0), zone = zone
            )
        )
        assertNull(single.averageGapBetweenBreaksSeconds)
    }

    @Test fun meals_are_counted_by_kind() {
        assertEquals(1, report.meals[Nourishment.BREAKFAST])
        assertEquals(1, report.meals[Nourishment.TEA_COFFEE])
        assertEquals(1, report.waterCount)
        assertEquals(1, report.fuelStops)
    }

    // ---- money ----

    @Test fun costs_are_totalled_and_ranked_by_size() {
        assertEquals(4000.0, report.totalCost, 0.001)
        assertEquals("FUEL", report.costLines.first().type)
        assertEquals(0.75, report.costLines.first().share, 0.001)
    }

    @Test fun derived_rates_are_computed() {
        assertEquals(0.01, report.costPerMetre!!, 0.0001)   // 4000 / 400000 m
        assertEquals(666.67, report.costPerHour!!, 0.01)    // 4000 / 6 h
        assertEquals(40.0, report.litres, 0.001)
    }

    @Test fun a_journey_with_no_expenses_reports_no_rates() {
        val free = JourneyAnalytics.analyse(
            JourneyAnalytics.Inputs(
                events = events, distanceCoveredM = 400_000.0,
                startedAtMs = base, endedAtMs = at(6.0), zone = zone
            )
        )
        assertEquals(0.0, free.totalCost, 0.001)
        assertNull(free.costPerMetre)
        assertNull(free.costPerHour)
        assertTrue(free.costLines.isEmpty())
    }

    // ---- insights ----

    @Test fun insights_read_the_numbers_back_in_plain_words() {
        assertTrue(report.insights.isNotEmpty())
        assertTrue(
            "expected a moving-share insight, got ${report.insights}",
            report.insights.any { it.contains("83%") }
        )
        assertTrue(
            "expected the dominant cost called out, got ${report.insights}",
            report.insights.any { it.contains("Fuel") && it.contains("75%") }
        )
    }

    @Test fun a_long_drive_with_no_breaks_is_noticed() {
        val relentless = JourneyAnalytics.analyse(
            JourneyAnalytics.Inputs(
                events = listOf(ev(EventTypes.TRIP_STARTED, base)),
                distanceCoveredM = 500_000.0,
                startedAtMs = base, endedAtMs = at(7.0),
                transportMode = "CAR", zone = zone
            )
        )
        assertTrue(
            "expected a no-breaks insight, got ${relentless.insights}",
            relentless.insights.any { it.contains("No breaks") }
        )
    }

    /** The same silence on a train means nothing — nobody is driving. */
    @Test fun a_long_train_ride_with_no_breaks_is_not_remarked_on() {
        val train = JourneyAnalytics.analyse(
            JourneyAnalytics.Inputs(
                events = listOf(ev(EventTypes.TRIP_STARTED, base)),
                distanceCoveredM = 500_000.0,
                startedAtMs = base, endedAtMs = at(7.0),
                transportMode = "TRAIN", zone = zone
            )
        )
        assertTrue(
            "a train ride should not be told off for not stopping: ${train.insights}",
            train.insights.none { it.contains("No breaks") }
        )
    }

    @Test fun an_empty_journey_analyses_without_dividing_by_zero() {
        val empty = JourneyAnalytics.analyse(
            JourneyAnalytics.Inputs(
                events = emptyList(), distanceCoveredM = 0.0,
                startedAtMs = base, endedAtMs = base, zone = zone
            )
        )
        assertEquals(0L, empty.totalSeconds)
        assertEquals(0.0, empty.averageMovingSpeedKmh, 0.001)
        assertEquals(0.0, empty.overallSpeedKmh, 0.001)
        assertNotNull(empty.insights)
    }
}
