package com.trippulse.app.domain

/**
 * The journey plan, versioned.
 *
 * A destination, travel mode, traveller role or planned halt change creates a
 * new revision instead of overwriting the old one, so the journey keeps its
 * history ("v1: Hyderabad → Thrissur, overnight Salem; v2: → Kochi") and
 * every material change becomes a first-class event the traveller's chosen
 * people hear about.
 *
 * Koode never judges the route: there is no deviation, off-track or
 * back-on-track here — only what the traveller told us they are doing now.
 */
data class JourneyPlan(
    val version: Int,
    val atMs: Long,
    val destination: String,
    val mode: String,
    val role: String,
    /** Where the traveller plans to halt, if they have said. */
    val plannedHalt: String?,
    /** The event type that created this revision (JOURNEY_STARTED for v1). */
    val reason: String
)

object JourneyPlans {

    const val INITIAL = "JOURNEY_STARTED"

    fun initial(atMs: Long, destination: String, mode: String, role: String): JourneyPlan =
        JourneyPlan(1, atMs, destination, mode, role, null, INITIAL)

    /** A revision and the one event that announces it. */
    data class Revision(val plan: JourneyPlan, val eventType: String, val text: String, val payload: Map<String, Any?>)

    /** A change to the planned halt; [to] null removes it. */
    data class HaltChange(val to: String?)

    /**
     * The revision for a change, or null if nothing material changed. Pass
     * only what changed.
     */
    fun revise(
        current: JourneyPlan,
        atMs: Long,
        destination: String? = null,
        mode: String? = null,
        role: String? = null,
        halt: HaltChange? = null,
        /** The traveller's name, so followers read who changed it; blank for a neutral sentence. */
        who: String = ""
    ): Revision? {
        val newDest = destination?.takeIf { it.isNotBlank() && it != current.destination }
        val newMode = mode?.takeIf { it != current.mode }
        val newRole = role?.takeIf { it != current.role }
        val wantedHalt = halt?.to?.trim()?.ifBlank { null }
        val haltChanged = halt != null && wantedHalt != current.plannedHalt
        val newHalt = if (haltChanged) wantedHalt else current.plannedHalt

        // A new mode brings its own default role, so the role only counts as
        // a separate change when the mode stays the same.
        val changes = listOfNotNull(
            newDest?.let { "destination" }, newMode?.let { "mode" },
            newRole?.takeIf { newMode == null }?.let { "role" }, if (haltChanged) "halt" else null
        )
        if (changes.isEmpty()) return null

        val type = when {
            changes.size > 1 -> EventTypes.JOURNEY_PLAN_REVISED
            newDest != null -> EventTypes.DESTINATION_CHANGED
            newMode != null || newRole != null -> EventTypes.TRAVEL_MODE_CHANGED
            current.plannedHalt == null -> EventTypes.PLANNED_HALT_CREATED
            newHalt == null -> EventTypes.PLANNED_HALT_CANCELLED
            else -> EventTypes.PLANNED_HALT_CHANGED
        }
        val plan = current.copy(
            version = current.version + 1, atMs = atMs,
            destination = newDest ?: current.destination,
            mode = newMode ?: current.mode,
            role = newRole ?: current.role,
            plannedHalt = newHalt,
            reason = type
        )
        val text = describe(current, plan, who)
        val payload = buildMap<String, Any?> {
            put("planVersion", plan.version)
            put("text", text)
            newDest?.let { put("fromDestination", current.destination); put("toDestination", it) }
            newMode?.let { put("fromMode", current.mode); put("toMode", it) }
            newRole?.let { put("fromRole", current.role); put("toRole", it) }
            if (haltChanged) { put("fromHalt", current.plannedHalt); put("toHalt", newHalt) }
            put("source", "USER_PLAN_CHANGE")
        }
        return Revision(plan, type, text, payload)
    }

