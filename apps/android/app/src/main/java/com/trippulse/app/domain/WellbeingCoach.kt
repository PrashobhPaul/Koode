package com.trippulse.app.domain

/**
 * Koode's Wellbeing Coach — the part of the product that makes it a travel
 * *companion* rather than a location dot.
 *
 * It is a rule-based, contextual, stateful decision engine, not a set of
 * timers:
 *
 *   signals ─▶ [WellbeingContext] ─▶ [Rules] for this mode *and* role
 *           ─▶ per-need lifecycle ([NeedState]) ─▶ [Decision] with reasons
 *
 * For each need — water, food, and a break from continuous movement — one
 * small lifecycle runs:
 *
 *   NONE ─▶ NUDGED ─┬─▶ (answered: the need is logged, the cycle restarts)
 *                   ├─▶ SNOOZED / ACKNOWLEDGED ─▶ REMINDED
 *                   └─▶ REMINDED ─┬─▶ INFORMED     (followers told, neutrally)
 *                                 └─▶ NO_RESPONSE  (kept to the traveller)
 *
 * What it will not do:
 *  - nag: one nudge, one reminder, then quiet until the need is met;
 *  - tell a passenger to stop a train, or ask a cab passenger to take a
 *    driving break;
 *  - force food when access is uncertain (train, flight: a suggestion only,
 *    in a meal window, never repeated, never shared);
 *  - call silence "ignoring": followers hear facts, never blame.
 *
 * Everything here is pure. The caller persists [NeedState]s (so a restart
 * never resets the ladder), turns [Decision]s into notifications and journey
 * events, and feeds acknowledgements back in.
 */
object WellbeingCoach {

    enum class Need(val key: String) {
        WATER("water"), FOOD("food"), BREAK("break");

        companion object {
            fun fromKey(k: String?): Need? = entries.firstOrNull { it.key == k }
        }
    }

    /** Whether the traveller is operating the vehicle. */
    enum class Role {
        DRIVER, PASSENGER;

        companion object {
            fun fromKey(k: String?): Role? = entries.firstOrNull { it.name == k?.uppercase() }
        }
    }

    /** Car and bike are driven by the traveller unless they say otherwise; everything else is ridden. */
    fun defaultRole(modeKey: String?): Role = when (modeKey?.uppercase()) {
        "CAR", "BIKE" -> Role.DRIVER
        else -> Role.PASSENGER
    }

    enum class Movement {
        MOVING, STOPPED,
        /** The traveller confirmed a halt (room, family, rest stop): the coach stands down. */
        HALTED,
        PAUSED
    }

    /**
     * Where a need's lifecycle is. [INFORMED], [NO_RESPONSE], [DECLINED] and
     * [EXPIRED] end a cycle; the next one starts when the need is met again.
     */
    enum class Stage { NONE, NUDGED, SNOOZED, ACKNOWLEDGED, REMINDED, INFORMED, NO_RESPONSE, DECLINED, EXPIRED }

    enum class Confidence { LOW, MEDIUM, HIGH }

    /** How practical food is right now, which decides how (and whether) to suggest it. */
    enum class FoodAccess {
        /** No food guidance at all. */
        NONE,
        /** Driver or car passenger: they can choose to stop for a meal. */
        PLAN_A_STOP,
        /** Bus or cab passenger: food when the vehicle stops. */
        AT_A_STOP,
        /** Train, ship, flight: only if food happens to be available. */
        IF_AVAILABLE
    }

    enum class BreakKind { DRIVING, RIDING, STRETCH }

