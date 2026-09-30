package com.trippulse.app

import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.FollowerAlerts
import com.trippulse.app.domain.JourneyClosure
import com.trippulse.app.domain.JourneyClosure.Lifecycle
import com.trippulse.app.domain.JourneyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A journey ends because the traveller ended it — or, when forgotten, after
 * sustained arrival and still for their review. Followers hear nothing until
 * the traveller approves, and "safely" is never inferred.
 */
class JourneyClosureTest {

    private val min = 60_000L
    private val t0 = 1_759_000_000_000L

    @Test fun arrival_is_not_closure() {
        assertEquals(Lifecycle.ACTIVE, JourneyClosure.lifecycle("ACTIVE", JourneyStatus.DRIVING.name, false, null))
        assertEquals(Lifecycle.AWAITING_CLOSURE, JourneyClosure.lifecycle("ACTIVE", JourneyStatus.ARRIVED.name, true, null))
        assertEquals(Lifecycle.DESTINATION_REACHED, JourneyClosure.lifecycle("ACTIVE", JourneyStatus.ARRIVED.name, false, null))
        assertEquals(Lifecycle.PLANNED, JourneyClosure.lifecycle("CREATED", null, false, null))
        // Arrival is a detected fact, not an ending, for followers too.
        assertEquals(EventTypes.ARRIVAL_DETECTED, EventTypes.DESTINATION_REACHED)
    }

    @Test fun one_stronger_reminder_at_fifteen_minutes() {
        assertFalse(JourneyClosure.reminderDue(t0, t0 + 14 * min, reminderSent = false))
        assertTrue(JourneyClosure.reminderDue(t0, t0 + 15 * min, reminderSent = false))
        assertFalse(JourneyClosure.reminderDue(t0, t0 + 20 * min, reminderSent = true))
        assertFalse(JourneyClosure.reminderDue(null, t0 + 20 * min, reminderSent = false))
    }

    @Test fun auto_close_needs_sustained_arrival_and_a_fresh_fix_at_the_destination() {
        val now = t0 + 30 * min
        assertTrue(JourneyClosure.autoCloseDue(t0, now, true, now - 2 * min, true))
        // Not yet 30 minutes.
        assertFalse(JourneyClosure.autoCloseDue(t0, t0 + 29 * min, true, t0 + 28 * min, true))
        // Moved away: the pending close is cancelled (no arrival clock).
        assertFalse(JourneyClosure.autoCloseDue(null, now, true, now, true))
        assertFalse(JourneyClosure.autoCloseDue(t0, now, false, now, true))
        // Last fix outside the radius, or too old to trust.
        assertFalse(JourneyClosure.autoCloseDue(t0, now, true, now, false))
        assertFalse(JourneyClosure.autoCloseDue(t0, now, true, now - 20 * min, true))
        assertFalse(JourneyClosure.autoCloseDue(t0, now, true, null, true))
    }

    @Test fun the_closure_record_survives_a_restart() {
        val r = JourneyClosure.Record(
            Lifecycle.CLOSED_PENDING_REVIEW, t0, auto = true, previousStatus = JourneyStatus.ARRIVED.name,
            closingNote = "Roads were clear, | fine"
        )
        val back = JourneyClosure.Record.decode(r.encode())
        assertEquals(r, back)
        assertTrue(back!!.pendingReview)
        val approved = r.copy(stage = Lifecycle.FINALIZED, approvedAtMs = t0 + min, approvedBy = "Amma", safeConfirmed = true)
        assertEquals(approved, JourneyClosure.Record.decode(approved.encode()))
        assertFalse(approved.pendingReview)
        assertNull(JourneyClosure.Record.decode("garbage"))
        assertEquals(Lifecycle.FINALIZED, JourneyClosure.lifecycle("COMPLETED", null, false, approved))
    }

    @Test fun safely_only_when_the_traveller_said_so() {
        val plain = JourneyClosure.endedText("Amma", "Home", "Thrissur", "6:42 pm", safeConfirmed = false)
        assertEquals("Amma completed the journey from Home to Thrissur at 6:42 pm. The verified journey report is available.", plain)
        assertFalse(plain.lowercase().contains("safe"))
        val safe = JourneyClosure.endedText("Amma", "Home", "Thrissur", "6:42 pm", safeConfirmed = true)
        assertTrue(safe.contains("Amma confirmed arriving safely."))
        assertFalse(JourneyClosure.arrivalText("Amma", "Thrissur").lowercase().contains("safe"))
        assertFalse(JourneyClosure.AUTO_CLOSED_TEXT.lowercase().contains("safe"))
        assertFalse(JourneyClosure.AUTO_CLOSED_TEXT.lowercase().contains("confirmed"))
    }

    @Test fun nothing_about_closing_reaches_followers_before_approval() {
        listOf(
            EventTypes.JOURNEY_CLOSE_PROMPTED, EventTypes.JOURNEY_REOPENED, EventTypes.JOURNEY_CLOSED,
            EventTypes.JOURNEY_AUTO_CLOSED, EventTypes.JOURNEY_REVIEW_STARTED, EventTypes.JOURNEY_ANALYTICS_APPROVED,
            EventTypes.JOURNEY_FINALIZED, EventTypes.TRAVELLER_CONFIRMED_SAFE, EventTypes.TRAVEL_EXPENSES_APPROVED
        ).forEach {
            assertFalse(it, FollowerAlerts.shouldNotify(it, emptyMap()))
            assertEquals(it, FollowerAlerts.Level.TRAVELLER_ONLY, FollowerAlerts.level(it))
        }
        // The approved completion is the one follower notification.
        assertTrue(FollowerAlerts.shouldNotify(EventTypes.TRIP_COMPLETED, emptyMap()))
        // Expenses are private by default.
        assertTrue(EventTypes.isSensitiveByDefault(EventTypes.TRAVEL_EXPENSES_APPROVED))
    }
}
