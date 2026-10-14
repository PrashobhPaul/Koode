package com.trippulse.app.domain

import com.trippulse.app.core.Geo

/**
 * The parts of a journey nobody taps: the walk out of the door to the
 * vehicle, the change from one ride to the next, the walk in at the other
 * end. No vehicle drives into a home or an office, and no one steps off a
 * bike taxi straight onto a metro platform. Plain rules, no model.
 *
 * A change lasts at least a floor that a person needs ([LEAVE_MS],
 * [CHANGE_MS], [STATION_CHANGE_MS], [ARRIVE_MS]); a wait the movement
 * measured (the bike taxi at the gate, the train on the platform) is used
 * instead when it is longer. The change is carved from the stages either
 * side of it, so the parts still add up to the whole journey.
 *
 * And a stage recorded too fast to have been travelled (a bike taxi that
 * crossed 1.7 km in ten seconds) was started late: its start is put back by
 * the distance at the mode's city pace ([departureMs]).
 */
object Changes {

    /** Out of the door to the vehicle: the lift, the gate, the kerb. */
    const val LEAVE_MS = 60_000L
    /** From one ride to the next on the road. */
    const val CHANGE_MS = 60_000L
    /** Into or out of a station: stairs, gates, the platform. */
    const val STATION_CHANGE_MS = 2 * 60_000L
    /** From the vehicle to the door, the desk, the sofa. */
    const val ARRIVE_MS = 60_000L
    /** A measured wait this far from a change belongs to it. */
    const val NEAR_MS = 5 * 60_000L
    /** Below this, a stage's ends are too close to say anything about its pace. */
    const val MIN_SPAN_M = 300.0

    /** A stage, as the rules need it. */
    data class Stage(
        val mode: String, val fromName: String, val toName: String,
        val fromMs: Long, val toMs: Long,
        val metres: Double? = null
    )

    /** A stand the movement measured (a WAITED event). */
    data class Wait(val fromMs: Long, val toMs: Long)

    enum class Kind { LEAVE, CHANGE, ARRIVE }

    /** A change between the rides: [fromMs]..[toMs], [waitedMs] of it a measured stand. */
    data class Change(
        val kind: Kind, val fromMs: Long, val toMs: Long,
        val place: String, val fromMode: String?, val toMode: String?,
        val waitedMs: Long
    ) {
        val ms: Long get() = (toMs - fromMs).coerceAtLeast(0)
        /** Read from the movement rather than assumed from the floor. */
        val measured: Boolean get() = waitedMs > 0
    }

    sealed class Part {
        abstract val fromMs: Long
        abstract val toMs: Long
        val ms: Long get() = (toMs - fromMs).coerceAtLeast(0)

        /** A stage, less the changes carved from its ends. */
        data class Ride(val stage: Stage, override val fromMs: Long, override val toMs: Long) : Part()
        data class Step(val change: Change) : Part() {
            override val fromMs: Long get() = change.fromMs
            override val toMs: Long get() = change.toMs
        }
    }

    private fun walk(mode: String?) = TransportCatalog.profile(mode).key == TransportCatalog.WALK.key
    private fun station(mode: String?) = mode != null && TransitNetwork.kindOf(mode) != null

    /** The least a change between [a] and [b] takes. */
    fun floorMs(a: String?, b: String?): Long = when {
        a == null -> if (walk(b)) 0 else LEAVE_MS
        b == null -> if (walk(a)) 0 else ARRIVE_MS
        walk(a) || walk(b) -> 0          // the walk is the change
        station(a) || station(b) -> STATION_CHANGE_MS
        else -> CHANGE_MS
    }

    /** A riding stretch this close to a change is the vehicle either side of it. */
    const val MOVE_NEAR_MS = 20 * 60_000L
    /** A change measured shorter than this is no change: they were already on board, or stepped straight off. */
    const val NO_CHANGE_MS = 30_000L

    /**
     * When the movement says the vehicle before a change stopped and the one
     * after it got going: the end of the last riding stretch before [at]
     * and the start of the first one after it. Null for an end the fixes
     * do not show.
     */
    private fun measured(at: Long, moves: List<StageSense.Episode>): Pair<Long?, Long?> {
        val rides = moves.filter { it.pace == StageSense.Pace.RIDING }
        val stopped = rides.filter { it.fromMs < at && it.toMs in (at - MOVE_NEAR_MS)..(at + 3 * 60_000L) }.maxOfOrNull { it.toMs }
        val going = rides.filter { it.toMs > at && it.fromMs in (at - 3 * 60_000L)..(at + MOVE_NEAR_MS) }.minOfOrNull { it.fromMs }
        return stopped to going
    }

