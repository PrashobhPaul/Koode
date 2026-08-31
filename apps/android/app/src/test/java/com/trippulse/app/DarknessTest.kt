package com.trippulse.app

import com.trippulse.app.domain.DarkReason
import com.trippulse.app.domain.Darkness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether to wake a family in the night.
 *
 * These matter more than anything else in the codebase in both directions: a
 * missed alert is the failure the app exists to prevent, and a false one is
 * how the app teaches people to ignore it. Both are tested here.
 */
class DarknessTest {

    private val now = 1_700_000_000_000L
    private fun minutes(n: Long) = n * 60_000L
    private fun hours(n: Long) = n * 3_600_000L

    private fun inputs(
        lastUpdateMs: Long? = now,
        battery: Int? = 80,
        shutdownAtMs: Long? = null,
        shutdownBattery: Int? = null,
        restart: Boolean = false,
        simChangedAtMs: Long? = null,
        deviation: Boolean = false,
        offlineExpected: Boolean = false,
        closed: Boolean = false
    ) = Darkness.Inputs(
        nowMs = now, lastUpdateMs = lastUpdateMs, lastBatteryPct = battery,
        shutdownAtMs = shutdownAtMs, shutdownBatteryPct = shutdownBattery,
        shutdownWasRestart = restart, simChangedAtMs = simChangedAtMs,
        deviationActive = deviation, offlineExpected = offlineExpected,
        journeyClosed = closed
    )

    // ---- the ordinary case: nothing is wrong -----------------------------

    @Test fun a_reporting_phone_is_not_dark() {
        val a = Darkness.assess(inputs(lastUpdateMs = now - minutes(2)))
        assertFalse(a.dark)
        assertFalse(a.concerning)
        assertEquals(DarkReason.NONE, a.reason)
        assertEquals(-1, a.escalationStep)
    }

    @Test fun a_tunnel_is_not_an_emergency() {
        // Ten minutes of silence is a tunnel, a lift, a basement car park, or
        // a village with no mast. Waking anybody for this is how an app gets
        // its notifications turned off.
        val a = Darkness.assess(inputs(lastUpdateMs = now - minutes(10)))
        assertFalse(a.dark)
    }

    @Test fun twenty_minutes_is_dark_but_not_yet_worrying() {
        val a = Darkness.assess(inputs(lastUpdateMs = now - minutes(20)))
        assertTrue(a.dark)
        assertFalse("still inside the explicable window", a.concerning)
        assertEquals(DarkReason.SIGNAL_LOST, a.reason)
    }

    @Test fun an_hour_of_silence_on_a_healthy_battery_is_worrying() {
        val a = Darkness.assess(inputs(lastUpdateMs = now - hours(1)))
        assertTrue(a.dark)
        assertTrue(a.concerning)
        assertEquals(DarkReason.SIGNAL_LOST, a.reason)
    }

    // ---- battery is the pivot -------------------------------------------

    @Test fun a_phone_that_ran_out_of_battery_is_never_alarming() {
        // The commonest harmless ending. Reported, never escalated.
        val a = Darkness.assess(inputs(lastUpdateMs = now - hours(6), battery = 3))
        assertTrue(a.dark)
        assertFalse(a.concerning)
        assertEquals(DarkReason.BATTERY_DIED, a.reason)
        assertEquals(-1, a.escalationStep)
    }

    @Test fun switched_off_with_charge_left_is_alarming_immediately() {
        // The distinction the whole feature exists for.
        val a = Darkness.assess(inputs(shutdownAtMs = now - minutes(1), shutdownBattery = 74))
        assertTrue(a.dark)
        assertTrue(a.concerning)
        assertEquals(DarkReason.POWERED_OFF, a.reason)
    }

    @Test fun switched_off_on_a_flat_battery_is_not() {
        val a = Darkness.assess(inputs(shutdownAtMs = now - minutes(1), shutdownBattery = 4))
        assertTrue(a.dark)
        assertFalse(a.concerning)
        assertEquals(DarkReason.BATTERY_DIED, a.reason)
    }

    @Test fun the_flat_threshold_is_generous_on_purpose() {
        // 15% counts as flat. Calling a 12% shutdown suspicious would cry wolf
        // at a case that happens to somebody every single day.
        assertFalse(
            Darkness.assess(inputs(shutdownAtMs = now, shutdownBattery = 12)).concerning
        )
        assertTrue(
            Darkness.assess(inputs(shutdownAtMs = now, shutdownBattery = 30)).concerning
        )
    }

    @Test fun a_restart_gets_a_moment_before_anyone_is_woken() {
        // A restart says the device means to come back.
        val quiet = Darkness.assess(
            inputs(shutdownAtMs = now - minutes(3), shutdownBattery = 80, restart = true)
        )
        assertFalse(quiet.concerning)

        // But it does not get forever.
        val loud = Darkness.assess(
            inputs(shutdownAtMs = now - minutes(30), shutdownBattery = 80, restart = true)
        )
        assertTrue(loud.concerning)
    }

    // ---- compounding worries --------------------------------------------

