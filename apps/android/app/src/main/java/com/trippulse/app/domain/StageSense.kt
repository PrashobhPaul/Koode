package com.trippulse.app.domain

import com.trippulse.app.core.Geo

/**
 * What the phone's own movement says about a journey's stages, read with
 * plain rules: no model, no guess that the record does not back.
 *
 * A metro never goes to an office. It goes to the station nearest the
 * office, and the traveller gets there some other way: a bike taxi, an
 * auto, a cab, their own two feet. When nobody tapped that change, the
 * fixes still show it. A ride on rails or water ends where the fixes leave
 * the line at a station ([Reason.LEFT_LINE]); what came after is read from
 * its pace: a quarter of the fixes faster than anyone walks is a vehicle,
 * the rest is a walk. A stage on a cab or a bus that was never ridden, only
 * walked, is a walk ([Reason.WALKED_INSTEAD]). And a stand of a few minutes
 * where a stage began is a wait: for the bike taxi at the door, for the
 * metro on the platform, for the cab that never came before the walk.
 *
 * Everything here is pure and unit-tested; the caller decides what to do
 * with a [Split] or a [Wait] (record it, tell it, ask about it).
 */
object StageSense {

    /** One fix of the record, as the sense needs it. */
    data class Fix(val tMs: Long, val lat: Double, val lng: Double, val speedMps: Double? = null, val accuracyM: Double = 0.0) {
        val point: GeoPoint get() = GeoPoint(lat, lng)
    }

    /** A stage as recorded. */
    data class Leg(
        val index: Int, val mode: String,
        val fromName: String, val from: GeoPoint,
        val toName: String, val to: GeoPoint,
        val startedAtMs: Long?, val completedAtMs: Long?
    )

    enum class Pace { STILL, WALKING, RIDING }

    /** A stretch of the record made at one pace: standing, walking, or riding something. */
    data class Episode(
        val fromMs: Long, val toMs: Long, val pace: Pace, val metres: Double,
        val from: GeoPoint, val to: GeoPoint,
        /** Average over the stretch, km/h. */
        val kmh: Double
    ) {
        val ms: Long get() = toMs - fromMs
    }

    enum class Reason {
        /** A ride on rails or water whose fixes left the line at a station and carried on by road. */
        LEFT_LINE,
        /** A stage on a cab, auto, bike taxi or bus that was walked from its start to its end. */
        WALKED_INSTEAD
    }

    /** A stand of some minutes where a stage began: before the ride, on the platform, for the cab. */
    data class Wait(
        /** The stage the wait preceded (for a [Split], the stage it opens). */
        val legIndex: Int,
        val fromMs: Long, val toMs: Long,
        val at: GeoPoint, val placeName: String,
        /** What was waited for, when the stage says: the mode set off on. Null when it is not known. */
        val forMode: String?,
        /** The wait ended with a walk, not the ride it was for. */
        val thenWalked: Boolean
    ) {
        val ms: Long get() = toMs - fromMs
        val minutes: Long get() = (ms + 30_000L) / 60_000L
    }

    /**
     * A stage the movement says was made but nobody recorded: stage
     * [legIndex] ended at [atMs] at [placeName], and from there the journey
     * went on by [mode].
     */
    data class Split(
        val legIndex: Int, val atMs: Long, val at: GeoPoint, val placeName: String,
        val mode: String, val reason: Reason, val pace: Pace,
        /** The stand before setting off from [placeName], when there was one worth telling. */
        val wait: Wait?
    )

    // ---- thresholds: the rules, in one place ----

    /** Standing still: every fix within this of the stand's centre (the GPS wanders a little while you stand). */
    const val STILL_RADIUS_M = 60.0
    /** A stand shorter than this is a traffic light, not a stop in the movement. */
    const val STILL_MIN_MS = 2 * 60_000L
    /** A stand told as a wait is at least this long. */
    const val WAIT_MIN_MS = 3 * 60_000L
    /** Three fixes in four under this: nobody rode. A brisk walk. */
    const val WALK_KMH = 7.5
    /** One fix in four over this: something with wheels, whatever the traffic did to the rest. */
    const val RIDE_KMH = 12.0
    /** Between those, the average decides: a crawl is still a ride if it averages faster than a walk. */
    const val AVERAGE_RIDE_KMH = 6.5
    /** A pair of fixes further apart in time than this says nothing about pace. */
    const val PAIR_MAX_MS = 5 * 60_000L
    /** A fix less sure than this of where it is is not read. */
    const val FIX_ACCURACY_M = 80.0
    /** A fix within this of a station is at the station. */
    const val STATION_M = 250.0
    /** A walk from the start of a ride stage that goes this far and this long, and ends the stage, was the stage. */
    const val WALK_SPLIT_M = 400.0
    const val WALK_SPLIT_MS = 4 * 60_000L
    /** A stand counts as the wait at a stage's start when it began within this of the start. */
    const val START_SLACK_MS = 3 * 60_000L
    /** How much of a stage's record is read for its opening wait. */
    const val START_WINDOW_MS = 60 * 60_000L

