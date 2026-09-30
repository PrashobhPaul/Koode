package com.trippulse.app

import com.trippulse.app.domain.EtaShift
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.FollowerAlerts
import com.trippulse.app.domain.HaltPlanning
import com.trippulse.app.domain.Halts
import com.trippulse.app.domain.JourneyInput
import com.trippulse.app.domain.JourneyPlans
import com.trippulse.app.domain.JourneyStateMachine
import com.trippulse.app.domain.JourneyStatus
import com.trippulse.app.domain.WellbeingCoach.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plans change, and each change is a first-class, versioned event; halts are
 * what the traveller told us, never a guess; only material ETA changes reach
 * followers.
 */
class JourneyPlanTest {

    private val min = 60_000L
    private val t0 = 1_759_000_000_000L
    private val v1 = JourneyPlans.initial(t0, "Thrissur", "CAR", Role.DRIVER.name)

    // ---- plan revisions ---------------------------------------------------------

    @Test fun destination_change_creates_version_two_and_keeps_version_one() {
        val rev = JourneyPlans.revise(v1, t0 + 60 * min, destination = "Kochi")!!
        assertEquals(EventTypes.DESTINATION_CHANGED, rev.eventType)
        assertEquals(2, rev.plan.version)
        assertEquals("Kochi", rev.plan.destination)
        assertEquals("Journey destination changed from Thrissur to Kochi.", rev.text)
        assertEquals("Thrissur", rev.payload["fromDestination"])
        val history = JourneyPlans.decode(JourneyPlans.encode(listOf(v1, rev.plan)))
        assertEquals(listOf("Thrissur", "Kochi"), history.map { it.destination })
    }

    @Test fun mode_change_is_one_event_even_though_the_role_follows() {
        val rev = JourneyPlans.revise(v1, t0, mode = "TRAIN", role = Role.PASSENGER.name)!!
        assertEquals(EventTypes.TRAVEL_MODE_CHANGED, rev.eventType)
        assertEquals("Travel mode changed from car to train.", rev.text)
        assertEquals(Role.PASSENGER.name, rev.plan.role)
    }

    @Test fun planned_halt_created_changed_and_removed() {
        val created = JourneyPlans.revise(v1, t0, halt = JourneyPlans.HaltChange("Salem"))!!
        assertEquals(EventTypes.PLANNED_HALT_CREATED, created.eventType)
        assertEquals("Planning to halt at Salem.", created.text)
        val changed = JourneyPlans.revise(created.plan, t0, halt = JourneyPlans.HaltChange("Coimbatore"))!!
        assertEquals(EventTypes.PLANNED_HALT_CHANGED, changed.eventType)
        assertEquals(EventTypes.HALT_PLAN_CHANGED, changed.eventType)
        assertEquals("Planned halt changed from Salem to Coimbatore.", changed.text)
        val removed = JourneyPlans.revise(changed.plan, t0, halt = JourneyPlans.HaltChange(null))!!
        assertNull(removed.plan.plannedHalt)
        assertEquals(4, removed.plan.version)
    }

    @Test fun several_changes_at_once_are_a_plan_revision() {
        val rev = JourneyPlans.revise(v1, t0, destination = "Kochi", halt = JourneyPlans.HaltChange("Coimbatore"))!!
        assertEquals(EventTypes.JOURNEY_PLAN_REVISED, rev.eventType)
        assertEquals("Journey destination changed from Thrissur to Kochi. Planning to halt at Coimbatore.", rev.text)
    }

    @Test fun no_material_change_means_no_revision() {
        assertNull(JourneyPlans.revise(v1, t0, destination = "Thrissur", mode = "CAR"))
        assertNull(JourneyPlans.revise(v1, t0, halt = JourneyPlans.HaltChange("  ")))
        assertNull(JourneyPlans.revise(v1, t0))
    }

