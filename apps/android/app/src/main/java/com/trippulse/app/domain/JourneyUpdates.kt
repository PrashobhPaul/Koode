package com.trippulse.app.domain

/**
 * The periodic update to the traveller's followers: one short line answering
 * what they actually wonder — how is it going, how far is left, when will
 * they arrive, are they looking after themselves.
 *
 *   "Driving · 1 h 40 m on the road · 85 km to go · ETA 6:40 pm · water 40 m ago"
 *
 * It is considered at most once an [INTERVAL_MIN], and **sent only when
 * something meaningful changed** since the last one: real progress, a
 * noticeable ETA move, moving ↔ stopped, or water/food/a break recorded. An
 * hour passing is not news. Pure and unit-tested.
 */
object JourneyUpdates {

    const val INTERVAL_MIN = 60L
    /** Progress worth mentioning since the last update. */
    const val MIN_PROGRESS_M = 10_000.0
    /** ETA movement worth mentioning (smaller than a "significant" ETA change event). */
    const val MIN_ETA_MOVE_MIN = 15L

    /** Whether the next update may be considered. The first comes an hour in. */
    fun due(lastUpdateAtMs: Long?, startedAtMs: Long, nowMs: Long): Boolean =
        nowMs - (lastUpdateAtMs ?: startedAtMs) >= INTERVAL_MIN * 60_000

    /** What the last update said, kept to compare the next one against. */
    data class Snapshot(
        val coveredM: Double,
        val etaMs: Long?,
        val moving: Boolean,
        val waterAtMs: Long?,
        val foodAtMs: Long?,
        val breakAtMs: Long?
    ) {
        fun encode(): String = listOf(coveredM, etaMs ?: "", moving, waterAtMs ?: "", foodAtMs ?: "", breakAtMs ?: "")
            .joinToString(",")

        companion object {
            fun decode(raw: String?): Snapshot? {
                val f = raw?.split(',') ?: return null
                if (f.size != 6) return null
                return Snapshot(
                    coveredM = f[0].toDoubleOrNull() ?: return null,
                    etaMs = f[1].toLongOrNull(),
                    moving = f[2].toBoolean(),
                    waterAtMs = f[3].toLongOrNull(),
                    foodAtMs = f[4].toLongOrNull(),
                    breakAtMs = f[5].toLongOrNull()
                )
            }
        }
    }

    /**
     * Why an update is worth sending now, or empty if nothing meaningful
     * changed since [last] (null: this would be the first).
     */
    fun changes(last: Snapshot?, now: Snapshot): List<String> {
        if (last == null) return listOf("first update")
        return buildList {
            if (now.coveredM - last.coveredM >= MIN_PROGRESS_M) add("progress")
            if (now.moving != last.moving) add(if (now.moving) "moving again" else "stopped")
            val a = last.etaMs; val b = now.etaMs
            if (a != null && b != null && kotlin.math.abs(b - a) / 60_000 >= MIN_ETA_MOVE_MIN) add("eta moved")
            if (now.waterAtMs != null && now.waterAtMs != last.waterAtMs) add("water")
            if (now.foodAtMs != null && now.foodAtMs != last.foodAtMs) add("food")
            if (now.breakAtMs != null && now.breakAtMs != last.breakAtMs) add("break")
        }
    }

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
        val foodAtMs: Long?,
        /** When the last meaningful break ended (drivers only). */
        val breakAtMs: Long? = null
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
        f.etaClock?.let { add("ETA $it") }
        since("water", f.waterAtMs, f.startedAtMs, f.nowMs)?.let(::add)
        since("ate", f.foodAtMs, f.startedAtMs, f.nowMs)?.let(::add)
        if (f.driving || f.riding) since("last break", f.breakAtMs, f.startedAtMs, f.nowMs)?.let(::add)
    }.joinToString(" · ")

    /** Only facts that exist: nothing is said about what was never recorded. */
    private fun since(what: String, atMs: Long?, startedAtMs: Long, nowMs: Long): String? =
        atMs?.takeIf { it >= startedAtMs }?.let { "$what ${WellbeingCoach.duration((nowMs - it) / 60_000)} ago" }
}
