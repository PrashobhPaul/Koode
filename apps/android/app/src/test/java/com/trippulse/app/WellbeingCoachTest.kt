package com.trippulse.app

import com.trippulse.app.domain.JourneyUpdates
import com.trippulse.app.domain.WellbeingCoach
import com.trippulse.app.domain.WellbeingCoach.Decision
import com.trippulse.app.domain.WellbeingCoach.DecisionType
import com.trippulse.app.domain.WellbeingCoach.Movement
import com.trippulse.app.domain.WellbeingCoach.Need
import com.trippulse.app.domain.WellbeingCoach.Role
import com.trippulse.app.domain.WellbeingCoach.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coach's promise: suggest to the traveller first, in words that fit how
 * they travel; remind once; and only then — for needs where it is meaningful —
 * tell their followers, neutrally. Never before the traveller had the chance
 * to act, never again once they have, and never as blame.
 */
class WellbeingCoachTest {

    private val min = 60_000L
    private val t0 = 1_759_000_000_000L

    private fun ctx(
        nowMin: Long, mode: String = "CAR", role: Role = WellbeingCoach.defaultRole(mode),
        movement: Movement = Movement.MOVING, continuousSinceMin: Long? = 0,
        waterAtMin: Long? = null, foodAtMin: Long? = null, hour: Int = 14, minuteOfHour: Int = 0
    ) = WellbeingCoach.WellbeingContext(
        nowMs = t0 + nowMin * min, localHour = hour, localMinuteOfDay = hour * 60 + minuteOfHour,
        mode = mode, role = role, movement = movement,
        journeyStartedAtMs = t0, continuousSinceMs = continuousSinceMin?.let { t0 + it * min },
        waterAtMs = waterAtMin?.let { t0 + it * min }, foodAtMs = foodAtMin?.let { t0 + it * min },
        travellerName = "Amma"
    )

    /** Run the coach minute by minute, collecting every decision. */
    private fun run(
        until: Long,
        start: Map<Need, WellbeingCoach.NeedState> = emptyMap(),
        at: (Long) -> WellbeingCoach.WellbeingContext
    ): Pair<List<Pair<Long, Decision>>, Map<Need, WellbeingCoach.NeedState>> {
        var states = start
        val out = mutableListOf<Pair<Long, Decision>>()
        for (m in 0..until) {
            val r = WellbeingCoach.step(at(m), states)
            states = r.states
            r.decisions.forEach { out += m to it }
        }
        return out to states
    }

    private fun List<Pair<Long, Decision>>.of(need: Need) = filter { it.second.need == need }

    // ---- water -------------------------------------------------------------------

    @Test fun water_is_suggested_then_reminded_once_then_followers_informed() {
        val (all, states) = run(200) { ctx(it, hour = 16) }
        val water = all.of(Need.WATER)
        assertEquals(listOf(DecisionType.NUDGE, DecisionType.REMINDER, DecisionType.INFORM_FOLLOWERS), water.map { it.second.type })
        assertEquals(120L, water[0].first)          // car driver: two hours
        assertEquals(145L, water[1].first)          // reminder 25 min later
        assertEquals(170L, water[2].first)          // followers 25 min after that
        assertEquals(Stage.INFORMED, states[Need.WATER]?.stage)
        assertTrue(water[2].second.followerVisible)
        assertFalse(water[0].second.followerVisible)
    }

    @Test fun logging_water_restarts_the_cycle_and_nothing_reaches_followers() {
        val (all, _) = run(200) { m -> ctx(m, hour = 16, waterAtMin = if (m >= 125) 125 else null) }
        val water = all.of(Need.WATER)
        assertEquals(1, water.size)
        assertFalse(water.any { it.second.followerVisible })
    }

    @Test fun acknowledgement_clears_the_open_suggestion() {
        var states = WellbeingCoach.step(ctx(120, hour = 16), emptyMap()).states
        assertEquals(Stage.NUDGED, states[Need.WATER]?.stage)
        val r = WellbeingCoach.step(ctx(121, hour = 16, waterAtMin = 121), states)
        assertTrue(Need.WATER in r.expired)
        assertEquals(Stage.NONE, r.states[Need.WATER]?.stage)
    }