    @Test fun plan_changes_never_speak_of_route_correctness() {
        val texts = listOf(
            JourneyPlans.revise(v1, t0, destination = "Kochi")!!.text,
            JourneyPlans.revise(v1, t0, mode = "BUS")!!.text,
            JourneyPlans.revise(v1, t0, role = Role.PASSENGER.name)!!.text
        )
        texts.forEach { t ->
            listOf("deviat", "off-track", "off track", "wrong", "back on track").forEach {
                assertFalse(t, t.lowercase().contains(it))
            }
        }
    }

    // ---- ETA ---------------------------------------------------------------------

    @Test fun only_material_eta_changes_count() {
        val now = t0
        val eta = t0 + 600 * min                   // 10 h left
        assertFalse(EtaShift.significant(eta, eta + 40 * min, now))    // 40 min of 10 h
        assertTrue(EtaShift.significant(eta, eta + 130 * min, now))
        val close = t0 + 60 * min                  // 1 h left
        assertFalse(EtaShift.significant(close, close + 20 * min, now))
        assertTrue(EtaShift.significant(close, close + 30 * min, now))
        assertFalse(EtaShift.significant(null, close, now))
    }

    // ---- halts ---------------------------------------------------------------------

    @Test fun a_confirmed_room_reads_factually() {
        assertEquals(
            "Prashobh has taken a room in Salem and is halting here for the night.",
            Halts.confirmedText("Prashobh", Halts.Type.ROOM, "Salem", overnight = true)
        )
        assertEquals("Amma is halting with friends or family in Kochi.",
            Halts.confirmedText("Amma", Halts.Type.FRIEND_FAMILY, "Kochi", overnight = false))
        listOf(Halts.Type.entries.map { Halts.confirmedText("A", it, null, true) },
            listOf(Halts.resumedText("A", "Salem", true), Halts.resumedText("A", null, false), Halts.cancelledText("A"))
        ).flatten().forEach { assertFalse(it, it.lowercase().contains("stuck")) }
    }

    @Test fun resume_wording_distinguishes_confirmed_from_inferred() {
        assertEquals("Amma has resumed the journey after the halt in Salem.", Halts.resumedText("Amma", "Salem", confirmed = true))
        assertEquals("Amma is on the move again after the halt in Salem.", Halts.resumedText("Amma", "Salem", confirmed = false))
    }

    @Test fun older_overnight_answers_map_to_halt_types() {
        assertEquals(Halts.Type.ROOM, Halts.Type.from("HOTEL"))
        assertEquals(Halts.Type.FRIEND_FAMILY, Halts.Type.from("FAMILY"))
        assertEquals(Halts.Type.REST_STOP, Halts.Type.from("VEHICLE"))
        assertEquals(Halts.Type.OTHER, Halts.Type.from(null))
    }

    @Test fun overnight_is_decided_by_the_clock_or_the_expected_length() {
        assertTrue(Halts.isOvernight(21, null))
        assertTrue(Halts.isOvernight(14, 8 * 60))
        assertFalse(Halts.isOvernight(14, 60))
    }

    @Test fun halt_lifecycle_in_the_state_machine() {
        assertEquals(JourneyStatus.OVERNIGHT, JourneyStateMachine.next(JourneyStatus.STOPPED, JourneyInput.OVERNIGHT_CONFIRM))
        assertEquals(JourneyStatus.OVERNIGHT, JourneyStateMachine.next(JourneyStatus.LONG_STOP, JourneyInput.OVERNIGHT_CONFIRM))
        assertEquals(JourneyStatus.LONG_STOP, JourneyStateMachine.next(JourneyStatus.OVERNIGHT, JourneyInput.OVERNIGHT_DECLINE))
        assertEquals(JourneyStatus.DRIVING, JourneyStateMachine.next(JourneyStatus.OVERNIGHT, JourneyInput.RESTART))
        assertNull(JourneyStateMachine.next(JourneyStatus.DRIVING, JourneyInput.OVERNIGHT_CONFIRM))
    }