    /**
     * How far off its line a ride of a kind must be, for how long, and how far
     * from the last station, before it has plainly ended. A metro line is
     * mapped closely and its stations are near; a railway's track may be
     * mapped as the straight run between stations tens of kilometres apart,
     * so a train is judged more slowly.
     */
    data class OffLine(val offM: Double, val holdMs: Long, val moveM: Double)

    fun offLineFor(kind: TransitNetwork.Kind): OffLine = when (kind) {
        TransitNetwork.Kind.METRO -> OffLine(350.0, 3 * 60_000L, 400.0)
        TransitNetwork.Kind.WATER -> OffLine(300.0, 3 * 60_000L, 300.0)
        TransitNetwork.Kind.RAIL -> OffLine(1_200.0, 8 * 60_000L, 1_500.0)
    }

    /** The rides by road a traveller is driven in: what a last mile is usually made by. */
    val ROAD_RIDES = setOf("CAB", "AUTO", "BIKE_TAXI", "BUS")

    /**
     * The record cut into [Episode]s: a stand wherever the fixes stayed
     * within [STILL_RADIUS_M] of one spot for [STILL_MIN_MS] or more, and
     * between the stands the moves, each at the pace its fixes show.
     * Neighbours share their boundary fix.
     */
    fun episodes(fixes: List<Fix>): List<Episode> {
        val pts = fixes.sortedBy { it.tMs }
        if (pts.size < 2) return emptyList()
        val out = ArrayList<Episode>()
        var moveStart = 0
        var stillStart = 0
        var sumLat = pts[0].lat
        var sumLng = pts[0].lng
        var n = 1
        fun centre() = GeoPoint(sumLat / n, sumLng / n)
        fun stand(a: Int, b: Int): Episode {
            val c = centre()
            return Episode(pts[a].tMs, pts[b].tMs, Pace.STILL, 0.0, c, c, 0.0)
        }
        for (i in 1 until pts.size) {
            val f = pts[i]
            if (Geo.haversineM(centre(), f.point) <= STILL_RADIUS_M) {
                sumLat += f.lat; sumLng += f.lng; n++
                continue
            }
            // The stand ended where the fixes began to move away from it,
            // not where they cleared the circle: the first steps of a walk
            // are the walk's.
            val last = leaving(pts, stillStart, i - 1, centre())
            if (pts[last].tMs - pts[stillStart].tMs >= STILL_MIN_MS) {
                if (stillStart > moveStart) out += move(pts, moveStart, stillStart)
                out += stand(stillStart, last)
                moveStart = last
            }
            stillStart = i; sumLat = f.lat; sumLng = f.lng; n = 1
        }
        val last = pts.lastIndex
        if (pts[last].tMs - pts[stillStart].tMs >= STILL_MIN_MS) {
            if (stillStart > moveStart) out += move(pts, moveStart, stillStart)
            out += stand(stillStart, last)
        } else if (last > moveStart) out += move(pts, moveStart, last)
        return out
    }

    /**
     * The last fix of a stand over [a]..[b] around [centre]: the one before
     * the fixes started getting steadily further away.
     */
    private fun leaving(pts: List<Fix>, a: Int, b: Int, centre: GeoPoint): Int {
        var j = b
        while (j > a && Geo.haversineM(centre, pts[j].point) > Geo.haversineM(centre, pts[j - 1].point)) j--
        return j
    }

    /** The move through fixes [a]..[b], at the pace they show. */
    private fun move(pts: List<Fix>, a: Int, b: Int): Episode {
        var metres = 0.0
        val speeds = ArrayList<Double>()
        for (i in a + 1..b) {
            val p = pts[i - 1]; val q = pts[i]
            val d = Geo.haversineM(p.point, q.point)
            metres += d
            val dt = q.tMs - p.tMs
            if (dt <= 0 || dt > PAIR_MAX_MS) continue
            speeds += q.speedMps?.takeIf { it >= 0 }?.times(3.6) ?: (d / dt * 3_600.0)
        }
        val ms = pts[b].tMs - pts[a].tMs
        val kmh = if (ms > 0) metres / ms * 3_600.0 else 0.0
        val pace = if (speeds.size >= 2) {
            val sorted = speeds.sorted()
            val p75 = sorted[(sorted.size * 3) / 4]
            when {
                p75 >= RIDE_KMH -> Pace.RIDING
                p75 <= WALK_KMH -> Pace.WALKING
                else -> if (kmh >= AVERAGE_RIDE_KMH) Pace.RIDING else Pace.WALKING
            }
        } else if (kmh >= AVERAGE_RIDE_KMH) Pace.RIDING else Pace.WALKING
        return Episode(pts[a].tMs, pts[b].tMs, pace, metres, pts[a].point, pts[b].point, kmh)
    }