    /**
     * The travel-mode profile for one mode and role: hydration, meal, break,
     * snooze, halt-planning and follower-visibility policy in one place, so
     * thresholds are starting defaults to tune — never logic. A null
     * threshold means the coach does not watch that need here. Informing
     * followers needs both an [informAfterMin] and the need in [informNeeds].
     */
    data class Rules(
        val mode: String,
        val role: Role,
        val waterMin: Int?,
        val food: FoodAccess,
        val breakMin: Int?,
        val breakKind: BreakKind,
        /** Minutes between the nudge and the one reminder; null = never remind. */
        val remindAfterMin: Int?,
        /** Minutes after the reminder before followers hear; null = never. */
        val informAfterMin: Int?,
        val informNeeds: Set<Need>,
        /** Hold routine suggestions between 23:00 and 05:00 (the traveller may be asleep). */
        val quietSmallHours: Boolean,
        /** How long "Remind me later" waits. */
        val snoozeMin: Int = SNOOZE_MIN,
        /** Long drives may be offered an overnight-halt plan (only someone at the wheel can choose to halt). */
        val suggestsHaltPlanning: Boolean = false
    )

    /** Snooze, and the grace after "Taking a break", before the one reminder. */
    const val SNOOZE_MIN = 30
    const val ACK_GRACE_MIN = 20

    /** Food outside a meal window is only suggested after this long without it. */
    const val FOOD_LONG_GAP_MIN = 330
    /** Inside a meal window, only once this long has passed since the last meal (or the start). */
    const val FOOD_WINDOW_GAP_MIN = 150

    fun rulesFor(modeKey: String?, role: Role = defaultRole(modeKey)): Rules? {
        val mode = modeKey?.uppercase() ?: return null
        val all = setOf(Need.WATER, Need.FOOD, Need.BREAK)
        return when (mode) {
            // Driving: the road-safety standard is a break every two hours.
            "CAR", "CAB", "AUTO" -> if (role == Role.DRIVER)
                Rules(mode, role, waterMin = 120, food = FoodAccess.PLAN_A_STOP, breakMin = 120,
                    breakKind = BreakKind.DRIVING, remindAfterMin = 25, informAfterMin = 25,
                    informNeeds = all, quietSmallHours = false, suggestsHaltPlanning = true)
            else
                Rules(mode, role, waterMin = 150,
                    food = if (mode == "CAB" || mode == "AUTO") FoodAccess.AT_A_STOP else FoodAccess.PLAN_A_STOP,
                    breakMin = null, breakKind = BreakKind.STRETCH, remindAfterMin = 30, informAfterMin = 45,
                    informNeeds = setOf(Need.WATER, Need.FOOD), quietSmallHours = true)
            // Riding is more tiring and more exposed: shorter intervals.
            "BIKE" -> if (role == Role.DRIVER)
                Rules(mode, role, waterMin = 90, food = FoodAccess.PLAN_A_STOP, breakMin = 90,
                    breakKind = BreakKind.RIDING, remindAfterMin = 20, informAfterMin = 20,
                    informNeeds = all, quietSmallHours = false, suggestsHaltPlanning = true)
            else
                Rules(mode, role, waterMin = 90, food = FoodAccess.PLAN_A_STOP, breakMin = null,
                    breakKind = BreakKind.STRETCH, remindAfterMin = 25, informAfterMin = 30,
                    informNeeds = setOf(Need.WATER, Need.FOOD), quietSmallHours = true)
            // A bus stops on its own schedule: stretch and eat when it does.
            "BUS" -> Rules(mode, role, waterMin = 180, food = FoodAccess.AT_A_STOP, breakMin = 180,
                breakKind = BreakKind.STRETCH, remindAfterMin = 30, informAfterMin = 45,
                informNeeds = setOf(Need.WATER), quietSmallHours = true)
            "TRAIN", "SHIP" -> Rules(mode, role, waterMin = 180, food = FoodAccess.IF_AVAILABLE, breakMin = 180,
                breakKind = BreakKind.STRETCH, remindAfterMin = 30, informAfterMin = 45,
                informNeeds = setOf(Need.WATER), quietSmallHours = true)
            // In the air: hydration and a stretch; meals only if served; no
            // signal to reach anyone with, so nothing is ever shared.
            "FLIGHT" -> Rules(mode, role, waterMin = 120, food = FoodAccess.IF_AVAILABLE, breakMin = 150,
                breakKind = BreakKind.STRETCH, remindAfterMin = 40, informAfterMin = null,
                informNeeds = emptySet(), quietSmallHours = true)
            // Metro rides are short hops. Only an unusually long one earns a
            // single water suggestion, never repeated or shared.
            "METRO" -> Rules(mode, role, waterMin = 90, food = FoodAccess.NONE, breakMin = null,
                breakKind = BreakKind.STRETCH, remindAfterMin = null, informAfterMin = null,
                informNeeds = emptySet(), quietSmallHours = true)
            // On a cycle: pedalling is thirsty work; water often, a stretch when stopped. Nothing shared.
            "CYCLE" -> Rules(mode, role, waterMin = 45, food = FoodAccess.PLAN_A_STOP, breakMin = null,
                breakKind = BreakKind.STRETCH, remindAfterMin = null, informAfterMin = null,
                informNeeds = emptySet(), quietSmallHours = true)
            // On foot: water on a long walk, a meal if it runs through one. Nothing shared.
            "WALK" -> Rules(mode, role, waterMin = 60, food = FoodAccess.PLAN_A_STOP, breakMin = null,
                breakKind = BreakKind.STRETCH, remindAfterMin = null, informAfterMin = null,
                informNeeds = emptySet(), quietSmallHours = true)
            // Anything else: the gentlest useful guidance, kept to the traveller.
            "OTHER" -> Rules(mode, role, waterMin = 150, food = FoodAccess.IF_AVAILABLE, breakMin = null,
                breakKind = BreakKind.STRETCH, remindAfterMin = null, informAfterMin = null,
                informNeeds = emptySet(), quietSmallHours = true)
            else -> null
        }
    }