    @Test fun going_quiet_while_off_route_escalates_far_sooner() {
        // Two unexplained things at once are not two coincidences.
        val onRoute = Darkness.assess(inputs(lastUpdateMs = now - minutes(20)))
        val offRoute = Darkness.assess(inputs(lastUpdateMs = now - minutes(20), deviation = true))
        assertFalse(onRoute.concerning)
        assertTrue(offRoute.concerning)
    }

    @Test fun a_sim_change_is_reported_at_once() {
        // Phones do not swap their own SIM.
        val a = Darkness.assess(inputs(simChangedAtMs = now - minutes(1)))
        assertTrue(a.dark)
        assertTrue(a.concerning)
        assertEquals(DarkReason.SIM_SWAPPED, a.reason)
    }

    @Test fun a_sim_change_outranks_a_tidy_shutdown() {
        val a = Darkness.assess(
            inputs(shutdownAtMs = now - minutes(5), shutdownBattery = 4, simChangedAtMs = now - minutes(5))
        )
        assertEquals(DarkReason.SIM_SWAPPED, a.reason)
        assertTrue("a flat battery does not excuse a swapped SIM", a.concerning)
    }

    // ---- silence we already expect ---------------------------------------

    @Test fun a_flight_is_allowed_to_be_silent() {
        val a = Darkness.assess(inputs(lastUpdateMs = now - hours(4), offlineExpected = true))
        assertTrue("still shown as dark", a.dark)
        assertFalse("but nobody is woken", a.concerning)
        assertEquals(-1, a.escalationStep)
    }

    @Test fun a_finished_journey_cannot_go_dark() {
        val a = Darkness.assess(inputs(lastUpdateMs = now - hours(20), closed = true))
        assertFalse(a.dark)
    }

    @Test fun a_journey_that_never_reported_is_not_an_alarm() {
        // Nothing has ever arrived, so there is no silence to measure against.
        assertFalse(Darkness.assess(inputs(lastUpdateMs = null)).dark)
    }

    // ---- the escalating series -------------------------------------------

    @Test fun alerts_widen_rather_than_repeat() {
        fun stepAt(elapsed: Long) =
            Darkness.assess(inputs(shutdownAtMs = now - elapsed, shutdownBattery = 80)).escalationStep

        assertEquals("before the first alert", -1, stepAt(minutes(5)))
        assertEquals(0, stepAt(minutes(15)))
        assertEquals(0, stepAt(minutes(50)))
        assertEquals(1, stepAt(hours(1)))
        assertEquals(2, stepAt(hours(3)))
        assertEquals(3, stepAt(hours(6)))
        assertEquals(4, stepAt(hours(12)))
    }

    @Test fun the_series_never_ends() {
        // Twelve hours is not the end. A journey that has gone dark stays
        // open, and the reminders keep coming, because the alternative is an
        // app that quietly gives up on somebody.
        fun stepAt(elapsed: Long) =
            Darkness.assess(inputs(shutdownAtMs = now - elapsed, shutdownBattery = 80)).escalationStep

        val atTwelve = stepAt(hours(12))
        assertEquals(atTwelve + 1, stepAt(hours(24)))
        assertEquals(atTwelve + 2, stepAt(hours(36)))
        assertTrue("still escalating days later", stepAt(hours(120)) > atTwelve + 5)
    }

    @Test fun a_harmless_silence_never_enters_the_series() {
        // Escalation is gated on concern, not on darkness, so a flat battery
        // and a flight both stay at -1 forever however long they last.
        assertEquals(
            -1,
            Darkness.assess(inputs(lastUpdateMs = now - hours(30), battery = 2)).escalationStep
        )
        assertEquals(
            -1,
            Darkness.assess(inputs(lastUpdateMs = now - hours(30), offlineExpected = true)).escalationStep
        )
    }

    // ---- wording ---------------------------------------------------------

    @Test fun the_headline_states_a_fact_and_never_a_guess() {
        val off = Darkness.assess(inputs(shutdownAtMs = now - minutes(20), shutdownBattery = 74))
        val headline = Darkness.headline(off, "Asha")
        assertTrue(headline.contains("Asha"))
        assertTrue(headline.contains("switched off"))
        // The app records what happened. What it might mean is not its to say.
        for (word in listOf("missing", "danger", "kidnap", "accident", "emergency")) {
            assertFalse(
                "headline must not speculate: $headline",
                headline.lowercase().contains(word)
            )
        }
    }

    @Test fun the_detail_always_says_the_position_is_saved() {
        // This is the sentence that tells a frightened person there is
        // something to act on.
        for (a in listOf(
            Darkness.assess(inputs(shutdownAtMs = now, shutdownBattery = 74)),
            Darkness.assess(inputs(lastUpdateMs = now - hours(2))),
            Darkness.assess(inputs(lastUpdateMs = now - hours(2), battery = 2)),
            Darkness.assess(inputs(simChangedAtMs = now))
        )) {
            assertTrue(
                "reason ${a.reason} should mention the saved position",
                Darkness.detail(a).contains("last known position", ignoreCase = true)
            )
        }
    }
}
