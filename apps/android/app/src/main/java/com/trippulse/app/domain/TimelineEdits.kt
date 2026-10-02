package com.trippulse.app.domain

/**
 * When an entry *happened* versus when it was *logged*, and the traveller's
 * corrections to either.
 *
 * Every event's own time is the moment it was written. That has to stay so:
 * followers fetch "everything after the last event I saw", and an event
 * stamped into the past would never reach them. So a break logged at 2 AM
 * about a dinner at 9:30 PM is written at 2 AM and *says* 9:30 PM in its
 * payload, and every timeline shows the time it says.
 *
 * Corrections follow the same rule. The log is append-only, so a change is a
 * new event: a break is revised by another BREAK_CHECKPOINT with the same
 * breakId (see [BreakTimeline]); anything else by a [EventTypes.TIMELINE_EDIT]
 * naming the entry it corrects. Timelines apply them here, once, for the app,
 * the follower view and the PDF alike; the web viewer mirrors this in app.js.
 */
object TimelineEdits {

    /** The time a timeline shows for an entry: when it happened, if the entry says. */
    fun shownTime(type: String, payload: Map<String, Any?>, eventTimeMs: Long): Long {
        (payload["atMs"] as? Number)?.toLong()?.let { return it }
        if (type == EventTypes.BREAK_CHECKPOINT) (payload["startMs"] as? Number)?.toLong()?.let { return it }
        if (type == EventTypes.HALT_CONFIRMED) (payload["sinceMs"] as? Number)?.toLong()?.let { return it }
        return eventTimeMs
    }

    /** What a correction says about one entry. */
    data class Edit(val atMs: Long?, val removed: Boolean)

    /** The latest correction per target entry, by the correction's own time. */
    fun <T> collect(items: List<T>, type: (T) -> String, time: (T) -> Long, payload: (T) -> Map<String, Any?>): Map<String, Edit> {
        val when_ = HashMap<String, Long>()
        val edits = HashMap<String, Edit>()
        items.forEach { e ->
            if (type(e) != EventTypes.TIMELINE_EDIT) return@forEach
            val p = payload(e)
            val target = p["targetEventId"] as? String ?: return@forEach
            val t = time(e)
            if (t >= (when_[target] ?: Long.MIN_VALUE)) {
                when_[target] = t
                edits[target] = Edit((p["atMs"] as? Number)?.toLong(), p["removed"] == true)
            }
        }
        return edits
    }

    /**
     * Applies corrections: removed entries and the corrections themselves
     * disappear; a re-timed entry's payload carries the new time as `atMs` so
     * [shownTime] shows it. Entries without an id are left as they are.
     */
    fun <T> apply(
        items: List<T>, id: (T) -> String?, type: (T) -> String, time: (T) -> Long,
        payload: (T) -> Map<String, Any?>, withPayload: (T, Map<String, Any?>) -> T
    ): List<T> {
        val edits = collect(items, type, time, payload)
        if (edits.isEmpty() && items.none { type(it) == EventTypes.TIMELINE_EDIT }) return items
        return items.mapNotNull { e ->
            if (type(e) == EventTypes.TIMELINE_EDIT) return@mapNotNull null
            val edit = id(e)?.let { edits[it] } ?: return@mapNotNull e
            when {
                edit.removed -> null
                edit.atMs != null -> withPayload(e, payload(e) + mapOf("atMs" to edit.atMs))
                else -> e
            }
        }
    }
}
