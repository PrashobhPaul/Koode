package com.trippulse.app.domain

import com.trippulse.app.core.Geo

/**
 * Common sense about a journey's stages, applied to what was recorded.
 *
 * Older builds (and a hurried thumb) leave stages that read wrong: a cab
 * that "went to" the destination though the traveller left it at the metro,
 * the same change tapped three times in a minute. Nothing here invents a
 * place or a time; it only reads the stages against each other.
 */
object StageRepair {

    /** A stage, as far as the repair cares. */
    data class Stage(
        val index: Int,
        val mode: String,
        val fromName: String, val fromLat: Double, val fromLng: Double,
        val toName: String, val toLat: Double, val toLng: Double,
        val startedAtMs: Long?, val completedAtMs: Long?
    )

    /** Ends further apart than this are different places. */
    const val SAME_PLACE_M = 150.0

    /** A stage this short followed by the same mode was a repeated tap, not a stage. */
    const val REPEAT_TAP_MS = 3 * 60_000L

    /**
     * A finished stage ended where the next one began: its end is the next
     * stage's start, whatever destination it was heading for at the time.
     * Returns only the stages whose end changed.
     */
    fun endsWhereNextBegan(stages: List<Stage>): List<Stage> {
        val byIndex = stages.associateBy { it.index }
        return stages.mapNotNull { s ->
            if (s.completedAtMs == null) return@mapNotNull null
            val next = byIndex[s.index + 1]?.takeIf { it.startedAtMs != null } ?: return@mapNotNull null
            val apart = Geo.haversineM(GeoPoint(s.toLat, s.toLng), GeoPoint(next.fromLat, next.fromLng))
            if (apart <= SAME_PLACE_M) return@mapNotNull null
            s.copy(toName = next.fromName, toLat = next.fromLat, toLng = next.fromLng)
        }
    }

    /**
     * Stages as a person would list them: a stage of a few moments followed by
     * one in the same mode is the same stage (a change tapped twice), so the
     * two read as one, from the first's start to the last's end.
     */
    fun <T> folded(
        items: List<T>,
        mode: (T) -> String,
        startedAt: (T) -> Long?,
        completedAt: (T) -> Long?,
        merge: (first: T, later: T) -> T
    ): List<T> {
        val out = ArrayList<T>()
        for (item in items) {
            val last = out.lastOrNull()
            val lastStart = last?.let(startedAt)
            val lastEnd = last?.let(completedAt)
            if (last != null && mode(last).equals(mode(item), ignoreCase = true) &&
                lastStart != null && lastEnd != null && lastEnd - lastStart < REPEAT_TAP_MS) {
                out[out.lastIndex] = merge(last, item)
            } else out += item
        }
        return out
    }

    /**
     * A walk counts as a way of travelling from this far. Shorter, between
     * two rides, it is the change between them: the platform, the car park,
     * the minutes waiting for the cab. Told as the change, not as a stage.
     */
    const val WALK_COUNTS_FROM_M = 1000.0

    /**
     * Whether a stage counts as a way the journey was made. Everything ridden
     * does. Walking does from [WALK_COUNTS_FROM_M]; a walk whose distance is
     * not known is kept, because nothing is dropped on a guess.
     */
    fun countsAsStage(mode: String, distanceM: Double?): Boolean =
        TransportCatalog.profile(mode).key != TransportCatalog.WALK.key || distanceM == null || distanceM >= WALK_COUNTS_FROM_M

    /**
     * The stages that count (see [countsAsStage]), the short walks between
     * rides left out. A journey made only of short walks keeps them: they are
     * all it was.
     */
    fun <T> ridden(items: List<T>, mode: (T) -> String, distanceM: (T) -> Double?): List<T> {
        val kept = items.filter { countsAsStage(mode(it), distanceM(it)) }
        return kept.ifEmpty { items }
    }

    /**
     * The stages as they were within the journey: none began after it
     * ended, and none ran past its end. "Got off" at the destination is
     * often tapped a while after arriving (the arrival itself is noticed
     * from the fixes); the stage ended when the journey did, and a stage
     * begun after that was never part of it. On a journey that has ended
     * ([completed]), a stage still open ended with it too.
     */
    fun <T> withinJourney(
        items: List<T>, endMs: Long, startedAt: (T) -> Long?, completedAt: (T) -> Long?,
        completed: Boolean = true, endedAt: (T, Long) -> T
    ): List<T> = items.filter { (startedAt(it) ?: Long.MIN_VALUE) < endMs }.map { item ->
        val c = completedAt(item)
        when {
            c != null && c > endMs -> endedAt(item, endMs)
            c == null && completed && startedAt(item) != null -> endedAt(item, endMs)
            else -> item
        }
    }

    /**
     * The stages of a journey as a traveller tells them: [ridden], less a
     * walk from a place to the same place ([goesNowhere]: the last steps of
     * arriving), and a ride after a short walk between rides starting where
     * the ride before it ended. Off the metro at Durgam Cheruvu, a few
     * hundred metres to the bike taxi: the bike taxi is from Durgam Cheruvu
     * Metro, not the street corner it was waiting at.
     */
    fun <T> told(
        items: List<T>, mode: (T) -> String, distanceM: (T) -> Double?,
        fromName: (T) -> String, toName: (T) -> String, startingAt: (T, String) -> T
    ): List<T> {
        val out = ArrayList<T>()
        var walkedFrom = false
        for (it in items) {
            when {
                goesNowhere(mode(it), fromName(it), toName(it)) -> Unit
                !countsAsStage(mode(it), distanceM(it)) -> if (out.isNotEmpty()) walkedFrom = true
                else -> {
                    val prev = out.lastOrNull()
                    val to = prev?.let(toName)
                    out += if (walkedFrom && to != null && to.isNotBlank() && to != EN_ROUTE) startingAt(it, to) else it
                    walkedFrom = false
                }
            }
        }
        return out.ifEmpty { ridden(items, mode, distanceM) }
    }

    /**
     * How far the record moved between [from] and [to], along the fixes.
     * Null when fewer than two fixes fall inside: the distance is then not
     * known, and a caller must not treat it as zero.
     */
    fun <P> pathLengthM(points: List<P>, tMs: (P) -> Long, lat: (P) -> Double, lng: (P) -> Double, from: Long, to: Long): Double? {
        val inside = points.filter { tMs(it) in from..to }
        if (inside.size < 2) return null
        var d = 0.0
        for (i in 1 until inside.size) {
            d += Geo.haversineM(GeoPoint(lat(inside[i - 1]), lng(inside[i - 1])), GeoPoint(lat(inside[i]), lng(inside[i])))
        }
        return d
    }

    /**
     * A walk from a place to the same place ("Qualizeal Office → Qualizeal
     * Office") is the last steps of arriving, not a way the journey was made.
     */
    fun goesNowhere(mode: String, fromName: String, toName: String): Boolean =
        TransportCatalog.profile(mode).key == TransportCatalog.WALK.key &&
            fromName.isNotBlank() && fromName != EN_ROUTE && fromName.trim().equals(toName.trim(), ignoreCase = true)

    /** What a switch point away from any saved place is called until it is named. */
    const val EN_ROUTE = "En route"
}
