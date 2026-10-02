package com.trippulse.app

import com.trippulse.app.domain.BreakTimeline
import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.TimelineEdits
import com.trippulse.app.domain.TripEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEditsTest {
    private val t0 = 1_758_000_000_000L
    private fun ev(id: String, type: String, min: Int, p: Map<String, Any?> = emptyMap()) =
        TripEvent(id, "T", type, t0 + min * 60_000L, null, null, null, EventSource.DRIVER_CONFIRMATION, p)

    private fun timeline(events: List<TripEvent>): List<TripEvent> {
        val corrected = TimelineEdits.apply(events, { it.eventId }, { it.type }, { it.eventTimeMs }, { it.payload }, { e, p -> e.copy(payload = p) })
        return BreakTimeline.forTimeline(corrected, { it.type }, { it.eventTimeMs }, { it.payload })
            .sortedBy { TimelineEdits.shownTime(it.type, it.payload, it.eventTimeMs) }
    }

    @Test fun a_break_logged_late_sits_at_the_time_it_happened() {
        // Dinner at 21:30 (t0), logged at 02:30 (t0 + 5 h); a stop detected at 23:00 in between.
        val dinner = ev("b", EventTypes.BREAK_CHECKPOINT, 300, mapOf("breakId" to "b1", "food" to true, "startMs" to t0, "durationS" to 2700))
        val stop = ev("s", EventTypes.STOP_STARTED, 90)
        val shown = timeline(listOf(dinner, stop))
        assertEquals(listOf("b", "s"), shown.map { it.eventId })
        assertEquals(t0, TimelineEdits.shownTime(dinner.type, dinner.payload, dinner.eventTimeMs))
    }

    @Test fun a_halt_confirmed_in_the_morning_began_the_night_before() {
        val halt = ev("h", EventTypes.HALT_CONFIRMED, 480, mapOf("sinceMs" to t0 + 60 * 60_000L))
        assertEquals(t0 + 60 * 60_000L, TimelineEdits.shownTime(halt.type, halt.payload, halt.eventTimeMs))
        // The event itself is still written at the time it was logged, so a
        // follower fetching "after the last event I saw" still receives it.
        assertEquals(t0 + 480 * 60_000L, halt.eventTimeMs)
    }

    @Test fun a_revised_break_replaces_the_original_and_a_removed_one_vanishes_with_its_items() {
        val first = ev("b1a", EventTypes.BREAK_CHECKPOINT, 10, mapOf("breakId" to "b1", "water" to true, "startMs" to t0 + 10 * 60_000L, "durationS" to 300))
        val water = ev("w", EventTypes.WATER_REPORTED, 10, mapOf("breakId" to "b1"))
        val revised = ev("b1b", EventTypes.BREAK_CHECKPOINT, 50, mapOf("breakId" to "b1", "water" to true, "startMs" to t0, "durationS" to 900, "revised" to true))
        val shown = timeline(listOf(first, water, revised))
        assertEquals(listOf("b1b"), shown.map { it.eventId })
        assertEquals(t0, TimelineEdits.shownTime(shown[0].type, shown[0].payload, shown[0].eventTimeMs))

        val removed = ev("b1c", EventTypes.BREAK_CHECKPOINT, 60, mapOf("breakId" to "b1", "removed" to true))
        assertTrue(timeline(listOf(first, water, revised, removed)).isEmpty())
        // Analytics stop counting it too.
        assertTrue(BreakTimeline.latestBreaks(listOf(first, water, revised, removed)).none { it.type == EventTypes.BREAK_CHECKPOINT })
    }

    @Test fun a_timeline_edit_retimes_or_removes_a_note_and_never_shows_itself() {
        val note = ev("n", EventTypes.QUICK_NOTE, 100, mapOf("text" to "Tyre changed"))
        val other = ev("o", EventTypes.STOP_STARTED, 50)
        val retime = ev("e1", EventTypes.TIMELINE_EDIT, 200, mapOf("targetEventId" to "n", "atMs" to t0 + 20 * 60_000L))
        val shown = timeline(listOf(note, other, retime))
        assertEquals(listOf("n", "o"), shown.map { it.eventId })
        assertEquals(t0 + 20 * 60_000L, TimelineEdits.shownTime(shown[0].type, shown[0].payload, shown[0].eventTimeMs))

        val remove = ev("e2", EventTypes.TIMELINE_EDIT, 210, mapOf("targetEventId" to "n", "removed" to true))
        assertEquals(listOf("o"), timeline(listOf(note, other, retime, remove)).map { it.eventId })
    }

    @Test fun the_latest_correction_wins_whatever_order_the_log_arrives_in() {
        val note = ev("n", EventTypes.QUICK_NOTE, 100)
        val later = ev("e2", EventTypes.TIMELINE_EDIT, 210, mapOf("targetEventId" to "n", "atMs" to t0 + 30 * 60_000L))
        val earlier = ev("e1", EventTypes.TIMELINE_EDIT, 200, mapOf("targetEventId" to "n", "removed" to true))
        val shown = timeline(listOf(later, note, earlier))
        assertEquals(listOf("n"), shown.map { it.eventId })
        assertEquals(t0 + 30 * 60_000L, TimelineEdits.shownTime(shown[0].type, shown[0].payload, shown[0].eventTimeMs))
    }

    @Test fun only_the_travellers_own_entries_are_editable() {
        assertTrue(EventTypes.BREAK_CHECKPOINT in EventTypes.USER_EDITABLE)
        assertTrue(EventTypes.HALT_CONFIRMED in EventTypes.USER_EDITABLE)
        assertTrue(EventTypes.STOP_STARTED !in EventTypes.USER_EDITABLE)
        assertTrue(EventTypes.DEVICE_BACK_ONLINE !in EventTypes.USER_EDITABLE)
        assertTrue(EventTypes.SOS_ACTIVATED !in EventTypes.USER_EDITABLE)
    }
}
