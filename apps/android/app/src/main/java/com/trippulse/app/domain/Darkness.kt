package com.trippulse.app.domain

/**
 * When a phone stops reporting, and what that is likely to mean.
 *
 * ## The constraint everything here is built around
 *
 * A powered-off phone cannot report anything. No app can. Whatever the app
 * does about a device going dark, it does *before* the lights go out or from
 * somebody else's device afterwards — never from the dark phone itself. Any
 * design that quietly assumes otherwise is lying to a family at the worst
 * possible moment, so this file assumes the opposite everywhere.
 *
 * That gives three sources of truth, in descending order of reliability:
 *
 *  1. **An explicit goodbye.** Android broadcasts `ACTION_SHUTDOWN` on an
 *     orderly power-off or restart, and the app has a few seconds to write
 *     down where it was and how much battery it had. This is the strongest
 *     evidence there is, and it is also the one a thief can most easily deny
 *     us by holding the power button or pulling the battery.
 *  2. **The last state that reached the server.** Position, accuracy, battery,
 *     and the time of each. Already there for every journey.
 *  3. **Silence.** The absence of updates is itself information, and it is the
 *     only source that still works when the phone is gone. It is evaluated on
 *     the *follower's* device, which is exactly the point: the family's alarm
 *     clock cannot be switched off by whatever happened to the traveller.
 *
 * ## Why battery is the pivot
 *
 * A phone that dies at 2% is sad; a phone that switches off at 74% is a
 * different kind of event, and the difference is the single most useful thing
 * the app can tell a family. Nothing else here matters as much as getting that
 * distinction right, so it is the first thing every rule below looks at.
 */

/** What most likely happened, in the order a person would guess. */
enum class DarkReason {
    /** Reporting normally. */
    NONE,

    /** Battery was nearly flat and then silence. The ordinary sad ending. */
    BATTERY_DIED,

    /** Powered off, or lost, while there was charge left. */
    POWERED_OFF,

    /** Still on as far as we know, but nothing is reaching us. */
    SIGNAL_LOST,

    /** The SIM changed mid-journey. Phones do not do this to themselves. */
    SIM_SWAPPED
}

/**
 * What the app believes about a device that has stopped reporting.
 *
 * [concerning] is deliberately separate from [dark]: a train through a valley
 * is dark and entirely fine, and treating those two as one thing is how a
 * safety app teaches people to ignore it.
 */
data class DarkAssessment(
    val dark: Boolean,
    val reason: DarkReason,
    val concerning: Boolean,
    /** When updates stopped, as far as we can tell. */
    val sinceMs: Long?,
    /** How long it has been silent. */
    val elapsedMs: Long,
    /** Battery at the last word we had, if any. */
    val lastBatteryPct: Int?,
    /**
     * Which alert in the escalating series is now due, or -1 for none.
     * Callers persist the last step they sent and act only when this is
     * greater, which is what stops a poll loop notifying every cycle.
     */
    val escalationStep: Int
) {
    val silent: Boolean get() = dark && reason != DarkReason.NONE
}

object Darkness {

    /**
     * Everything the assessment needs. All nullable fields mean "we never
     * heard", which is different from zero and must stay different.
     */
    data class Inputs(
        val nowMs: Long,
        /** Last time anything at all arrived from the device. */
        val lastUpdateMs: Long?,
        val lastBatteryPct: Int?,
        /** Set when the device told us it was shutting down. */
        val shutdownAtMs: Long? = null,
        /** Battery reported in that goodbye, which is the number that matters. */
        val shutdownBatteryPct: Int? = null,
        /** True when the shutdown broadcast said a restart rather than a power-off. */
        val shutdownWasRestart: Boolean = false,
        val simChangedAtMs: Long? = null,
        /** Off-route when the silence began: two worries compound. */
        val deviationActive: Boolean = false,
        /** Flights and remote rail are expected to be silent for hours. */
        val offlineExpected: Boolean = false,
        /** The journey is finished; silence afterwards means nothing. */
        val journeyClosed: Boolean = false
    )

    /**
     * Below this, a phone was going to die whatever anyone did.
     *
     * Chosen high rather than low on purpose. Calling a 6% shutdown
     * "suspicious" would cry wolf at the most common harmless case, and a
     * family that has been woken once for a flat battery will not believe the
     * alert that matters.
     */
    const val FLAT_BATTERY_PCT = 15

    /**
     * Silence shorter than this is not worth a word. Tunnels, lifts, basement
     * car parks, a village with no mast: all of them look exactly like this
     * and none of them are an emergency.
     */
    const val GRACE_MS = 12 * 60_000L

    /** Silence this long with a healthy battery stops being explicable. */
    const val UNEXPLAINED_MS = 45 * 60_000L

    /**
     * When to tell the family, measured from the moment the device went quiet.
     *
     * Close together at first, because the first hour is when a wrong turn is
     * still a wrong turn rather than a search, and then widening — but never
     * stopping. The twelve-hour mark is deliberately not the end: a journey
     * that has gone dark stays open, and the reminders keep coming, because
     * the alternative is an app that quietly gives up on someone.
     */
    val ESCALATION_MS = longArrayOf(
        15 * 60_000L,
        60 * 60_000L,
        3 * 3_600_000L,
        6 * 3_600_000L,
        12 * 3_600_000L
    )

    /** After the last scheduled step, keep going at this interval, forever. */
    const val REPEAT_AFTER_MS = 12 * 3_600_000L