    /** Everything the coach knows about this moment. Nothing here is invented. */
    data class WellbeingContext(
        val nowMs: Long,
        /** Local hour and minute of day, so meal windows and small hours are the traveller's own. */
        val localHour: Int,
        val localMinuteOfDay: Int = localHour * 60,
        val mode: String,
        val role: Role = defaultRole(mode),
        val movement: Movement,
        val journeyStartedAtMs: Long,
        /** Start of the current continuous movement; short stops do not reset it. */
        val continuousSinceMs: Long?,
        val waterAtMs: Long?,
        val foodAtMs: Long?,
        val travellerName: String
    )

    /**
     * One lifecycle. [anchorMs] is the "need last met" moment this cycle
     * belongs to and [cycle] narrows it further (for food, the meal window);
     * when either moves on, a fresh cycle starts.
     */
    data class NeedState(
        val need: Need,
        val stage: Stage,
        val stageAtMs: Long,
        val anchorMs: Long,
        val cycle: Long = 0,
        val reminders: Int = 0
    )

    enum class DecisionType { NUDGE, REMINDER, INFORM_FOLLOWERS }

    /**
     * What the coach decided and why. [reasons] make every decision
     * explainable; [followerVisible] is true only for [DecisionType.INFORM_FOLLOWERS].
     */
    data class Decision(
        val need: Need,
        val type: DecisionType,
        val title: String,
        val body: String,
        val reasons: List<String>,
        val confidence: Confidence,
        val gapMin: Long
    ) {
        val followerVisible: Boolean get() = type == DecisionType.INFORM_FOLLOWERS
        val reminder: Boolean get() = type == DecisionType.REMINDER
    }

    data class Result(
        val states: Map<Need, NeedState>,
        val decisions: List<Decision>,
        /** Needs whose open suggestion was answered or no longer applies — clear their tray entries. */
        val expired: List<Need>
    )

