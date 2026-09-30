package com.trippulse.app

import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.FollowerAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules deciding which followed-journey events reach a Circle member, and
 * how each maps to a stable notification id. Pinned here because the product
 * requirement is "every meaningful status update, without silent drops" — and
 * the two ways that fails are dropping a real event or collapsing several real
 * events into one tray entry.
 */
class FollowerAlertsTest {

    @Test fun meaningful_traveller_events_notify() {
        for (t in listOf(
            EventTypes.TRIP_STARTED, EventTypes.TRIP_RESUMED, EventTypes.TRIP_COMPLETED,
            EventTypes.ARRIVAL_DETECTED, EventTypes.TOLL_CROSSED, EventTypes.LEG_STARTED,
            EventTypes.OVERNIGHT_CONFIRMED, EventTypes.SOS_ACTIVATED, EventTypes.QUICK_NOTE
        )) {
            assertTrue("$t should notify", FollowerAlerts.shouldNotify(t, emptyMap()))
        }
    }

    @Test fun raw_movement_and_housekeeping_stay_silent() {
        for (t in listOf(
            EventTypes.STOP_STARTED, EventTypes.STOP_ENDED, EventTypes.LONG_STOP,
            EventTypes.LOCATION_UPDATE, EventTypes.BATTERY_LOW, EventTypes.ETA_UPDATED,
            EventTypes.NETWORK_ONLINE, EventTypes.NETWORK_OFFLINE,
            // The darkness/health watcher owns these — they must not double-fire here.
            EventTypes.DEVICE_SHUTDOWN, EventTypes.SIM_CHANGED
        )) {
            assertFalse("$t should stay silent", FollowerAlerts.shouldNotify(t, emptyMap()))
        }
    }

    @Test fun private_medical_content_is_never_pushed_to_the_circle() {
        assertFalse(FollowerAlerts.shouldNotify(EventTypes.MEDICINE, emptyMap()))
    }

    @Test fun a_break_notifies_once_via_the_aggregate_not_its_items() {
        // Private-vehicle break: the aggregate speaks…
        assertTrue(
            FollowerAlerts.shouldNotify(
                EventTypes.BREAK_CHECKPOINT, mapOf("breakId" to "b1", "countsAsBreak" to true)
            )
        )
        // …and its member items (which carry the breakId) stay silent.
        assertFalse(
            FollowerAlerts.shouldNotify(EventTypes.FOOD_REPORTED, mapOf("breakId" to "b1"))
        )
        assertFalse(
            FollowerAlerts.shouldNotify(EventTypes.FUEL_STOP, mapOf("breakId" to "b1"))
        )
    }

    @Test fun public_transport_wellbeing_items_notify_standalone() {
        // No aggregate break on public transport (countsAsBreak == false)…
        assertFalse(
            FollowerAlerts.shouldNotify(EventTypes.BREAK_CHECKPOINT, mapOf("countsAsBreak" to false))
        )
        // …so the individual items (no breakId) are what the Circle hears.
        assertTrue(FollowerAlerts.shouldNotify(EventTypes.FOOD_REPORTED, emptyMap()))
        assertTrue(FollowerAlerts.shouldNotify(EventTypes.TEA_COFFEE_REPORTED, emptyMap()))
    }

    @Test fun distinct_events_get_distinct_ids_in_a_safe_band() {
        val a = FollowerAlerts.notificationId("k", EventTypes.TOLL_CROSSED, 1_000L, emptyMap())
        val b = FollowerAlerts.notificationId("k", EventTypes.TOLL_CROSSED, 2_000L, emptyMap())
        val c = FollowerAlerts.notificationId("k", EventTypes.FUEL_STOP, 1_000L, emptyMap())
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        // Clear of the app's reserved notification ids (1001, 2001-2010, 3001).
        for (id in listOf(a, b, c)) assertTrue("id $id in band", id in 5_000..94_999)
    }

    @Test fun an_open_break_and_its_closed_form_share_one_id() {
        val open = FollowerAlerts.notificationId(
            "k", EventTypes.BREAK_CHECKPOINT, 1_000L, mapOf("breakId" to "b1", "open" to true)
        )
        val closed = FollowerAlerts.notificationId(
            "k", EventTypes.BREAK_CHECKPOINT, 5_000L, mapOf("breakId" to "b1", "open" to false)
        )
        // Same stop → same tray entry (the close updates, never duplicates).
        assertEquals(open, closed)
    }

    @Test fun push_and_poll_agree_on_one_event_but_not_across_events() {
        // The server push and the in-app poll must compute the same key for
        // the same event (so it shows once), yet a break's later "closed"
        // event is distinct news and must not be swallowed as a duplicate.
        assertEquals(
            FollowerAlerts.dedupKey("TP-1", EventTypes.TOLL_CROSSED, 42L),
            FollowerAlerts.dedupKey("TP-1", EventTypes.TOLL_CROSSED, 42L)
        )
        assertNotEquals(
            FollowerAlerts.dedupKey("TP-1", EventTypes.BREAK_CHECKPOINT, 1_000L),
            FollowerAlerts.dedupKey("TP-1", EventTypes.BREAK_CHECKPOINT, 5_000L)
        )
        assertNotEquals(
            FollowerAlerts.dedupKey("TP-1", EventTypes.TOLL_CROSSED, 42L),
            FollowerAlerts.dedupKey("TP-2", EventTypes.TOLL_CROSSED, 42L)
        )
    }

    @Test fun the_same_event_re_seen_keeps_the_same_id() {
        val first = FollowerAlerts.notificationId("k", EventTypes.TRIP_STARTED, 42L, emptyMap())
        val again = FollowerAlerts.notificationId("k", EventTypes.TRIP_STARTED, 42L, emptyMap())
        assertEquals(first, again)
    }
}