    @Test fun snooze_delays_the_reminder() {
        var states = WellbeingCoach.step(ctx(120, hour = 16), emptyMap()).states
        states = WellbeingCoach.snooze(states, Need.WATER, t0 + 125 * min)
        val (all, _) = run(200, states) { m -> ctx(m + 126, hour = 16) }
        val reminder = all.of(Need.WATER).first()
        assertEquals(DecisionType.REMINDER, reminder.second.type)
        assertEquals(155L - 126, reminder.first)    // 30 min after the snooze
    }

    @Test fun no_duplicate_suggestions_while_waiting() {
        val (all, _) = run(140) { ctx(it, hour = 16) }
        assertEquals(1, all.of(Need.WATER).count { it.second.type == DecisionType.NUDGE })
    }

    @Test fun state_survives_a_restart_and_the_ladder_does_not_start_again() {
        val (_, before) = run(150) { ctx(it, hour = 16) }
        val restored = WellbeingCoach.decode(WellbeingCoach.encode(before))
        assertEquals(before, restored)
        val (after, _) = run(10, restored) { m -> ctx(151 + m, hour = 16) }
        assertTrue(after.of(Need.WATER).none { it.second.type == DecisionType.NUDGE })
    }

    @Test fun older_stored_states_still_decode() {
        val old = WellbeingCoach.decode("water,ESCALATED,1000,500;break,REMINDED,2000,1500")
        assertEquals(Stage.INFORMED, old[Need.WATER]?.stage)
        assertEquals(1, old[Need.BREAK]?.reminders)
        assertEquals(0L, old[Need.WATER]?.cycle)
    }

    // ---- breaks: continuous movement, driver vs passenger ---------------------------

    @Test fun continuous_driving_suggests_a_break_and_explains_why() {
        val (all, _) = run(125) { ctx(it, hour = 16, waterAtMin = it) }
        val brk = all.of(Need.BREAK).single()
        assertEquals(120L, brk.first)
        assertEquals("Time for a break?", brk.second.title)
        assertTrue(brk.second.reasons.any { it.contains("Continuous movement") })
        assertTrue(brk.second.reasons.contains("Role = DRIVER"))
        assertEquals(WellbeingCoach.Confidence.HIGH, brk.second.confidence)
    }

    @Test fun a_short_stop_keeps_the_cycle_and_a_meaningful_one_restarts_it() {
        // Stopped for a few minutes at 100: the anchor (continuousSince) does not move.
        val (short, _) = run(125) { m ->
            ctx(m, hour = 16, waterAtMin = m, movement = if (m in 100..104) Movement.STOPPED else Movement.MOVING)
        }
        assertEquals(120L, short.of(Need.BREAK).single().first)
        // A real break at 100 resets continuous driving to 110.
        val (reset, _) = run(200) { m ->
            ctx(m, hour = 16, waterAtMin = m, foodAtMin = m,
                movement = if (m in 100..109) Movement.STOPPED else Movement.MOVING,
                continuousSinceMin = if (m >= 110) 110 else 0)
        }
        val breaks = reset.of(Need.BREAK).filter { it.second.type == DecisionType.NUDGE }
        assertTrue(breaks.isEmpty())                // 90 min since the break: not yet
    }

    @Test fun a_bike_rider_is_nudged_sooner_than_a_driver() {
        val (all, _) = run(95) { ctx(it, mode = "BIKE", hour = 16, waterAtMin = it) }
        assertEquals(90L, all.of(Need.BREAK).single().first)
        assertTrue(all.of(Need.BREAK).single().second.body.contains("riding"))
    }

    @Test fun a_car_or_cab_passenger_never_gets_driving_break_logic() {
        val (car, _) = run(300) { ctx(it, mode = "CAR", role = Role.PASSENGER, hour = 16, waterAtMin = it, foodAtMin = it) }
        assertTrue(car.of(Need.BREAK).isEmpty())
        val (cab, _) = run(300) { ctx(it, mode = "CAB", hour = 16, waterAtMin = it, foodAtMin = it) }
        assertTrue(cab.of(Need.BREAK).isEmpty())
        val (cabDriver, _) = run(125) { ctx(it, mode = "CAB", role = Role.DRIVER, hour = 16, waterAtMin = it) }
        assertEquals(1, cabDriver.of(Need.BREAK).size)
    }