    fun assess(i: Inputs): DarkAssessment {
        val battery = i.shutdownBatteryPct ?: i.lastBatteryPct

        // A journey nobody is travelling any more cannot go dark.
        if (i.journeyClosed) return quiet(battery)

        // An explicit goodbye is the strongest signal we will ever get, and it
        // outranks the clock: we know the device is off, so there is no point
        // waiting out a grace period for silence we have already been told
        // about.
        if (i.shutdownAtMs != null) {
            val elapsed = (i.nowMs - i.shutdownAtMs).coerceAtLeast(0)
            val flat = battery != null && battery <= FLAT_BATTERY_PCT
            // A restart says the device intends to come back, so it is given
            // the grace period before anyone is woken. A power-off does not.
            val reason = if (flat) DarkReason.BATTERY_DIED else DarkReason.POWERED_OFF
            val concerning = !flat && !(i.shutdownWasRestart && elapsed < GRACE_MS)
            return DarkAssessment(
                dark = true,
                reason = if (i.simChangedAtMs != null) DarkReason.SIM_SWAPPED else reason,
                concerning = concerning || i.simChangedAtMs != null,
                sinceMs = i.shutdownAtMs,
                elapsedMs = elapsed,
                lastBatteryPct = battery,
                escalationStep = stepFor(elapsed, concerning || i.simChangedAtMs != null)
            )
        }

        // A SIM change is never something a phone does to itself, so it is
        // reported the moment it is seen — silent or not.
        if (i.simChangedAtMs != null) {
            val elapsed = (i.nowMs - i.simChangedAtMs).coerceAtLeast(0)
            return DarkAssessment(
                dark = true, reason = DarkReason.SIM_SWAPPED, concerning = true,
                sinceMs = i.simChangedAtMs, elapsedMs = elapsed,
                lastBatteryPct = battery, escalationStep = stepFor(elapsed, true)
            )
        }

        val last = i.lastUpdateMs ?: return quiet(battery)
        val elapsed = (i.nowMs - last).coerceAtLeast(0)
        if (elapsed < GRACE_MS) return quiet(battery)

        // Somewhere we already know has no coverage. Still dark, so the screen
        // says so, but nobody is woken about it.
        if (i.offlineExpected) {
            return DarkAssessment(
                dark = true, reason = DarkReason.SIGNAL_LOST, concerning = false,
                sinceMs = last, elapsedMs = elapsed, lastBatteryPct = battery,
                escalationStep = -1
            )
        }

        val flat = battery != null && battery <= FLAT_BATTERY_PCT
        val reason = if (flat) DarkReason.BATTERY_DIED else DarkReason.SIGNAL_LOST

        // With charge in the tank, silence earns concern once it outlasts every
        // innocent explanation — sooner if they were already off-route, because
        // two unexplained things at once is not two coincidences.
        val threshold = if (i.deviationActive) GRACE_MS else UNEXPLAINED_MS
        val concerning = !flat && elapsed >= threshold

        return DarkAssessment(
            dark = true, reason = reason, concerning = concerning,
            sinceMs = last, elapsedMs = elapsed, lastBatteryPct = battery,
            escalationStep = stepFor(elapsed, concerning)
        )
    }

    private fun quiet(battery: Int?) = DarkAssessment(
        dark = false, reason = DarkReason.NONE, concerning = false,
        sinceMs = null, elapsedMs = 0, lastBatteryPct = battery, escalationStep = -1
    )

    /** Which alert in the series [elapsed] has reached, or -1 if none is due. */
    private fun stepFor(elapsed: Long, concerning: Boolean): Int {
        if (!concerning) return -1
        var step = -1
        for (index in ESCALATION_MS.indices) if (elapsed >= ESCALATION_MS[index]) step = index
        if (step < ESCALATION_MS.lastIndex) return step
        // Past the last scheduled alert, one more for every repeat interval,
        // so the series continues rather than ending.
        val over = elapsed - ESCALATION_MS.last()
        return ESCALATION_MS.lastIndex + (over / REPEAT_AFTER_MS).toInt()
    }

    /**
     * What to say to someone watching. Written to be read on a lock screen at
     * three in the morning by a person who is already frightened: what we know,
     * how long, and never a guess dressed up as a fact.
     */
    fun headline(a: DarkAssessment, who: String): String = when {
        !a.dark -> "$who is reporting normally"
        a.reason == DarkReason.SIM_SWAPPED ->
            "$who's phone has a different SIM in it"
        a.reason == DarkReason.BATTERY_DIED ->
            "$who's phone ran out of battery"
        a.reason == DarkReason.POWERED_OFF && a.concerning ->
            "$who's phone was switched off with battery remaining"
        a.reason == DarkReason.POWERED_OFF ->
            "$who's phone was switched off"
        a.concerning -> "No word from $who"
        else -> "$who's phone is out of signal"
    }

    /**
     * The second line: what it does and does not mean. This is where the app
     * refuses to speculate — "switched off" is a fact, "something happened to
     * them" is not, and only one of those belongs in a notification.
     */
    fun detail(a: DarkAssessment): String = when (a.reason) {
        DarkReason.NONE -> "Updates are arriving as expected."
        DarkReason.BATTERY_DIED ->
            "The battery was at ${a.lastBatteryPct}% at the last update. " +
                "The last known position is saved."
        DarkReason.POWERED_OFF -> buildString {
            append("The phone reported switching off")
            a.lastBatteryPct?.let { append(" with $it% battery left") }
            append(". The last known position is saved.")
        }
        DarkReason.SIGNAL_LOST ->
            "The phone has not been able to reach us. It may simply be out of " +
                "coverage. The last known position is saved."
        DarkReason.SIM_SWAPPED ->
            "A different SIM is in the phone. Koode keeps reporting over any " +
                "network it can reach, so updates may continue. The last known " +
                "position is saved."
    }
}