    fun step(ctx: WellbeingContext, states: Map<Need, NeedState>): Result {
        val rules = rulesFor(ctx.mode, ctx.role)
        val out = LinkedHashMap<Need, NeedState>()
        val decisions = mutableListOf<Decision>()
        val expired = mutableListOf<Need>()

        for (need in Need.entries) {
            val prior = states[need]
            if (rules == null || !watches(rules, need)) {
                // A mode or role change took this need out of scope (car → train
                // ends driving breaks): close any open cycle rather than let it linger.
                if (prior != null && prior.stage.open) {
                    out[need] = prior.copy(stage = Stage.EXPIRED, stageAtMs = ctx.nowMs)
                    expired += need
                } else if (prior != null) out[need] = prior
                continue
            }

            val anchor = anchorFor(need, ctx)
            if (anchor == null) {
                // Not measurable right now (a break while the vehicle is still):
                // keep the cycle exactly as it was.
                prior?.let { out[need] = it }
                continue
            }
            val cycle = cycleFor(need, ctx, prior)
            var st = prior?.takeIf { it.anchorMs == anchor && it.cycle == cycle }
                ?: NeedState(need, Stage.NONE, ctx.nowMs, anchor, cycle)
            // The need was met, or the meal window moved on, while a suggestion
            // was still open: its tray entry no longer applies.
            if (prior != null && prior.stage.open && st !== prior) expired += need
            val gapMin = (ctx.nowMs - anchor) / 60_000

            if (!held(rules, ctx)) {
                val since = (ctx.nowMs - st.stageAtMs) / 60_000
                when (st.stage) {
                    Stage.NONE -> eligibility(rules, need, ctx, gapMin)?.let { reasons ->
                        st = st.copy(stage = Stage.NUDGED, stageAtMs = ctx.nowMs)
                        decisions += suggestion(rules, need, ctx, gapMin, reminder = false, reasons)
                    }
                    Stage.NUDGED -> {
                        val after = remindAfter(rules, need)
                        if (after == null) {
                            if (since >= SNOOZE_MIN) st = st.copy(stage = Stage.NO_RESPONSE, stageAtMs = ctx.nowMs)
                        } else if (since >= after) {
                            st = st.copy(stage = Stage.REMINDED, stageAtMs = ctx.nowMs, reminders = st.reminders + 1)
                            decisions += suggestion(rules, need, ctx, gapMin, reminder = true,
                                listOf("No response to the earlier suggestion for $since min"))
                        }
                    }
                    Stage.SNOOZED, Stage.ACKNOWLEDGED -> {
                        val snoozed = st.stage == Stage.SNOOZED
                        val wait = if (snoozed) rules.snoozeMin else ACK_GRACE_MIN
                        if (since >= wait) {
                            st = st.copy(stage = Stage.REMINDED, stageAtMs = ctx.nowMs, reminders = st.reminders + 1)
                            decisions += suggestion(rules, need, ctx, gapMin, reminder = true, listOf(
                                if (snoozed) "Snoozed $wait min ago" else "Said they were taking a break $wait min ago",
                                "Still unresolved"
                            ))
                        }
                    }
                    Stage.REMINDED -> {
                        val after = rules.informAfterMin
                        if (after != null && need in rules.informNeeds && informable(need, gapMin)) {
                            if (since >= after) {
                                st = st.copy(stage = Stage.INFORMED, stageAtMs = ctx.nowMs)
                                decisions += inform(rules, need, ctx, gapMin, st.reminders)
                            }
                        } else if (since >= SNOOZE_MIN) {
                            st = st.copy(stage = Stage.NO_RESPONSE, stageAtMs = ctx.nowMs)
                        }
                    }
                    Stage.INFORMED, Stage.NO_RESPONSE, Stage.DECLINED, Stage.EXPIRED -> Unit
                }
            }
            out[need] = st
        }
        return Result(out, decisions, expired.distinct())
    }

    /** "Remind me later": wait [SNOOZE_MIN], then one reminder. */
    fun snooze(states: Map<Need, NeedState>, need: Need, nowMs: Long): Map<Need, NeedState> =
        states[need]?.takeIf { it.stage.open }
            ?.let { states + (need to it.copy(stage = Stage.SNOOZED, stageAtMs = nowMs)) } ?: states