    @Test fun train_bus_and_flight_get_a_stretch_suggestion_never_a_driving_break() {
        for (mode in listOf("TRAIN", "BUS", "FLIGHT")) {
            val (all, _) = run(300) { ctx(it, mode = mode, hour = 16, waterAtMin = it, foodAtMin = it) }
            val brk = all.of(Need.BREAK)
            assertEquals(mode, 1, brk.size)          // suggested once, never reminded
            assertEquals("Time to stretch?", brk.single().second.title)
            assertFalse(brk.single().second.body.contains("Pull over"))
            assertFalse(brk.single().second.body.contains("stop somewhere safe"))
        }
    }

    @Test fun switching_car_to_train_expires_the_open_break_cycle() {
        var states = WellbeingCoach.step(ctx(120, hour = 16, waterAtMin = 120), emptyMap()).states
        assertEquals(Stage.NUDGED, states[Need.BREAK]?.stage)
        val r = WellbeingCoach.step(ctx(121, mode = "TRAIN", continuousSinceMin = 121, hour = 16, waterAtMin = 121), states)
        // Train watches breaks too, but as a new cycle: the car's driving break is closed.
        assertTrue(Need.BREAK in r.expired)
        states = r.states
        assertEquals(Stage.NONE, states[Need.BREAK]?.stage)
    }

    @Test fun taking_a_break_waits_then_reminds_once() {
        var states = WellbeingCoach.step(ctx(120, hour = 16, waterAtMin = 120), emptyMap()).states
        states = WellbeingCoach.acknowledge(states, Need.BREAK, t0 + 121 * min)
        assertEquals(Stage.ACKNOWLEDGED, states[Need.BREAK]?.stage)
        val (all, _) = run(30, states) { m -> ctx(122 + m, hour = 16, waterAtMin = 122 + m) }
        val reminders = all.of(Need.BREAK)
        assertEquals(1, reminders.size)
        assertEquals(DecisionType.REMINDER, reminders.single().second.type)
        assertEquals(141L - 122, reminders.single().first)   // 20 min after "Taking a break"
    }

    // ---- food: meal windows and feasibility ------------------------------------------

    @Test fun food_waits_for_a_meal_window_and_a_real_gap() {
        // Started at 10:00; lunch window opens at 12:00 but the gap is only 2 h.
        val (early, _) = run(200) { m -> ctx(m, hour = 10 + (m / 60).toInt(), minuteOfHour = (m % 60).toInt(), waterAtMin = m) }
        val food = early.of(Need.FOOD)
        assertEquals(1, food.count { it.second.type == DecisionType.NUDGE })
        assertEquals(150L, food.first().first)        // 12:30 — lunchtime and 2.5 h since the start
        assertTrue(food.first().second.body.startsWith("It's around lunchtime."))
        assertTrue(food.first().second.body.contains("If you're able to stop for food"))
    }

    @Test fun train_food_is_an_optional_suggestion_only() {
        val (all, _) = run(400) { m -> ctx(m, mode = "TRAIN", hour = 10 + (m / 60).toInt(), minuteOfHour = (m % 60).toInt(), waterAtMin = m) }
        val food = all.of(Need.FOOD)
        assertEquals(1, food.size)                     // no reminder, nothing for followers
        assertTrue(food.single().second.body.contains("If food is available on your journey"))
    }

    @Test fun flight_meal_wording_and_no_forced_food_outside_meal_windows() {
        val (lunch, _) = run(200) { m -> ctx(m, mode = "FLIGHT", hour = 10 + (m / 60).toInt(), minuteOfHour = (m % 60).toInt(), waterAtMin = m) }
        assertTrue(lunch.of(Need.FOOD).single().second.body.contains("If a meal is available"))
        // 16:00 → 21:59 on a train: past lunch, before... dinner opens at 19:00 — only then.
        val (evening, _) = run(400) { m -> ctx(m, mode = "TRAIN", hour = 16 + (m / 60).toInt().coerceAtMost(7), waterAtMin = m) }
        evening.of(Need.FOOD).forEach { assertTrue(it.second.body.startsWith("It's around dinner time.")) }
    }

    @Test fun food_reminder_only_reaches_followers_after_a_genuinely_long_gap() {
        // A car driver at lunch 2.5 h in: suggested and reminded, but followers
        // are not told about a 3-hour gap.
        val (all, _) = run(240) { m -> ctx(m, hour = 10 + (m / 60).toInt(), minuteOfHour = (m % 60).toInt(), waterAtMin = m, continuousSinceMin = m) }
        assertTrue(all.of(Need.FOOD).none { it.second.followerVisible })
    }