    /**
     * The stages the movement says were made but nobody recorded, one per
     * stage at most. [lastMileRide] is what a ride by road is taken to be
     * when the fixes say the last mile was ridden: the ride by road the
     * journey already used, or what people where the traveller is use.
     */
    fun splits(legs: List<Leg>, fixes: List<Fix>, net: TransitNetwork, lastMileRide: String): List<Split> {
        val sorted = fixes.filter { it.accuracyM <= FIX_ACCURACY_M }.sortedBy { it.tMs }
        val lastMs = sorted.lastOrNull()?.tMs ?: return emptyList()
        val out = ArrayList<Split>()
        for (leg in legs) {
            val start = leg.startedAtMs ?: continue
            val end = leg.completedAtMs ?: lastMs
            val inside = sorted.filter { it.tMs in start..end }
            if (inside.size < 4) continue
            val kind = TransitNetwork.kindOf(leg.mode)
            val split = if (kind != null) leftLine(leg, kind, inside, net, lastMileRide) else walkedInstead(leg, inside)
            if (split != null) out += split
        }
        return out
    }

    /**
     * The ride by road a journey's last mile is made by: the one the journey
     * already used before stage [before] (the bike taxi it began with), else
     * [default].
     */
    fun usualRide(legs: List<Leg>, before: Int, default: String): String =
        legs.filter { it.index < before && TransportCatalog.profile(it.mode).key in ROAD_RIDES }
            .maxByOrNull { it.index }?.mode ?: default

    private fun leftLine(leg: Leg, kind: TransitNetwork.Kind, inside: List<Fix>, net: TransitNetwork, lastMileRide: String): Split? {
        if (net.isEmpty) return null
        // A ride whose recorded end is at a station was ended there by a tap: nothing to read.
        if (leg.completedAtMs != null && net.nearestStation(leg.to, kind, STATION_M) != null) return null
        val rule = offLineFor(kind)
        var lastStation: Int? = null
        var arrivedAt: Fix? = null
        var offSince: Fix? = null
        var settled = false
        for (f in inside) {
            val near = net.nearestStation(f.point, kind, STATION_M)
            if (near != null) {
                // At the station: the moment the train was nearest it.
                val at = arrivedAt
                if (near != lastStation || at == null ||
                    Geo.haversineM(net.stations[near].point, f.point) < Geo.haversineM(net.stations[near].point, at.point)
                ) { lastStation = near; arrivedAt = f }
                offSince = null
                continue
            }
            val station = lastStation ?: continue
            val off = net.offLineM(f.point, kind) ?: Double.MAX_VALUE
            if (off <= rule.offM) { offSince = null; continue }
            val since = offSince ?: f.also { offSince = it }
            if (f.tMs - since.tMs >= rule.holdMs && Geo.haversineM(net.stations[station].point, f.point) >= rule.moveM) {
                settled = true
                break
            }
        }
        if (!settled) return null
        val station = net.stations[lastStation ?: return null]
        val from = arrivedAt ?: return null
        val after = inside.filter { it.tMs >= from.tMs }
        val eps = episodes(after)
        val moving = eps.filter { it.pace != Pace.STILL }
        val ridden = moving.filter { it.pace == Pace.RIDING }.sumOf { it.metres }
        val walked = moving.filter { it.pace == Pace.WALKING }.sumOf { it.metres }
        if (ridden + walked <= 0.0) return null
        val pace = if (ridden >= walked) Pace.RIDING else Pace.WALKING
        val mode = if (pace == Pace.RIDING) lastMileRide else TransportCatalog.WALK.key
        val name = TransitNetwork.label(station)
        // The stand before the first move away: the wait for the ride, or before the walk.
        val stood = eps.takeWhile { it.pace == Pace.STILL }.lastOrNull()
        val wait = stood?.takeIf { it.ms >= WAIT_MIN_MS }?.let {
            Wait(leg.index + 1, eps.first().fromMs, it.toMs, it.from, name, if (pace == Pace.RIDING) mode else null, pace == Pace.WALKING)
        }
        return Split(leg.index, from.tMs, station.point, name, mode, Reason.LEFT_LINE, pace, wait)
    }