    /**
     * "Taking a break": the traveller intends to stop. When the stop happens
     * the cycle restarts on its own; if it hasn't after [ACK_GRACE_MIN], one
     * gentle reminder.
     */
    fun acknowledge(states: Map<Need, NeedState>, need: Need, nowMs: Long): Map<Need, NeedState> =
        states[need]?.takeIf { it.stage.open }
            ?.let { states + (need to it.copy(stage = Stage.ACKNOWLEDGED, stageAtMs = nowMs)) } ?: states

    /** The traveller explicitly said no to this suggestion: nothing more this cycle, nothing shared. */
    fun decline(states: Map<Need, NeedState>, need: Need, nowMs: Long): Map<Need, NeedState> =
        states[need]?.takeIf { it.stage.open }
            ?.let { states + (need to it.copy(stage = Stage.DECLINED, stageAtMs = nowMs)) } ?: states

    /** Compact storage: "water,NUDGED,<stageAt>,<anchor>,<cycle>,<reminders>;…". */
    fun encode(states: Map<Need, NeedState>): String =
        states.values.joinToString(";") {
            "${it.need.key},${it.stage.name},${it.stageAtMs},${it.anchorMs},${it.cycle},${it.reminders}"
        }

    fun decode(raw: String?): Map<Need, NeedState> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';').mapNotNull { part ->
            val f = part.split(',')
            if (f.size != 4 && f.size != 6) return@mapNotNull null
            val need = Need.fromKey(f[0]) ?: return@mapNotNull null
            // Builds before the lifecycle rework called INFORMED "ESCALATED".
            val stageName = if (f[1] == "ESCALATED") Stage.INFORMED.name else f[1]
            val stage = runCatching { Stage.valueOf(stageName) }.getOrNull() ?: return@mapNotNull null
            val at = f[2].toLongOrNull() ?: return@mapNotNull null
            val anchor = f[3].toLongOrNull() ?: return@mapNotNull null
            val cycle = f.getOrNull(4)?.toLongOrNull() ?: 0L
            val reminders = f.getOrNull(5)?.toIntOrNull()
                ?: if (stage == Stage.REMINDED || stage == Stage.INFORMED) 1 else 0
            need to NeedState(need, stage, at, anchor, cycle, reminders)
        }.toMap()
    }

    // ---- meal windows ------------------------------------------------------------

    enum class Meal(val word: String, val phrase: String, val fromHour: Int, val toHour: Int) {
        BREAKFAST("breakfast", "breakfast time", 7, 10),
        LUNCH("lunch", "lunchtime", 12, 15),
        DINNER("dinner", "dinner time", 19, 22)
    }

    fun mealWindow(hour: Int): Meal? = Meal.entries.firstOrNull { hour >= it.fromHour && hour < it.toHour }

    /** The nearest meal by the clock, for wording outside a window. */
    fun mealWord(hour: Int): String = when (hour) {
        in 4..10 -> "breakfast"
        in 11..16 -> "lunch"
        in 17..18 -> "a snack"
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

    // ---- internals -----------------------------------------------------------------

    private val Stage.open: Boolean
        get() = this == Stage.NUDGED || this == Stage.SNOOZED || this == Stage.ACKNOWLEDGED || this == Stage.REMINDED

    private fun watches(r: Rules, need: Need): Boolean = when (need) {
        Need.WATER -> r.waterMin != null
        Need.FOOD -> r.food != FoodAccess.NONE
        Need.BREAK -> r.breakMin != null
    }

    private fun anchorFor(need: Need, c: WellbeingContext): Long? = when (need) {
        Need.WATER -> maxOf(c.waterAtMs ?: c.journeyStartedAtMs, c.journeyStartedAtMs)
        Need.FOOD -> maxOf(c.foodAtMs ?: c.journeyStartedAtMs, c.journeyStartedAtMs)
        // Continuous movement only: a stop long enough to count resets it, a
        // traffic light does not. While still, a break is not measurable.
        Need.BREAK -> if (c.movement == Movement.MOVING) c.continuousSinceMs else null
    }

    /**
     * Food cycles per meal window, so a missed breakfast does not silence
     * lunch. Outside a window the current cycle carries on unchanged.
     */
    private fun cycleFor(need: Need, c: WellbeingContext, prior: NeedState?): Long {
        if (need != Need.FOOD) return 0
        val meal = mealWindow(c.localHour) ?: return prior?.cycle ?: 0
        val minutesIn = (c.localMinuteOfDay - meal.fromHour * 60).coerceAtLeast(0)
        return (c.nowMs - minutesIn * 60_000L) / 60_000 * 60_000
    }

    private fun held(r: Rules, c: WellbeingContext): Boolean {
        if (c.movement == Movement.HALTED || c.movement == Movement.PAUSED) return true
        val smallHours = c.localHour >= 23 || c.localHour < 5
        return smallHours && r.quietSmallHours
    }

    private fun remindAfter(r: Rules, need: Need): Int? = when {
        r.remindAfterMin == null -> null
        // Food on a train or flight is optional: suggested once, never chased.
        need == Need.FOOD && r.food == FoodAccess.IF_AVAILABLE -> null
        // A passenger's stretch is a nicety, not a safety matter.
        need == Need.BREAK && r.breakKind == BreakKind.STRETCH -> null
        else -> r.remindAfterMin
    }

    /** Followers hear about food only when the gap is genuinely long. */
    private fun informable(need: Need, gapMin: Long): Boolean =
        need != Need.FOOD || gapMin >= FOOD_LONG_GAP_MIN - 30

    /** Null when not eligible; otherwise the reasons it is. */
    private fun eligibility(r: Rules, need: Need, c: WellbeingContext, gapMin: Long): List<String>? {
        val base = listOf("Mode = ${r.mode}", "Role = ${r.role}")
        return when (need) {
            Need.WATER -> {
                val t = r.waterMin ?: return null
                if (gapMin < t) null
                else base + "No water recorded for ${duration(gapMin)} (threshold ${duration(t.toLong())})"
            }
            Need.FOOD -> {
                val meal = mealWindow(c.localHour)
                when {
                    meal != null && gapMin >= FOOD_WINDOW_GAP_MIN ->
                        base + "Local time is in the ${meal.word} window" +
                            "No food recorded for ${duration(gapMin)}" + "Food access: ${r.food}"
                    // Outside a meal window only when food is practical and the gap is long.
                    meal == null && r.food != FoodAccess.IF_AVAILABLE && gapMin >= FOOD_LONG_GAP_MIN ->
                        base + "No food recorded for ${duration(gapMin)}" + "Food access: ${r.food}"
                    else -> null
                }
            }
            Need.BREAK -> {
                val t = r.breakMin ?: return null
                if (gapMin < t) null
                else base + "Continuous movement ${duration(gapMin)} (threshold ${duration(t.toLong())})" +
                    "Vehicle is moving" + "No halt in progress"
            }
        }
    }

    private fun confidence(need: Need): Confidence = when (need) {
        // Movement comes from GPS; water and food only from what was logged.
        Need.BREAK -> Confidence.HIGH
        Need.WATER, Need.FOOD -> Confidence.MEDIUM
    }

    private fun suggestion(
        r: Rules, need: Need, c: WellbeingContext, gapMin: Long, reminder: Boolean, reasons: List<String>
    ): Decision {
        val gap = duration(gapMin)
        val driving = r.role == Role.DRIVER && r.breakKind != BreakKind.STRETCH
        val (title, body) = when (need) {
            Need.WATER -> if (!reminder) {
                "Water check" to (if (driving)
                    "You've been on the road for a while. If you have water with you, have some when it's safe to."
                else "You've been travelling for a while. If you have water with you, consider having some.")
            } else {
                "A reminder about water" to "It's been about $gap since you last had water. A few sips now is a good idea."
            }
            Need.FOOD -> foodCopy(r, c, gap, reminder)
            Need.BREAK -> breakCopy(r, gap, reminder)
        }
        return Decision(
            need, if (reminder) DecisionType.REMINDER else DecisionType.NUDGE,
            title, body, reasons, confidence(need), gapMin
        )
    }

    private fun foodCopy(r: Rules, c: WellbeingContext, gap: String, reminder: Boolean): Pair<String, String> {
        val meal = mealWindow(c.localHour)
        val opener = meal?.let { "It's around ${it.phrase}." } ?: "It's been about $gap since you last ate."
        if (reminder) {
            return "A reminder about food" to when (r.food) {
                FoodAccess.AT_A_STOP -> "It's been about $gap since you last ate. At the next stop, consider having something."
                else -> "It's been about $gap since you last ate. If you can stop for food soon, it's worth planning."
            }
        }
        val body = when (r.food) {
            FoodAccess.PLAN_A_STOP -> "$opener If you're able to stop for food, this would be a good time to plan it."
            FoodAccess.AT_A_STOP -> "$opener If there's a stop for food, consider having something."
            FoodAccess.IF_AVAILABLE ->
                if (r.mode == "FLIGHT") "$opener If a meal is available, consider having it."
                else "$opener If food is available on your journey, consider having something."
            FoodAccess.NONE -> opener
        }
        return (meal?.let { "${it.word.replaceFirstChar(Char::uppercase)} time" } ?: "Time to eat?") to body
    }

    private fun breakCopy(r: Rules, gap: String, reminder: Boolean): Pair<String, String> {
        val verb = if (r.breakKind == BreakKind.RIDING) "riding" else "driving"
        return when (r.breakKind) {
            BreakKind.DRIVING, BreakKind.RIDING -> if (!reminder) {
                "Time for a break?" to "You've been $verb for about $gap without a recorded break. Consider stopping when convenient."
            } else {
                "A reminder about a break" to "About $gap of $verb now without a recorded break. When you see a safe place, a short break is a good idea."
            }
            BreakKind.STRETCH -> "Time to stretch?" to when (r.mode) {
                "BUS" -> "You've been travelling for about $gap. At the next stop, it's a good chance to get down and stretch."
                "FLIGHT" -> "You've been flying for about $gap. When it's permitted, a short stretch or walk down the aisle helps."
                else -> "You've been travelling for about $gap. When it's convenient, a short walk or stretch helps."
            }
        }
    }

    /**
     * The follower update: factual, neutral, the least intrusive wording that
     * is still useful. Never "ignored", never "escalated".
     */
    private fun inform(r: Rules, need: Need, c: WellbeingContext, gapMin: Long, reminders: Int): Decision {
        val who = c.travellerName.ifBlank { "The traveller" }
        val gap = duration(gapMin)
        val text = when (need) {
            Need.BREAK -> {
                val verb = if (r.breakKind == BreakKind.RIDING) "riding" else "driving"
                "$who has been $verb for about $gap. Koode suggested a break, but no break has been recorded yet."
            }
            Need.WATER -> "$who has been travelling for a while and no water has been recorded for about $gap. Koode suggested some along the way."
            Need.FOOD -> "$who has been travelling for a while and no meal has been recorded for about $gap. Koode suggested food along the way."
        }
        val reasons = listOf(
            "Mode = ${r.mode}", "Role = ${r.role}",
            "Suggested once and reminded $reminders time(s)",
            "Still unresolved after ${duration(gapMin)}"
        )
        return Decision(need, DecisionType.INFORM_FOLLOWERS, "", text, reasons, confidence(need), gapMin)
    }
}