    // ---- quiet hours, halts, followers' wording ----------------------------------------

    @Test fun a_passenger_is_left_alone_in_the_small_hours_but_a_driver_is_not() {
        val (bus, _) = run(250) { ctx(it, mode = "BUS", hour = 2) }
        assertTrue(bus.isEmpty())
        val (car, _) = run(125) { ctx(it, hour = 2, waterAtMin = it) }
        assertEquals(1, car.of(Need.BREAK).size)
    }

    @Test fun a_confirmed_halt_is_never_interrupted() {
        val (all, _) = run(400) { ctx(it, movement = Movement.HALTED, hour = 20) }
        assertTrue(all.isEmpty())
    }

    @Test fun a_flight_suggests_water_but_never_informs_followers() {
        val (all, _) = run(400) { ctx(it, mode = "FLIGHT", hour = 16) }
        assertTrue(all.of(Need.WATER).isNotEmpty())
        assertTrue(all.none { it.second.followerVisible })
    }

    @Test fun short_metro_hops_are_not_coached() {
        val (all, _) = run(80) { ctx(it, mode = "METRO", hour = 16) }
        assertTrue(all.isEmpty())
        assertNull(WellbeingCoach.rulesFor("WALK"))
    }

    @Test fun follower_wording_is_neutral_and_factual() {
        val (all, _) = run(200) { ctx(it, hour = 16, waterAtMin = it) }
        val update = all.of(Need.BREAK).last().second
        assertTrue(update.followerVisible)
        assertEquals(
            "Amma has been driving for about 2 h 50 m. Koode suggested a break, but no break has been recorded yet.",
            update.body
        )
        val forbidden = listOf("ignor", "fail", "escalat", "caught", "even after", "skipp")
        all.filter { it.second.followerVisible }.forEach { d ->
            forbidden.forEach { word -> assertFalse(d.second.body, d.second.body.lowercase().contains(word)) }
        }
    }

    @Test fun traveller_wording_makes_no_medical_claims() {
        val (all, _) = run(400) { m -> ctx(m, hour = 10 + (m / 60).toInt().coerceAtMost(12), minuteOfHour = (m % 60).toInt()) }
        all.forEach { (_, d) ->
            assertFalse(d.body, d.body.lowercase().contains("dehydrat"))
            assertFalse(d.body, d.body.lowercase().contains("must"))
        }
    }

    // ---- periodic updates ------------------------------------------------------------

    @Test fun the_periodic_update_is_considered_an_hour_in_and_then_hourly() {
        assertFalse(JourneyUpdates.due(null, t0, t0 + 59 * min))
        assertTrue(JourneyUpdates.due(null, t0, t0 + 60 * min))
        assertFalse(JourneyUpdates.due(t0 + 60 * min, t0, t0 + 100 * min))
    }

    @Test fun an_update_is_suppressed_when_nothing_meaningful_changed() {
        val a = JourneyUpdates.Snapshot(50_000.0, t0 + 300 * min, true, t0, null, null)
        assertEquals(listOf("first update"), JourneyUpdates.changes(null, a))
        assertTrue(JourneyUpdates.changes(a, a.copy(coveredM = 55_000.0, etaMs = t0 + 305 * min)).isEmpty())
        assertEquals(listOf("progress"), JourneyUpdates.changes(a, a.copy(coveredM = 61_000.0)))
        assertEquals(listOf("stopped"), JourneyUpdates.changes(a, a.copy(moving = false)))
        assertEquals(listOf("eta moved"), JourneyUpdates.changes(a, a.copy(etaMs = t0 + 320 * min)))
        assertEquals(listOf("water"), JourneyUpdates.changes(a, a.copy(waterAtMs = t0 + 90 * min)))
        assertEquals(a, JourneyUpdates.Snapshot.decode(a.encode()))
    }

    @Test fun the_update_only_states_what_was_recorded() {
        val text = JourneyUpdates.text(
            JourneyUpdates.Facts(
                nowMs = t0 + 100 * min, startedAtMs = t0, moving = true, driving = true, riding = false,
                distanceLeft = "85 km", etaClock = "6:40 pm",
                waterAtMs = t0 + 60 * min, foodAtMs = null, breakAtMs = t0 + 70 * min
            )
        )
        assertEquals("Driving · 1 h 40 m on the road · 85 km to go · ETA 6:40 pm · water 40 m ago · last break 30 m ago", text)
    }
}