    private fun walkedInstead(leg: Leg, inside: List<Fix>): Split? {
        if (TransportCatalog.profile(leg.mode).key !in ROAD_RIDES) return null
        val eps = episodes(inside)
        val first = eps.indexOfFirst { it.pace != Pace.STILL }
        if (first < 0) return null
        val walk = eps[first]
        if (walk.pace != Pace.WALKING || walk.metres < WALK_SPLIT_M || walk.ms < WALK_SPLIT_MS) return null
        // The walk was the stage: nothing was ridden after it.
        if (eps.drop(first + 1).any { it.pace == Pace.RIDING }) return null
        val stood = eps.take(first).filter { it.pace == Pace.STILL }
        val wait = stood.lastOrNull()?.let { s ->
            val fromMs = stood.first().fromMs
            Wait(leg.index, fromMs, s.toMs, s.from, leg.fromName, leg.mode, true).takeIf { it.ms >= WAIT_MIN_MS }
        }
        return Split(leg.index, walk.fromMs, wait?.at ?: walk.from, leg.fromName, TransportCatalog.WALK.key, Reason.WALKED_INSTEAD, Pace.WALKING, wait)
    }

    /**
     * The waits worth telling: a stand of [WAIT_MIN_MS] or more where a
     * stage on a ride began (the cab at the door, the metro on the
     * platform), and the stands the [splits] found. Not for a walk, a cycle,
     * the traveller's own car or bike, a flight or a cruise: standing before
     * those is not waiting for them.
     */
    fun waits(legs: List<Leg>, fixes: List<Fix>, splits: List<Split> = emptyList()): List<Wait> {
        val sorted = fixes.filter { it.accuracyM <= FIX_ACCURACY_M }.sortedBy { it.tMs }
        val lastMs = sorted.lastOrNull()?.tMs ?: return splits.mapNotNull { it.wait }
        val out = ArrayList<Wait>()
        val told = HashSet<Long>()
        for (leg in legs) {
            if (!waitedFor(leg.mode)) continue
            if (splits.any { it.legIndex == leg.index && it.reason == Reason.WALKED_INSTEAD }) continue
            val start = leg.startedAtMs ?: continue
            // The stand around the stage's start: the platform before the
            // tap (the change is tapped on boarding), or the door after it.
            val window = sorted.filter { it.tMs in (start - START_WINDOW_MS)..minOf(leg.completedAtMs ?: lastMs, start + START_WINDOW_MS) }
            val stand = episodes(window).firstOrNull {
                it.pace == Pace.STILL && it.toMs >= start - START_SLACK_MS && it.fromMs <= start + START_SLACK_MS &&
                    Geo.haversineM(it.from, leg.from) <= STATION_M
            } ?: continue
            if (stand.ms < WAIT_MIN_MS || !told.add(stand.fromMs)) continue
            out += Wait(leg.index, stand.fromMs, stand.toMs, stand.from, leg.fromName, leg.mode, false)
        }
        out += splits.mapNotNull { it.wait }
        return out.sortedBy { it.fromMs }
    }

    /** Whether standing before a stage on [mode] is waiting for it. */
    fun waitedFor(mode: String): Boolean {
        val key = TransportCatalog.profile(mode).key
        return key in ROAD_RIDES || TransitNetwork.kindOf(mode) != null
    }

    /** "a cab", "the metro": what a wait was for, as a person says it. */
    fun waitedWord(mode: String?): String? = when (TransportCatalog.profile(mode).key.takeIf { mode != null }) {
        "CAB" -> "a cab"
        "AUTO" -> "an auto"
        "BIKE_TAXI" -> "a bike taxi"
        "BUS" -> "the bus"
        "METRO" -> "the metro"
        "TRAIN" -> "the train"
        "FERRY" -> "the ferry"
        "SHIP" -> "the ship"
        else -> null
    }

    /** One line for a wait: "Waited 7 min at Raheja Vistas for a bike taxi", "…for a cab, then walked". */
    fun waitText(w: Wait): String {
        val place = w.placeName.takeIf { it.isNotBlank() && it != StageRepair.EN_ROUTE }?.let { " at $it" } ?: ""
        val word = waitedWord(w.forMode)?.let { " for $it" } ?: ""
        val then = if (w.thenWalked) ", then walked" else ""
        return "Waited ${w.minutes} min$place$word$then"
    }

    /** One line for a split: what the movement showed and what Koode made of it. */
    fun splitText(s: Split, endedMode: String): String {
        val off = when (TransitNetwork.kindOf(endedMode)) {
            TransitNetwork.Kind.METRO -> "Off the metro"
            TransitNetwork.Kind.RAIL -> "Off the train"
            TransitNetwork.Kind.WATER -> "Off the boat"
            null -> "The ${TransportCatalog.label(endedMode).lowercase()} stage"
        }
        val on = if (TransportCatalog.profile(s.mode).key == TransportCatalog.WALK.key) "on foot"
            else "by ${TransportCatalog.label(s.mode).lowercase()}"
        return when (s.reason) {
            Reason.LEFT_LINE -> "$off at ${s.placeName}; $on from there (noticed from the movement)"
            Reason.WALKED_INSTEAD -> "$off was walked (noticed from the movement)"
        }
    }
}
