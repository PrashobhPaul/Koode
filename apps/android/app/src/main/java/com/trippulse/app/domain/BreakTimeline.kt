package com.trippulse.app.domain

/**
 * How breaks read on every timeline (app, follower view, PDF).
 *
 * A break is logged once and then *updated* as items join it and when the
 * vehicle drives off (the event log is append-only, so an update is a new
 * BREAK_CHECKPOINT carrying the same `breakId`). Timelines show the latest
 * version of each break only, with its items folded into the one line, so a
 * stop where someone had water, a snack and a toilet break reads as one entry
 * rather than six.
 */
object BreakTimeline {

    private val FOLDED_ITEMS = setOf(
        EventTypes.WATER_REPORTED, EventTypes.FOOD_REPORTED, EventTypes.TEA_COFFEE_REPORTED,
        EventTypes.SNACK_REPORTED, EventTypes.TOILET_REPORTED, EventTypes.REST_REPORTED,
        EventTypes.FUEL_STOP, EventTypes.CHARGE_STOP
    )

    /** Latest BREAK_CHECKPOINT per breakId; everything else untouched. For counting. */
    fun latestBreaks(events: List<TripEvent>): List<TripEvent> =
        keepLatest(events, { it.type }, { it.eventTimeMs }, { it.payload }, fold = false)

    /** Timeline view: latest version of each break, items folded in, skipped prompts hidden. */
    fun <T> forTimeline(items: List<T>, type: (T) -> String, time: (T) -> Long, payload: (T) -> Map<String, Any?>): List<T> =
        keepLatest(items, type, time, payload, fold = true)

    private fun <T> keepLatest(
        items: List<T>, type: (T) -> String, time: (T) -> Long, payload: (T) -> Map<String, Any?>, fold: Boolean
    ): List<T> {
        val latest = HashMap<String, Long>()
        items.forEach { e ->
            if (type(e) == EventTypes.BREAK_CHECKPOINT) {
                (payload(e)["breakId"] as? String)?.let { id -> latest[id] = maxOf(latest[id] ?: Long.MIN_VALUE, time(e)) }
            }
        }
        val kept = HashSet<String>()
        return items.filter { e ->
            val id = payload(e)["breakId"] as? String
            when {
                type(e) == EventTypes.BREAK_CHECKPOINT && id != null -> time(e) == latest[id] && kept.add(id)
                fold && id != null && type(e) in FOLDED_ITEMS -> false
                fold && type(e) == EventTypes.BREAK_CHECKPOINT_SKIPPED -> false
                else -> true
            }
        }
    }

    /** "Break near Kurnool · 18 min · 💧 🍪 🚻" — only what is known is shown. */
    fun describe(payload: Map<String, Any?>): String {
        val place = (payload["place"] as? String)?.takeIf { it.isNotBlank() }
        val seconds = (payload["durationS"] as? Number)?.toLong()
        val open = payload["open"] == true
        val items = buildList {
            if (payload["water"] == true) add("💧")
            if (payload["food"] == true) add(Nourishment.fromKey(payload["meal"] as? String)?.emoji ?: "🍛")
            if (payload["tea"] == true) add("☕")
            if (payload["snack"] == true) add("🍪")
            if (payload["toilet"] == true) add("🚻")
            if (payload["rest"] == true) add("😴")
            if (payload["fuel"] == true) add("⛽")
            if (payload["charge"] == true) add("🔌")
        }
        return buildList {
            add(if (place != null) "Break near $place" else "Break")
            when {
                open -> add("ongoing")
                seconds != null && seconds >= 60 -> add("${seconds / 60} min")
            }
            if (items.isNotEmpty()) add(items.joinToString(" "))
        }.joinToString(" · ")
    }
}
