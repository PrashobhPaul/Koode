package com.trippulse.app.domain

/**
 * The circle's regular update: every hour of a live journey, one short line
 * that answers what the people watching actually wonder — are they moving,
 * how far is left, when will they arrive, and are they looking after
 * themselves.
 *
 *   "Driving · 1 h 40 m on the road · 85 km to go · arriving about 6:40 pm ·
 *    water 40 m ago · ate 2 h ago"
 *
 * It is written on the traveller's phone (which knows all of this) as a
 * journey event, so it reaches every follower — by server push even when
 * their Koode is closed. Pure and unit-tested.
 */
object JourneyUpdates {

    const val INTERVAL_MIN = 60L

    /** Whether the next regular update is due. The first comes an hour in. */
    fun due(lastUpdateAtMs: Long?, startedAtMs: Long, nowMs: Long): Boolean =
        nowMs - (lastUpdateAtMs ?: startedAtMs) >= INTERVAL_MIN * 60_000

    data class Facts(
        val nowMs: Long,
        val startedAtMs: Long,
        val moving: Boolean,
        val driving: Boolean,
        val riding: Boolean,
        /** Already formatted in the traveller's units, e.g. "85 km". */
        val distanceLeft: String?,
        /** Already formatted, e.g. "6:40 pm". */
        val etaClock: String?,
        val waterAtMs: Long?,
        val foodAtMs: Long?
    )

    fun text(f: Facts): String = buildList {
        add(
            when {
                !f.moving -> "Stopped"
                f.riding -> "Riding"
                f.driving -> "Driving"
                else -> "On the way"
            }
        )
        add("${WellbeingCoach.duration((f.nowMs - f.startedAtMs) / 60_000)} on the road")
        f.distanceLeft?.let { add("$it to go") }
        f.etaClock?.let { add("arriving about $it") }
        add(since("water", f.waterAtMs, f.startedAtMs, f.nowMs))
        add(since("ate", f.foodAtMs, f.startedAtMs, f.nowMs))
    }.joinToString(" · ")

    private fun since(what: String, atMs: Long?, startedAtMs: Long, nowMs: Long): String =
        if (atMs == null || atMs < startedAtMs) {
            if (what == "water") "no water logged yet" else "no meal logged yet"
        } else {
            "$what ${WellbeingCoach.duration((nowMs - atMs) / 60_000)} ago"
        }
}