    /**
     * Plain sentences, one per change. Neutral without a name ("Journey
     * destination changed from Thrissur to Kochi."), or naming who changed it
     * ("Prashobh changed the journey destination from Thrissur to Kochi.").
     * A planned halt is an intention, never presented as a halt that happened.
     */
    fun describe(from: JourneyPlan, to: JourneyPlan, who: String = ""): String {
        val name = who.trim()
        val named = name.isNotEmpty()
        return buildList {
            if (to.destination != from.destination) add(
                if (named) "$name changed the journey destination from ${from.destination} to ${to.destination}."
                else "Journey destination changed from ${from.destination} to ${to.destination}."
            )
            if (to.mode != from.mode) add(
                if (named) "$name changed travel mode from ${modeWord(from.mode)} to ${modeWord(to.mode)}."
                else "Travel mode changed from ${modeWord(from.mode)} to ${modeWord(to.mode)}."
            )
            else if (to.role != from.role) {
                val driving = to.role == WellbeingCoach.Role.DRIVER.name
                add(
                    when {
                        named && driving -> "$name is now driving the ${modeWord(to.mode)}."
                        named -> "$name is now travelling as a passenger in the ${modeWord(to.mode)}."
                        driving -> "Now driving the ${modeWord(to.mode)}."
                        else -> "Now travelling as a passenger in the ${modeWord(to.mode)}."
                    }
                )
            }
            if (to.plannedHalt != from.plannedHalt) add(
                when {
                    to.plannedHalt == null ->
                        if (named) "$name is no longer planning to halt at ${from.plannedHalt}."
                        else "The planned halt at ${from.plannedHalt} is no longer planned."
                    from.plannedHalt == null ->
                        if (named) "$name plans to halt at ${to.plannedHalt}." else "Planning to halt at ${to.plannedHalt}."
                    else ->
                        if (named) "$name changed the planned halt from ${from.plannedHalt} to ${to.plannedHalt}."
                        else "Planned halt changed from ${from.plannedHalt} to ${to.plannedHalt}."
                }
            )
        }.joinToString(" ")
    }

    fun modeWord(mode: String): String = when (mode.uppercase()) {
        "CAB" -> "cab"
        "SHIP" -> "ship"
        else -> TransportCatalog.label(mode).substringBefore(" ").lowercase()
    }

    // ---- storage: unit/record separators, so place names need no escaping ----

    private const val FS = '\u001F'
    private const val RS = '\u001E'

    fun encode(history: List<JourneyPlan>): String = history.joinToString(RS.toString()) {
        listOf(it.version, it.atMs, it.destination, it.mode, it.role, it.plannedHalt.orEmpty(), it.reason)
            .joinToString(FS.toString())
    }

    fun decode(raw: String?): List<JourneyPlan> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(RS).mapNotNull { rec ->
            val f = rec.split(FS)
            if (f.size != 7) return@mapNotNull null
            JourneyPlan(
                version = f[0].toIntOrNull() ?: return@mapNotNull null,
                atMs = f[1].toLongOrNull() ?: return@mapNotNull null,
                destination = f[2], mode = f[3], role = f[4],
                plannedHalt = f[5].ifEmpty { null }, reason = f[6]
            )
        }.sortedBy { it.version }
    }
}

/**
 * Only materially significant ETA changes reach followers: at least half an
 * hour, and at least a fifth of the time that was left — so a 20-minute slip
 * on a 10-hour drive stays quiet, and so does traffic near the destination.
 */
object EtaShift {
    const val MIN_SHIFT_MIN = 30L
    const val MIN_FRACTION = 0.2

    fun significant(baselineEtaMs: Long?, newEtaMs: Long?, nowMs: Long): Boolean {
        if (baselineEtaMs == null || newEtaMs == null) return false
        val shiftMin = kotlin.math.abs(newEtaMs - baselineEtaMs) / 60_000
        val remainingMin = ((baselineEtaMs - nowMs) / 60_000).coerceAtLeast(0)
        return shiftMin >= MIN_SHIFT_MIN && shiftMin >= remainingMin * MIN_FRACTION
    }
}

/**
 * Halts: a long stop the traveller has *told* Koode about. Koode never
 * guesses why someone stopped; it only records what they confirmed.
 */
object Halts {