    /**
     * The journey as rides and the changes between them. [completed]: the
     * last stage has ended, so the walk in is told; while it runs, it is not.
     * [moves] is the record cut into stretches ([StageSense.episodes]):
     * where it shows a change, the change is what it shows (from the
     * vehicle stopping to the next one going), not a floor; a journey
     * started on board has no walk out.
     */
    fun parts(stages: List<Stage>, waits: List<Wait>, completed: Boolean, moves: List<StageSense.Episode> = emptyList()): List<Part> {
        if (stages.isEmpty()) return emptyList()
        val st = stages.sortedBy { it.fromMs }
        // Where each boundary's change lies, before clamping.
        data class Plan(val kind: Kind, var fromMs: Long, var toMs: Long, val place: String, val a: String?, val b: String?, var waited: Long)
        val plans = ArrayList<Plan?>()
        for (i in 0..st.size) {
            val a = st.getOrNull(i - 1)
            val b = st.getOrNull(i)
            if (b == null && !completed) { plans += null; continue }
            val floor = floorMs(a?.mode, b?.mode)
            val at = b?.fromMs ?: a!!.toMs
            // What the movement shows, first: the vehicle stopping and the next one going.
            if (moves.isNotEmpty() && (a == null || !walk(a.mode)) && (b == null || !walk(b.mode))) {
                val (stopped, going) = measured(at, moves)
                val kind = when { a == null -> Kind.LEAVE; b == null -> Kind.ARRIVE; else -> Kind.CHANGE }
                val span: Pair<Long, Long>? = when (kind) {
                    Kind.LEAVE -> going?.let { b!!.fromMs to it }
                    Kind.ARRIVE -> stopped?.let { it to a!!.toMs }
                    Kind.CHANGE -> if (stopped == null && going == null) null else (stopped ?: at) to (going ?: at)
                }
                if (span != null) {
                    val (f, t) = span
                    if (t - f < NO_CHANGE_MS) { plans += null; continue }
                    plans += Plan(kind, f, t, if (a == null) b!!.fromName else a.toName, a?.mode, b?.mode, t - f)
                    continue
                }
            }
            val wait = waits.filter { w -> w.toMs > w.fromMs && w.toMs >= at - NEAR_MS && w.fromMs <= at + NEAR_MS }
                .maxByOrNull { it.toMs - it.fromMs }
            if (floor == 0L && wait == null) { plans += null; continue }
            val kind = when { a == null -> Kind.LEAVE; b == null -> Kind.ARRIVE; else -> Kind.CHANGE }
            val place = if (a == null) b!!.fromName else a.toName
            var from: Long; var to: Long
            when (kind) {
                Kind.LEAVE -> { from = b!!.fromMs; to = maxOf(from + floor, wait?.toMs ?: 0L) }
                Kind.ARRIVE -> { to = a!!.toMs; from = minOf(to - floor, wait?.fromMs ?: Long.MAX_VALUE) }
                Kind.CHANGE -> {
                    from = minOf(at - floor / 2, wait?.fromMs ?: Long.MAX_VALUE)
                    to = maxOf(at + floor - floor / 2, wait?.toMs ?: Long.MIN_VALUE)
                    if (to - from < floor) to = from + floor
                }
            }
            plans += Plan(kind, from, to, place, a?.mode, b?.mode, wait?.let { minOf(it.toMs, to) - maxOf(it.fromMs, from) }?.coerceAtLeast(0) ?: 0)
        }
        // Clamp: a change stays inside the stages either side of it and
        // leaves each ride at least nothing; changes never overlap.
        val out = ArrayList<Part>()
        var cursor = st.first().fromMs
        for (i in st.indices) {
            val s = st[i]
            plans[i]?.let { p ->
                p.fromMs = p.fromMs.coerceIn(cursor, s.toMs)
                p.toMs = p.toMs.coerceIn(p.fromMs, s.toMs)
                if (p.toMs > p.fromMs) {
                    out += Part.Step(Change(p.kind, p.fromMs, p.toMs, p.place, p.a, p.b, p.waited.coerceAtMost(p.toMs - p.fromMs)))
                    cursor = p.toMs
                }
            }
            val next = plans[i + 1]
            val rideEnd = next?.fromMs?.coerceIn(maxOf(cursor, s.fromMs), s.toMs) ?: s.toMs
            val rideFrom = maxOf(cursor, s.fromMs)
            out += Part.Ride(s, rideFrom, maxOf(rideFrom, rideEnd))
            cursor = maxOf(rideFrom, rideEnd)
            next?.let { it.fromMs = cursor }
        }
        plans.last()?.let { p ->
            val end = st.last().toMs
            p.fromMs = p.fromMs.coerceIn(minOf(cursor, end), end); p.toMs = end
            if (p.toMs > p.fromMs) out += Part.Step(Change(p.kind, p.fromMs, p.toMs, p.place, p.a, p.b, p.waited.coerceAtMost(p.toMs - p.fromMs)))
        }
        return out
    }

