package com.trippulse.app.domain

/**
 * Koode's wellbeing coach — the part of the product that makes it a travel
 * *companion* rather than a location dot.
 *
 * For each need (water, food, and — when someone is driving or riding — a
 * break from continuous driving) it walks one small ladder:
 *
 *   on track ──(threshold passes)──▶ NUDGED ──(no response)──▶ REMINDED
 *        ▲                                                         │
 *        └────────── the traveller logs it / takes the break ◀──── ESCALATED
 *                                                     (circle told)
 *
 *  1. **Nudge** the traveller, in words that fit how they travel.
 *  2. **Remind** them once if nothing changes.
 *  3. Only if they *still* skip it, **tell the circle** — factually, with the
 *     number of reminders, so the people watching know it isn't nagging but
 *     a real gap.
 *
 * Logging the need (or, for a break, the vehicle stopping) resets its ladder.
 * Everything here is pure: the caller persists [NeedState]s and turns
 * [Action]s into notifications and journey events.
 */
object WellbeingCoach {

    enum class Need(val key: String) {
        WATER("water"), FOOD("food"), BREAK("break");

        companion object {
            fun fromKey(k: String?): Need? = entries.firstOrNull { it.key == k }
        }
    }

    /** Where on the ladder a need is. */
    enum class Stage { NONE, NUDGED, REMINDED, ESCALATED }

    /**
     * Minutes, per travel mode. A null threshold means the coach doesn't
     * watch that need in this mode; a null [escalateAfterMin] means it never
     * involves the circle (a flight is offline anyway).
     */
    data class Rules(
        val waterMin: Int?,
        val foodMin: Int?,
        val breakAfterDrivingMin: Int?,
        val remindAfterMin: Int,
        val escalateAfterMin: Int?,
        /** Someone *operating* the vehicle — changes the wording and quiet hours. */
        val driving: Boolean,
        val riding: Boolean = false
    )

    fun rulesFor(modeKey: String?): Rules? = when (modeKey?.uppercase()) {
        // Driving: a break every 2 h is the standard road-safety advice.
        "CAR" -> Rules(waterMin = 120, foodMin = 300, breakAfterDrivingMin = 120,
            remindAfterMin = 25, escalateAfterMin = 25, driving = true)
        // Riding is more tiring and more exposed: shorter intervals.
        "BIKE" -> Rules(waterMin = 90, foodMin = 300, breakAfterDrivingMin = 90,
            remindAfterMin = 20, escalateAfterMin = 20, driving = true, riding = true)
        // Long passenger journeys: gentler, and the circle only after a longer gap.
        "BUS", "TRAIN", "SHIP", "CAB" -> Rules(waterMin = 180, foodMin = 300, breakAfterDrivingMin = null,
            remindAfterMin = 30, escalateAfterMin = 45, driving = false)
        // In the air: hydration matters, meals are served, and there is no
        // signal to reach the circle with — so no escalation.
        "FLIGHT" -> Rules(waterMin = 150, foodMin = null, breakAfterDrivingMin = null,
            remindAfterMin = 40, escalateAfterMin = null, driving = false)
        // Metro rides are short hops; coaching them would only nag.
        else -> null
    }

    /**
     * @param anchorMs the "last time this need was met" the stage belongs to.
     *   When the real last-met time moves on (water logged, a break taken),
     *   the anchor no longer matches and the ladder starts again.
     */
    data class NeedState(val need: Need, val stage: Stage, val stageAtMs: Long, val anchorMs: Long)

    data class Inputs(
        val nowMs: Long,
        val startedAtMs: Long,
        /** The vehicle is moving right now (break nudges only make sense then). */
        val moving: Boolean,
        /** Start of the current continuous stretch of driving; null when stopped. */
        val drivingSinceMs: Long?,
        val waterAtMs: Long?,
        val foodAtMs: Long?,
        /** Resting for the night: the coach stays quiet. */
        val overnight: Boolean,
        val localHour: Int,
        val travellerName: String
    )

    sealed interface Action {
        val need: Need
        /** Tell the traveller. [reminder] is the second, firmer ask. */
        data class Nudge(override val need: Need, val reminder: Boolean, val title: String, val body: String) : Action
        /** Tell the circle the traveller is still skipping it. */
        data class Escalate(override val need: Need, val text: String, val gapMin: Long, val reminders: Int) : Action
    }

    data class Result(val states: Map<Need, NeedState>, val actions: List<Action>)

    fun step(rules: Rules, inputs: Inputs, states: Map<Need, NeedState>): Result {
        val out = LinkedHashMap<Need, NeedState>()
        val actions = mutableListOf<Action>()
        for (need in Need.entries) {
            val threshold = thresholdMin(rules, need) ?: continue
            val anchor = anchorFor(need, inputs)
            if (anchor == null) {
                // Nothing to measure yet (e.g. a break while not moving): the
                // need is satisfied for now and its ladder is cleared.
                continue
            }
            var st = states[need]?.takeIf { it.anchorMs == anchor }
                ?: NeedState(need, Stage.NONE, inputs.nowMs, anchor)
            val gapMin = (inputs.nowMs - anchor) / 60_000

            if (!quiet(rules, need, inputs)) {
                when (st.stage) {
                    Stage.NONE -> if (gapMin >= threshold) {
                        st = st.copy(stage = Stage.NUDGED, stageAtMs = inputs.nowMs)
                        actions += nudge(rules, need, gapMin, reminder = false, inputs)
                    }
                    Stage.NUDGED -> if (minutesSince(st, inputs) >= rules.remindAfterMin) {
                        st = st.copy(stage = Stage.REMINDED, stageAtMs = inputs.nowMs)
                        actions += nudge(rules, need, gapMin, reminder = true, inputs)
                    }
                    Stage.REMINDED -> {
                        val after = rules.escalateAfterMin
                        if (after != null && minutesSince(st, inputs) >= after) {
                            st = st.copy(stage = Stage.ESCALATED, stageAtMs = inputs.nowMs)
                            actions += Action.Escalate(need, escalationText(rules, need, gapMin, inputs.travellerName), gapMin, reminders = 2)
                        }
                    }
                    Stage.ESCALATED -> Unit // told once; the next log resets it
                }
            }
            out[need] = st
        }
        return Result(out, actions)
    }