    enum class Type(val label: String, val emoji: String) {
        ROOM("Hotel / room", "🏨"),
        FRIEND_FAMILY("Friend / family", "🏠"),
        REST_STOP("Rest stop", "🅿️"),
        OTHER("Other", "📍");

        companion object {
            /** Accepts the pre-rework overnight answers too. */
            fun from(key: String?): Type = when (key?.uppercase()) {
                "ROOM", "HOTEL" -> ROOM
                "FRIEND_FAMILY", "FAMILY", "HOME" -> FRIEND_FAMILY
                "REST_STOP", "VEHICLE" -> REST_STOP
                else -> OTHER
            }
        }
    }

    /** How long the traveller said the halt would be; null when they didn't say. */
    enum class Duration(val label: String, val minutes: Int) {
        HOUR("1 hour", 60), FEW_HOURS("A few hours", 180), OVERNIGHT("The night", 8 * 60);

        companion object {
            fun fromMinutes(m: Int?): Duration? = entries.firstOrNull { it.minutes == m }
        }
    }

    /** A halt the traveller said is for the night, or one that starts late in the evening. */
    fun isOvernight(localHour: Int, expectedMinutes: Int?): Boolean =
        (expectedMinutes != null && expectedMinutes >= 6 * 60) || localHour >= 19 || localHour < 4

    /** "Prashobh has taken a room in Salem and is halting here for the night." Never "stuck". */
    fun confirmedText(who: String, type: Type, place: String?, overnight: Boolean): String {
        val name = who.ifBlank { "The traveller" }
        val at = place?.let { " in $it" }.orEmpty()
        val tail = if (overnight) " and is halting here for the night." else " for a halt."
        return when (type) {
            Type.ROOM -> "$name has taken a room$at$tail"
            Type.FRIEND_FAMILY -> "$name is halting with friends or family$at${if (overnight) " for the night." else "."}"
            Type.REST_STOP -> "$name is taking a halt at a rest stop$at${if (overnight) " for the night." else "."}"
            Type.OTHER -> "$name is taking a halt$at${if (overnight) " for the night." else "."}"
        }
    }

    /**
     * "Prashobh has resumed the journey from Salem." when they said so;
     * "Prashobh is on the move again from Salem." when only GPS saw it.
     */
    fun resumedText(who: String, place: String?, confirmed: Boolean): String {
        val name = who.ifBlank { "The traveller" }
        val from = place?.let { " from $it" }.orEmpty()
        return if (confirmed) "$name has resumed the journey$from." else "$name is on the move again$from."
    }

    /** The confirm button reads what is being confirmed. */
    fun confirmLabel(duration: Duration?): String =
        if (duration == Duration.OVERNIGHT) "Confirm overnight halt" else "Confirm halt"

    fun cancelledText(who: String): String = "${who.ifBlank { "The traveller" }} is continuing the journey — the halt was cancelled."
}

/**
 * Long-haul planning: on a long drive whose arrival lands late at night,
 * suggest — once — planning an overnight halt. A suggestion, never a command,
 * and never on public transport where the traveller cannot choose to halt.
 */
object HaltPlanning {
    const val MIN_REMAINING_MIN = 180L

    fun shouldSuggest(
        role: WellbeingCoach.Role, modeKey: String, plannedHalt: String?, alreadySuggested: Boolean,
        halted: Boolean, nowMs: Long, etaLikelyMs: Long?, etaLocalHour: Int?
    ): Boolean {
        if (alreadySuggested || halted || plannedHalt != null) return false
        if (WellbeingCoach.rulesFor(modeKey, role)?.suggestsHaltPlanning != true) return false
        val eta = etaLikelyMs ?: return false
        val remainingMin = (eta - nowMs) / 60_000
        if (remainingMin < MIN_REMAINING_MIN) return false
        val arrivesLate = etaLocalHour != null && (etaLocalHour >= 23 || etaLocalHour < 5)
        return arrivesLate || remainingMin >= 10 * 60
    }

    const val TEXT = "This is a long journey. Consider planning an overnight halt before continuing."
}