    /** The time the changes took, in all. */
    fun changeMs(parts: List<Part>): Long = parts.filterIsInstance<Part.Step>().sumOf { it.ms }

    /** "Out of Raheja Vistas, and the wait for the bike taxi", "Off the bike taxi, onto the metro", "Off the bike taxi and into Qualizeal Office". */
    fun text(c: Change): String {
        fun the(mode: String?) = mode?.let { "the ${TransportCatalog.label(it).lowercase()}" } ?: "the ride"
        return when (c.kind) {
            Kind.LEAVE -> "Out of ${c.place}, " + if (c.measured) "and the wait for ${the(c.toMode)}" else "to ${the(c.toMode)}"
            Kind.ARRIVE -> "Off ${the(c.fromMode)} and into ${c.place}"
            Kind.CHANGE -> "Off ${the(c.fromMode)}, onto ${the(c.toMode)}" + if (c.measured) " after a wait" else ""
        }
    }

    // ---- a stage tapped and put right at once ----

    /** Shorter than this, a stage between two others is a tap put right, not a part of the journey. */
    const val MIS_TAP_MS = 60_000L

    /**
     * A stage of seconds that was not travelled: the bike taxi tapped and
     * changed to the metro at once. It goes into the stage after it when
     * its ends are the same place, or when the stage after is a ride on
     * rails or water and it began on that line (at a station or along the
     * track: Start pressed on board). A stage of seconds from home to a
     * station a kilometre away was travelled, only late: see [lateStartFix].
     */
    fun misTap(from: GeoPoint, to: GeoPoint, startedAtMs: Long, completedAtMs: Long, nextMode: String?, net: TransitNetwork?): Boolean {
        if (completedAtMs - startedAtMs >= MIS_TAP_MS || nextMode == null) return false
        if (Geo.haversineM(from, to) < MIN_SPAN_M) return true
        val kind = TransitNetwork.kindOf(nextMode) ?: return false
        if (net == null || net.isEmpty) return false
        if (net.nearestStation(from, kind, StageSense.STATION_M) != null) return true
        return (net.offLineM(from, kind) ?: Double.MAX_VALUE) <= StageSense.offLineFor(kind).offM
    }

    // ---- a stage started late ----

    /** A door-to-door pace in town, km/h: what a road distance takes by each way of travelling. */
    fun cityKmh(mode: String?): Double = when (TransportCatalog.profile(mode).key) {
        "WALK" -> 4.8
        "CYCLE" -> 14.0
        "BIKE_TAXI", "BIKE" -> 22.0
        "AUTO" -> 18.0
        "CAB", "CAR" -> 20.0
        "BUS" -> 15.0
        else -> 20.0
    }

    /** Faster than this, a stage on the road was not travelled in the time recorded for it. */
    fun maxKmh(mode: String?): Double = when (TransportCatalog.profile(mode).key) {
        "WALK" -> 9.0
        "CYCLE" -> 35.0
        else -> 90.0
    }

    /**
     * When a stage by [mode] from [from] to [to], ending at [arrivedAtMs],
     * set off: the road distance at the city pace, and the walk out of the
     * door before it.
     */
    fun departureMs(from: GeoPoint, to: GeoPoint, mode: String?, arrivedAtMs: Long, roadFactor: Double = 1.27): Long {
        val roadM = Geo.haversineM(from, to) * roadFactor
        val rideMs = (roadM / (cityKmh(mode) / 3.6) * 1000).toLong()
        return arrivedAtMs - rideMs - LEAVE_MS
    }

    /**
     * The start a stage on the road really had, when the one recorded is
     * too late for the distance between its ends: null when it is
     * plausible, when its ends are close, or for a ride on rails or water
     * (those begin at their station, see [StageSense]).
     */
    fun lateStartFix(from: GeoPoint, to: GeoPoint, mode: String, startedAtMs: Long, completedAtMs: Long, roadFactor: Double = 1.27): Long? {
        if (TransitNetwork.kindOf(mode) != null) return null
        val straight = Geo.haversineM(from, to)
        if (straight < MIN_SPAN_M) return null
        val ms = (completedAtMs - startedAtMs).coerceAtLeast(1)
        val kmh = straight * roadFactor / ms * 3_600.0
        if (kmh <= maxKmh(mode)) return null
        return departureMs(from, to, mode, completedAtMs, roadFactor).takeIf { it < startedAtMs }
    }
}
