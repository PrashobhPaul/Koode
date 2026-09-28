package com.trippulse.app

import com.trippulse.app.domain.BreakTimeline
import com.trippulse.app.domain.EventNarrator
import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.JourneyAnalytics
import com.trippulse.app.domain.TripEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakTimelineTest {
    private val t0 = 1_758_000_000_000L
    private fun ev(type: String, min: Int, p: Map<String, Any?> = emptyMap()) =
        TripEvent("e$type$min", "T", type, t0 + min * 60_000L, null, null, null, EventSource.DRIVER_CONFIRMATION, p)

    /** One stop where water, a snack and the toilet were logged one tap at a time. */
    private val stop = listOf(
        ev(EventTypes.BREAK_CHECKPOINT, 10, mapOf("breakId" to "b1", "water" to true, "open" to true, "place" to "Kurnool")),
        ev(EventTypes.WATER_REPORTED, 10, mapOf("breakId" to "b1")),
        ev(EventTypes.BREAK_CHECKPOINT, 12, mapOf("breakId" to "b1", "water" to true, "snack" to true, "open" to true, "place" to "Kurnool")),
        ev(EventTypes.SNACK_REPORTED, 12, mapOf("breakId" to "b1")),
        ev(EventTypes.BREAK_CHECKPOINT, 14, mapOf("breakId" to "b1", "water" to true, "snack" to true, "toilet" to true, "open" to true, "place" to "Kurnool")),
        ev(EventTypes.TOILET_REPORTED, 14, mapOf("breakId" to "b1")),
        ev(EventTypes.BREAK_CHECKPOINT, 28, mapOf("breakId" to "b1", "water" to true, "snack" to true, "toilet" to true,
            "open" to false, "durationS" to 1080, "place" to "Kurnool")),
        ev(EventTypes.BREAK_CHECKPOINT_SKIPPED, 29)
    )

    @Test fun one_stop_reads_as_one_break_line() {
        val shown = BreakTimeline.forTimeline(stop, { it.type }, { it.eventTimeMs }, { it.payload })
        assertEquals(1, shown.size)
        assertEquals(t0 + 28 * 60_000L, shown.single().eventTimeMs)
        assertEquals("Break near Kurnool · 18 min · 💧 🍪 🚻", EventNarrator.line(shown.single().type, shown.single().payload).second)
    }

    @Test fun analytics_counts_an_updated_break_once() {
        val r = JourneyAnalytics.analyse(JourneyAnalytics.Inputs(stop, 100_000.0, t0, t0 + 60 * 60_000L))
        assertEquals(1, r.breakCount)
    }

    @Test fun tea_and_snack_are_a_break_but_never_water() {
        val line = BreakTimeline.describe(mapOf("tea" to true, "snack" to true, "durationS" to 900))
        assertTrue(line.contains("☕") && line.contains("🍪"))
        assertFalse(line.contains("💧"))
    }

    @Test fun ongoing_break_says_so_and_unknown_parts_are_left_out() {
        assertEquals("Break · ongoing", BreakTimeline.describe(mapOf("open" to true)))
        assertEquals("Break", BreakTimeline.describe(emptyMap()))
    }

    @Test fun older_breaks_without_an_id_still_show() {
        val legacy = listOf(ev(EventTypes.BREAK_CHECKPOINT, 5, mapOf("water" to true)), ev(EventTypes.WATER_REPORTED, 5))
        assertEquals(2, BreakTimeline.forTimeline(legacy, { it.type }, { it.eventTimeMs }, { it.payload }).size)
    }

    @Test fun late_start_is_labelled_as_an_estimate() {
        val (_, label) = EventNarrator.line(EventTypes.TRIP_STARTED,
            mapOf("startedEarlier" to true, "estimatedDistanceBeforeTrackingM" to 52_400.0))
        assertEquals("Journey started · about 52 km before tracking began (estimated)", label)
    }
}
