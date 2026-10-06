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
    /**
     * The road between two fixes, when the planned road explains them: both
     * fixes lie within [snapM] of the road, in order along it, and the road
     * between them is not a long detour (at most [maxDetour] × the straight
     * line, plus 50 m). The vehicle then glides along the road, round its
     * bends, rather than cutting the corner. Null when the road does not
     * explain the move: the glide is then a straight line, as before.
     */
    fun alongRoad(road: List<GeoPoint>, from: GeoPoint, to: GeoPoint, snapM: Double = 60.0, maxDetour: Double = 2.5): List<GeoPoint>? {
        if (road.size < 2) return null
        val a = snap(road, from) ?: return null
        val b = snap(road, to) ?: return null
        if (a.distanceM > snapM || b.distanceM > snapM) return null
        if (b.segment < a.segment || (b.segment == a.segment && b.t < a.t)) return null
        val path = ArrayList<GeoPoint>()
        path += a.point
        for (i in a.segment + 1..b.segment) path += road[i]
        path += b.point
        val straight = com.trippulse.app.core.Geo.haversineM(from, to)
        if (pathLengthM(path) > straight * maxDetour + 50.0) return null
        return path
    }

    /** Where along [path] a share [fraction] of its length falls, and the heading there. */
    fun pointAlong(path: List<GeoPoint>, fraction: Double): Pair<GeoPoint, Double?> {
        if (path.isEmpty()) error("empty path")
        if (path.size == 1) return path[0] to null
        val total = pathLengthM(path)
        var left = total * fraction.coerceIn(0.0, 1.0)
        for (i in 1 until path.size) {
            val a = path[i - 1]; val b = path[i]
            val d = com.trippulse.app.core.Geo.haversineM(a, b)
            if (left <= d || i == path.size - 1) {
                val t = if (d <= 0.0) 0.0 else (left / d).coerceIn(0.0, 1.0)
                val p = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
                return p to if (d > 0.5) headingDeg(a, b) else null
            }
            left -= d
        }
        return path.last() to null
    }

    /**
     * The road ahead to hand to followers with each state: from the point of
     * [road] nearest [here], about [lengthM] of it, at most [maxPoints] points.
     * Enough for a follower's map to glide along the next bends.
     */
    fun roadAhead(road: List<GeoPoint>, here: GeoPoint, lengthM: Double = 2_000.0, maxPoints: Int = 40): List<GeoPoint> {
        if (road.size < 2) return emptyList()
        val s = snap(road, here) ?: return emptyList()
        val out = arrayListOf(s.point)
        var run = 0.0
        var i = s.segment + 1
        while (i < road.size && out.size < maxPoints && run < lengthM) {
            run += com.trippulse.app.core.Geo.haversineM(out.last(), road[i])
            out += road[i]
            i++
        }
        return out
    }

    private data class Snap(val segment: Int, val t: Double, val point: GeoPoint, val distanceM: Double)

    /** The nearest point of [road] to [p]: which segment, how far along it, and how far off. */
    private fun snap(road: List<GeoPoint>, p: GeoPoint): Snap? {
        var best: Snap? = null
        val k = Math.cos(Math.toRadians(p.lat))
        for (i in 0 until road.size - 1) {
            val a = road[i]; val b = road[i + 1]
            val ax = (a.lng - p.lng) * k; val ay = a.lat - p.lat
            val bx = (b.lng - p.lng) * k; val by = b.lat - p.lat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val q = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            val d = com.trippulse.app.core.Geo.haversineM(p, q)
            if (best == null || d < best.distanceM) best = Snap(i, t, q, d)
        }
        return best
    }

    private fun pathLengthM(path: List<GeoPoint>): Double =
        (1 until path.size).sumOf { com.trippulse.app.core.Geo.haversineM(path[it - 1], path[it]) }

    private fun headingDeg(a: GeoPoint, b: GeoPoint): Double {
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lng - a.lng)
        val y = Math.sin(dl) * Math.cos(la2)
        val x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * How long a live glide lasts: the gap the fixes have been arriving at,
     * so the vehicle is always moving and reaches each fix as the next one
     * comes in. It is never ahead of a real fix, only ever behind the latest.
     */
    fun glideMs(observedGapMs: Long?): Long =
        (observedGapMs ?: GLIDE_MIN_MS).coerceIn(GLIDE_MIN_MS, GLIDE_MAX_MS)

    const val GLIDE_MIN_MS = 1_100L
    const val GLIDE_MAX_MS = 30_000L

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
