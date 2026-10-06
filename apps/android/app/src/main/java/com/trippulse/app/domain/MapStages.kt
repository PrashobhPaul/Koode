package com.trippulse.app.domain

/**
 * How a multi-mode journey is drawn on the map: which mode carried the
 * traveller at each moment, so a replay shows the cab, then the walker, then
 * the metro, each where it was, and the walked stretches of the trail read
 * differently from the ridden ones.
 */
object MapStages {

    /** From [fromMs] on, the traveller went by [mode] (until the next stage). */
    data class Stage(val fromMs: Long, val mode: String)

    /** A run of consecutive trail points made in one mode: indices [from]..[to], inclusive. */
    data class Run(val from: Int, val to: Int, val mode: String)

    /**
     * Stages from a journey's legs: each started leg from the moment it
     * started. A leg that has not started yet is not on the trail.
     */
    fun of(legs: List<Pair<Long?, String>>): List<Stage> =
        legs.mapNotNull { (startedAtMs, mode) -> startedAtMs?.let { Stage(it, mode) } }.sortedBy { it.fromMs }

    /**
     * The mode at [tMs]: the latest stage begun by then. Before the first
     * stage it is the first stage's mode (the start of the record precedes
     * the leg's own start by moments); with no stages, [fallback].
     */
    fun modeAt(stages: List<Stage>, tMs: Long, fallback: String?): String? {
        if (stages.isEmpty()) return fallback
        return (stages.lastOrNull { it.fromMs <= tMs } ?: stages.first()).mode
    }

    /**
     * The trail cut into runs by mode. Neighbouring runs share their boundary
     * point, so the line stays unbroken where the traveller changed mode.
     * Without matching times, the whole trail is one run in [fallback].
     */
    fun runs(timesMs: List<Long>, stages: List<Stage>, fallback: String?, size: Int = timesMs.size): List<Run> {
        if (size < 2) return emptyList()
        val whole = listOf(Run(0, size - 1, fallback ?: ""))
        if (stages.size < 2 || timesMs.size < size) return whole
        val out = ArrayList<Run>()
        var start = 0
        var mode = modeAt(stages, timesMs[0], fallback)
        for (i in 1 until size) {
            val m = modeAt(stages, timesMs[i], fallback)
            if (m != mode) {
                out += Run(start, i, mode ?: "")
                start = i
                mode = m
            }
        }
        if (start < size - 1 || out.isEmpty()) out += Run(start, size - 1, mode ?: "")
        return out
    }

    /**
     * How fast a replay runs, chosen for the viewer rather than by them: the
     * whole journey plays in [REPLAY_WHOLE_MS] (a short one in no less than
     * [REPLAY_MIN_MS]), and every stage gets at least [REPLAY_STAGE_MS] on
     * screen, so the walk to the metro is seen even in a six-hour drive.
     * The answer is in recorded points per frame of [REPLAY_FRAME_MS].
     */
    const val REPLAY_FRAME_MS = 60L
    const val REPLAY_WHOLE_MS = 25_000L
    const val REPLAY_MIN_MS = 6_000L
    const val REPLAY_STAGE_MS = 2_500L

    /** Points per frame for the journey as a whole. */
    fun replayBaseStep(points: Int): Float {
        if (points < 2) return 0f
        val ms = ((points - 1) * 250L).coerceIn(REPLAY_MIN_MS, REPLAY_WHOLE_MS)
        return (points - 1).toFloat() / (ms / REPLAY_FRAME_MS).toFloat()
    }

    /** Points per frame at [cursor]: the base pace, slowed inside a short stage. */
    fun replayStep(cursor: Float, runs: List<Run>, base: Float): Float {
        if (base <= 0f) return 0f
        val i = cursor.toInt()
        val run = runs.lastOrNull { it.from <= i && i < it.to } ?: return base
        val floor = (run.to - run.from).toFloat() / (REPLAY_STAGE_MS / REPLAY_FRAME_MS).toFloat()
        return minOf(base, floor).coerceAtLeast(base / 40f)
    }

    /** Walked stretches are drawn as dots, not a road line. */
    fun onFoot(mode: String?): Boolean = TransportCatalog.profile(mode).key == TransportCatalog.WALK.key

    /**
     * What a stretch of the trail looks like: a road for anything on wheels,
     * a cycle lane, rails for the train and the metro, a wake on the water,
     * footsteps on foot, a dashed line through the air.
     */
    fun look(mode: String?): String = when (TransportCatalog.profile(mode).key) {
        "TRAIN", "METRO" -> RAIL
        "FERRY", "SHIP" -> WATER
        "WALK" -> FOOT
        "CYCLE" -> CYCLE_LANE
        "FLIGHT" -> AIR
        else -> ROAD
    }

    const val ROAD = "road"
    const val CYCLE_LANE = "cycle"
    const val RAIL = "rail"
    const val WATER = "water"
    const val FOOT = "foot"
    const val AIR = "air"

    /**
     * The part of a route still to come from [here]: from the route point
     * nearest to it onwards. The road already travelled is the trail's.
     */
    fun ahead(route: List<GeoPoint>, here: GeoPoint?): List<GeoPoint> {
        if (here == null || route.size < 2) return route
        var best = 0
        var bestD = Double.MAX_VALUE
        route.forEachIndexed { i, p ->
            val d = com.trippulse.app.core.Geo.haversineM(p, here)
            if (d < bestD) { bestD = d; best = i }
        }
        return listOf(here) + route.subList((best + 1).coerceAtMost(route.size - 1), route.size)
    }
}