    /** Compact storage form: "water,NUDGED,<stageAt>,<anchor>;food,…". */
    fun encode(states: Map<Need, NeedState>): String =
        states.values.joinToString(";") { "${it.need.key},${it.stage.name},${it.stageAtMs},${it.anchorMs}" }

    fun decode(raw: String?): Map<Need, NeedState> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';').mapNotNull { part ->
            val f = part.split(',')
            if (f.size != 4) return@mapNotNull null
            val need = Need.fromKey(f[0]) ?: return@mapNotNull null
            val stage = runCatching { Stage.valueOf(f[1]) }.getOrNull() ?: return@mapNotNull null
            val at = f[2].toLongOrNull() ?: return@mapNotNull null
            val anchor = f[3].toLongOrNull() ?: return@mapNotNull null
            need to NeedState(need, stage, at, anchor)
        }.toMap()
    }

    /** The traveller asked to be reminded later: restart this stage's clock. */
    fun snooze(states: Map<Need, NeedState>, need: Need, nowMs: Long): Map<Need, NeedState> =
        states[need]?.let { states + (need to it.copy(stageAtMs = nowMs)) } ?: states

    // ---- internals -------------------------------------------------------------

    private fun thresholdMin(r: Rules, need: Need): Int? = when (need) {
        Need.WATER -> r.waterMin
        Need.FOOD -> r.foodMin
        Need.BREAK -> r.breakAfterDrivingMin
    }

    private fun anchorFor(need: Need, i: Inputs): Long? = when (need) {
        Need.WATER -> maxOf(i.waterAtMs ?: i.startedAtMs, i.startedAtMs)
        Need.FOOD -> maxOf(i.foodAtMs ?: i.startedAtMs, i.startedAtMs)
        Need.BREAK -> if (i.moving) i.drivingSinceMs else null
    }

    private fun minutesSince(st: NeedState, i: Inputs) = (i.nowMs - st.stageAtMs) / 60_000

    /**
     * When the coach holds its tongue: never overnight, and for a passenger
     * not in the small hours (they may be asleep). A driver at 2 am is
     * exactly who needs the break nudge, so driving is never quiet.
     */
    private fun quiet(r: Rules, need: Need, i: Inputs): Boolean {
        if (i.overnight) return true
        val smallHours = i.localHour >= 23 || i.localHour < 5
        return smallHours && !r.driving
    }

    private fun nudge(r: Rules, need: Need, gapMin: Long, reminder: Boolean, i: Inputs): Action.Nudge {
        val gap = duration(gapMin)
        val (title, body) = when (need) {
            Need.WATER -> if (!reminder) {
                "Time for some water" to
                    (if (r.driving) "$gap since your last drink. Have some water when it's safe to." else "$gap since your last drink — have some water.")
            } else {
                "Still no water?" to "It's been $gap. A few sips now will keep you sharp."
            }
            Need.FOOD -> {
                val meal = mealWord(i.localHour)
                if (!reminder) {
                    "Time to eat" to
                        (if (r.driving) "$gap since you last ate. Plan a stop for $meal." else "$gap since you last ate — grab $meal when you can.")
                } else {
                    "You haven't eaten yet" to "It's been $gap. Eating keeps your energy up for the road."
                }
            }
            Need.BREAK -> {
                val verb = if (r.riding) "riding" else "driving"
                if (!reminder) {
                    "Time for a break" to "You've been $verb for $gap. Pull over somewhere safe and stretch for a few minutes."
                } else {
                    "Please take a break" to "$gap of $verb without a stop. Tiredness creeps up — stop at the next safe place."
                }
            }
        }
        return Action.Nudge(need, reminder, title, body)
    }

    private fun escalationText(r: Rules, need: Need, gapMin: Long, name: String): String {
        val who = name.ifBlank { "The traveller" }
        val gap = duration(gapMin)
        return when (need) {
            Need.WATER -> "$who hasn't logged any water for $gap, even after 2 reminders"
            Need.FOOD -> "$who hasn't eaten for $gap, even after 2 reminders"
            Need.BREAK -> "$who has been ${if (r.riding) "riding" else "driving"} for $gap without a break, even after 2 reminders"
        }
    }

    /** Same windows as [MealClassifier]: breakfast 4–11, lunch 11–16, a snack 16–19, dinner otherwise. */
    fun mealWord(hour: Int): String = when (hour) {
        in 4..10 -> "breakfast"
        in 11..15 -> "lunch"
        in 16..18 -> "a snack"
        else -> "dinner"
    }

    /** "2 h 30 m", "45 m", "3 h". */
    fun duration(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m m"
            m == 0L -> "$h h"
            else -> "$h h $m m"
        }
    }
}