    @Test fun long_haul_halt_is_suggested_once_to_drivers_only() {
        val eta = t0 + 11 * 60 * min
        assertTrue(HaltPlanning.shouldSuggest(Role.DRIVER, "CAR", null, false, false, t0, eta, 20))
        assertFalse(HaltPlanning.shouldSuggest(Role.DRIVER, "CAR", null, true, false, t0, eta, 20))
        assertFalse(HaltPlanning.shouldSuggest(Role.DRIVER, "CAR", "Salem", false, false, t0, eta, 20))
        assertFalse(HaltPlanning.shouldSuggest(Role.PASSENGER, "CAR", null, false, false, t0, eta, 20))
        assertFalse(HaltPlanning.shouldSuggest(Role.PASSENGER, "TRAIN", null, false, false, t0, eta, 20))
        // A shorter drive that still lands after 11 pm.
        assertTrue(HaltPlanning.shouldSuggest(Role.DRIVER, "CAR", null, false, false, t0, t0 + 4 * 60 * min, 23))
        assertFalse(HaltPlanning.shouldSuggest(Role.DRIVER, "CAR", null, false, false, t0, t0 + 4 * 60 * min, 18))
    }

    // ---- what followers hear ----------------------------------------------------------

    @Test fun follower_levels() {
        assertEquals(FollowerAlerts.Level.SAFETY_CRITICAL, FollowerAlerts.level(EventTypes.SOS_ACTIVATED))
        assertEquals(FollowerAlerts.Level.IMPORTANT, FollowerAlerts.level(EventTypes.DESTINATION_CHANGED))
        assertEquals(FollowerAlerts.Level.IMPORTANT, FollowerAlerts.level(EventTypes.TRAVEL_MODE_CHANGED))
        assertEquals(FollowerAlerts.Level.IMPORTANT, FollowerAlerts.level(EventTypes.HALT_RESUMED))
        assertEquals(FollowerAlerts.Level.MEANINGFUL, FollowerAlerts.level(EventTypes.HALT_CONFIRMED))
        assertEquals(FollowerAlerts.Level.MEANINGFUL, FollowerAlerts.level(EventTypes.TOLL_CROSSED))
        assertEquals(FollowerAlerts.Level.TRAVELLER_ONLY, FollowerAlerts.level(EventTypes.BREAK_NUDGE))
        assertEquals(FollowerAlerts.Level.TRAVELLER_ONLY, FollowerAlerts.level(EventTypes.HALT_SUGGESTED))
        assertEquals(FollowerAlerts.Level.INTERNAL, FollowerAlerts.level(EventTypes.STOP_STARTED))
        assertEquals(FollowerAlerts.Level.INTERNAL, FollowerAlerts.level(EventTypes.ETA_UPDATED))
    }

    @Test fun coaching_never_reaches_followers_and_plan_changes_do() {
        listOf(EventTypes.WATER_NUDGE, EventTypes.FOOD_REMINDER, EventTypes.BREAK_ACKNOWLEDGED, EventTypes.HALT_SUGGESTED)
            .forEach { assertFalse(it, FollowerAlerts.shouldNotify(it, emptyMap())) }
        listOf(EventTypes.DESTINATION_CHANGED, EventTypes.TRAVEL_MODE_CHANGED, EventTypes.PLANNED_HALT_CREATED,
            EventTypes.ETA_SIGNIFICANTLY_CHANGED, EventTypes.HALT_CONFIRMED, EventTypes.HALT_RESUMED,
            EventTypes.HALT_CANCELLED, EventTypes.JOURNEY_PLAN_REVISED)
            .forEach { assertTrue(it, FollowerAlerts.shouldNotify(it, emptyMap())) }
    }

    @Test fun a_mode_switch_is_announced_once() {
        val leg = mapOf<String, Any?>("announcedAs" to EventTypes.TRAVEL_MODE_CHANGED)
        assertFalse(FollowerAlerts.shouldNotify(EventTypes.LEG_STARTED, leg))
        assertFalse(EventTypes.inTimeline(EventTypes.LEG_STARTED, leg))
        assertTrue(FollowerAlerts.shouldNotify(EventTypes.LEG_STARTED, emptyMap()))
        assertTrue(EventTypes.inTimeline(EventTypes.TRAVEL_MODE_CHANGED, emptyMap()))
    }
}
