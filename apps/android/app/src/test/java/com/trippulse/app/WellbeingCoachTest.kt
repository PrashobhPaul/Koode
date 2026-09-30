package com.trippulse.app

import com.trippulse.app.domain.JourneyUpdates
import com.trippulse.app.domain.WellbeingCoach
import com.trippulse.app.domain.WellbeingCoach.Action
import com.trippulse.app.domain.WellbeingCoach.Need
import com.trippulse.app.domain.WellbeingCoach.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coach's promise: nudge the traveller first, remind once, and only tell
 * the circle when a need is *still* skipped — never before the traveller had
 * the chance to act, and never again once they have.
 */
class WellbeingCoachTest {

    private val min = 60_000L
    private val t0 = 1_759_000_000_000L
    private val car = WellbeingCoach.rulesFor("CAR")!!

    private fun inputs(
        nowMin: Long, moving: Boolean = true, drivingSinceMin: Long? = 0,
        waterAtMin: Long? = null, foodAtMin: Long? = null, hour: Int = 14, overnight: Boolean = false
    ) = WellbeingCoach.Inputs(
        nowMs = t0 + nowMin * min, startedAtMs = t0, moving = moving,
        drivingSinceMs = drivingSinceMin?.let { t0 + it * min },
        waterAtMs = waterAtMin?.let { t0 + it * min }, foodAtMs = foodAtMin?.let { t0 + it * min },
        overnight = overnight, localHour = hour, travellerName = "Amma"
    )

    /** Run the coach minute by minute, collecting every action. */
    private fun run(rules: WellbeingCoach.Rules, until: Long, at: (Long) -> WellbeingCoach.Inputs): List<Pair<Long, Action>> {
        var states = emptyMap<Need, WellbeingCoach.NeedState>()
        val out = mutableListOf<Pair<Long, Action>>()
        for (m in 0..until) {
            val r = WellbeingCoach.step(rules, at(m), states)
            states = r.states
            r.actions.forEach { out += m to it }
        }
        return out
    }

    @Test fun water_is_nudged_then_reminded_then_escalated_in_that_order() {
        val actions = run(car, 200) { inputs(it, drivingSinceMin = it) } // stopping-and-going, no long drive
            .filter { it.second.need == Need.WATER }
        assertEquals(3, actions.size)
        val (nudgeAt, nudge) = actions[0]
        val (remindAt, remind) = actions[1]
        val (escAt, esc) = actions[2]
        assertEquals(120L, nudgeAt)
        assertTrue(nudge is Action.Nudge && !(nudge as Action.Nudge).reminder)
        assertEquals(145L, remindAt)
        assertTrue((remind as Action.Nudge).reminder)
        assertEquals(170L, escAt)
        assertTrue(esc is Action.Escalate)
        assertTrue((esc as Action.Escalate).text.startsWith("Amma hasn't logged any water for 2 h 50 m"))
    }

    @Test fun logging_water_resets_the_ladder_and_nothing_reaches_the_circle() {
        val actions = run(car, 240) { m ->
            // Nudged at 120, drank at 130.
            inputs(m, drivingSinceMin = m, waterAtMin = if (m >= 130) 130 else null)
        }.filter { it.second.need == Need.WATER }
        assertTrue(actions.none { it.second is Action.Escalate })
        assertEquals(listOf(120L, 250L).filter { it <= 240 }, actions.map { it.first })
    }

    @Test fun continuous_driving_asks_for_a_break_and_a_stop_answers_it() {
        val drove = run(car, 125) { inputs(it, drivingSinceMin = 0, waterAtMin = it) }
        assertEquals(Need.BREAK, drove.single().second.need)
        assertEquals(120L, drove.single().first)

        // Stopped (not moving): no break nudges at all.
        val stopped = run(car, 300) { inputs(it, moving = false, drivingSinceMin = null, waterAtMin = it, foodAtMin = it) }
        assertTrue(stopped.isEmpty())
    }

    @Test fun a_bike_rider_is_nudged_sooner_than_a_driver() {
        val bike = WellbeingCoach.rulesFor("BIKE")!!
        val first = run(bike, 100) { inputs(it, drivingSinceMin = 0, waterAtMin = it) }.first()
        assertEquals(90L, first.first)
        assertTrue((first.second as Action.Nudge).body.contains("riding"))
    }

    @Test fun a_passenger_is_left_alone_in_the_small_hours_but_a_driver_is_not() {
        val bus = WellbeingCoach.rulesFor("BUS")!!
        assertTrue(run(bus, 400) { inputs(it, moving = true, drivingSinceMin = null, hour = 2) }.isEmpty())
        assertFalse(run(car, 130) { inputs(it, drivingSinceMin = 0, waterAtMin = it, hour = 2) }.isEmpty())
    }

    @Test fun overnight_rest_is_never_interrupted() {
        assertTrue(run(car, 600) { inputs(it, overnight = true) }.isEmpty())
    }

    @Test fun a_flight_reminds_about_water_but_never_escalates() {
        val flight = WellbeingCoach.rulesFor("FLIGHT")!!
        val actions = run(flight, 400) { inputs(it, drivingSinceMin = null) }
        assertTrue(actions.isNotEmpty())
        assertTrue(actions.all { it.second is Action.Nudge && it.second.need == Need.WATER })
    }

    @Test fun short_metro_hops_are_not_coached() {
        assertNull(WellbeingCoach.rulesFor("METRO"))
    }

    @Test fun snooze_restarts_only_the_current_step() {
        val s = mapOf(Need.WATER to WellbeingCoach.NeedState(Need.WATER, Stage.NUDGED, t0, t0))
        val snoozed = WellbeingCoach.snooze(s, Need.WATER, t0 + 10 * min)
        assertEquals(Stage.NUDGED, snoozed[Need.WATER]!!.stage)
        assertEquals(t0 + 10 * min, snoozed[Need.WATER]!!.stageAtMs)
    }

    @Test fun coach_state_survives_a_restart() {
        val s = mapOf(
            Need.WATER to WellbeingCoach.NeedState(Need.WATER, Stage.REMINDED, t0 + 5, t0),
            Need.BREAK to WellbeingCoach.NeedState(Need.BREAK, Stage.NONE, t0, t0 + 9)
        )
        assertEquals(s, WellbeingCoach.decode(WellbeingCoach.encode(s)))
        assertTrue(WellbeingCoach.decode("garbage;;x,y").isEmpty())
    }

    // ---- the circle's regular update ----------------------------------------

    @Test fun the_hourly_update_is_due_an_hour_in_and_then_hourly() {
        assertFalse(JourneyUpdates.due(null, t0, t0 + 59 * min))
        assertTrue(JourneyUpdates.due(null, t0, t0 + 60 * min))
        assertFalse(JourneyUpdates.due(t0 + 60 * min, t0, t0 + 100 * min))
    }

    @Test fun the_update_says_how_they_are_not_just_where() {
        val text = JourneyUpdates.text(
            JourneyUpdates.Facts(
                nowMs = t0 + 100 * min, startedAtMs = t0, moving = true, driving = true, riding = false,
                distanceLeft = "85 km", etaClock = "6:40 pm",
                waterAtMs = t0 + 60 * min, foodAtMs = null
            )
        )
        assertEquals(
            "Driving · 1 h 40 m on the road · 85 km to go · arriving about 6:40 pm · water 40 m ago · no meal logged yet",
            text
        )
    }
}
