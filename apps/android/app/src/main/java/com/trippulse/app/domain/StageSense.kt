package com.trippulse.app.domain

import com.trippulse.app.core.Geo

/**
 * What the phone's own movement says about a journey's stages, read with
 * plain rules: no model, no guess that the record does not back.
 *
 * A metro never goes to an office, and never leaves from one either. It
 * runs from the station nearest the office to the station nearest home,
 * and the traveller gets to the first and from the last some other way: a
 * bike taxi, an auto, a cab, their own two feet. When nobody tapped those
 * changes, the fixes still show them. A ride on rails or water begins at
 * the station the fixes boarded at ([Reason.BEFORE_LINE]: reached, stood
 * at, and then followed the line to the next station) and ends where the
 * fixes leave the line at a station ([Reason.LEFT_LINE]); what came before
 * and after is read from its pace: a quarter of the fixes faster than
 * anyone walks is a vehicle, the rest is a walk. A stage on a cab or a bus
 * that was never ridden, only walked, is a walk ([Reason.WALKED_INSTEAD]).
 * And a stand of a few minutes where a stage began is a wait: for the bike
 * taxi at the door, for the metro on the platform, for the cab that never
 * came before the walk.
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
        /** A ride on rails or water recorded as starting away from any station: it began at the station the fixes boarded at. */
        BEFORE_LINE,
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
     * A stage the movement says was made but nobody recorded. For
     * [Reason.LEFT_LINE] and [Reason.WALKED_INSTEAD]: stage [legIndex] ended
     * at [atMs] at [placeName], and from there the journey went on by
     * [mode]. For [Reason.BEFORE_LINE]: stage [legIndex] really began at
     * [atMs] at [placeName] (its station), and the stretch before that,
     * from where it was recorded as starting, was made by [mode].
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
    /** A ride to the station shorter than this is the forecourt, not a stage (a walk to it is judged as [WALK_SPLIT_M], [WALK_SPLIT_MS]). */
    const val FIRST_MILE_MIN_M = 400.0
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
            if (kind == null) { walkedInstead(leg, inside)?.let { out += it }; continue }
            // Recorded as starting at a station: it did, and its line is read from the start.
            val atStation = net.isEmpty || net.nearestStation(leg.from, kind, STATION_M) != null
            val boarded = if (atStation) null else boarding(leg, kind, inside, net, lastMileRide)
            boarded?.firstMile?.let { out += it }
            // Where the ride left its line is read only from where it was
            // boarded: a station passed on the road to it is not one it got off at.
            val ridden = when {
                atStation -> inside
                boarded != null -> inside.filter { it.tMs >= boarded.atMs }
                else -> emptyList()
            }
            if (ridden.size >= 4) leftLine(leg, kind, ridden, net, lastMileRide)?.let { out += it }
        }
        return out
    }

    /**
     * The order splits are applied in so that each one's stage number still
     * means what it did when it was found: later stages first; on one
     * stage, its end ([Reason.LEFT_LINE]) before its beginning
     * ([Reason.BEFORE_LINE]), which moves it along by one.
     */
    fun applyOrder(splits: List<Split>): List<Split> =
        splits.sortedWith(compareByDescending<Split> { it.legIndex }.thenBy { if (it.reason == Reason.BEFORE_LINE) 1 else 0 })

    /**
     * The splits renumbered to the stages as they stand once every split is
     * applied: a [Reason.LEFT_LINE] on stage n puts a stage after n, a
     * [Reason.BEFORE_LINE] on stage n puts one before it (so n itself moves
     * along), and each moves the stages after it along by one. A split's
     * wait follows its stage.
     */
    fun shifted(splits: List<Split>): List<Split> = splits.map { sp ->
        val by = splits.count {
            (it.reason == Reason.LEFT_LINE && it.legIndex < sp.legIndex) || (it.reason == Reason.BEFORE_LINE && it.legIndex <= sp.legIndex)
        }
        sp.copy(legIndex = sp.legIndex + by, wait = sp.wait?.let { it.copy(legIndex = it.legIndex + by) })
    }

    /**
     * The ride by road a journey's first or last mile is made by: the one
     * the journey already used, the nearest before stage [near] (the bike
     * taxi it began with), else the nearest after it, else [default].
     */
    fun usualRide(legs: List<Leg>, near: Int, default: String): String {
        val rides = legs.filter { TransportCatalog.profile(it.mode).key in ROAD_RIDES }
        return rides.filter { it.index < near }.maxByOrNull { it.index }?.mode
            ?: rides.filter { it.index > near }.minByOrNull { it.index }?.mode
            ?: default
    }

    /** Where a ride on rails or water was boarded, as the fixes show it, and the stage made to get there when it was one. */
    private data class Boarding(val atMs: Long, val firstMile: Split?)

    /**
     * The station a ride recorded as starting away from any station was
     * boarded at: the first the fixes reached and then left along the line
     * (within [OffLine.offM] of it all the way to the next station). A
     * station merely passed, by a bike taxi on the road under the viaduct,
     * is left behind off the line and does not count; one the fixes stood
     * at or walked into is the surer, and taken first. The ride began when
     * the stand on its platform ended. The way to the station is a stage of
     * its own when it was long enough to be one, by the pace it was made at:
     * ridden, it is the ride by road the journey uses; walked, a walk.
     */
    private fun boarding(leg: Leg, kind: TransitNetwork.Kind, inside: List<Fix>, net: TransitNetwork, lastMileRide: String): Boarding? {
        val rule = offLineFor(kind)
        // Each station the fixes came within reach of, with the fix they were nearest it at.
        data class Visit(val station: Int, val at: Fix, val index: Int)
        val visits = ArrayList<Visit>()
        var current: Visit? = null
        for ((i, f) in inside.withIndex()) {
            val near = net.nearestStation(f.point, kind, STATION_M)
            val c = current
            if (near == null) { c?.let { visits += it }; current = null; continue }
            if (c == null || c.station != near) { c?.let { visits += it }; current = Visit(near, f, i); continue }
            if (Geo.haversineM(net.stations[near].point, f.point) < Geo.haversineM(net.stations[near].point, c.at.point)) current = Visit(near, f, i)
        }
        current?.let { visits += it }
        // A visit the line was followed from: on it from there to a different station.
        fun followedLineFrom(v: Visit): Boolean {
            for (f in inside.drop(v.index + 1)) {
                val near = net.nearestStation(f.point, kind, STATION_M)
                if (near != null && near != v.station) return true
                if (near == null && (net.offLineM(f.point, kind) ?: Double.MAX_VALUE) > rule.offM) return false
            }
            return false
        }
        val candidates = visits.filter { followedLineFrom(it) }
        if (candidates.isEmpty()) return null
        val eps = episodes(inside)
        // The stand on the platform, or the walk in: the surest sign of boarding.
        fun stoodAt(v: Visit): Episode? = eps.firstOrNull { e ->
            (e.pace == Pace.STILL || e.pace == Pace.WALKING) && e.toMs >= v.at.tMs - STILL_MIN_MS && e.fromMs <= v.at.tMs + STILL_MIN_MS &&
                Geo.haversineM(e.to, net.stations[v.station].point) <= STATION_M
        }
        val boarded = candidates.firstOrNull { stoodAt(it) != null } ?: candidates.first()
        val station = net.stations[boarded.station]
        val stood = stoodAt(boarded)?.takeIf { it.pace == Pace.STILL }
        val atMs = maxOf(boarded.at.tMs, stood?.toMs ?: boarded.at.tMs)
        // The way to the station, by the pace of what moved before reaching it.
        val moving = episodes(inside.filter { it.tMs <= atMs }).filter { it.pace != Pace.STILL }
        val ridden = moving.filter { it.pace == Pace.RIDING }.sumOf { it.metres }
        val walked = moving.filter { it.pace == Pace.WALKING }.sumOf { it.metres }
        val pace = if (ridden >= walked) Pace.RIDING else Pace.WALKING
        val isStage = if (pace == Pace.RIDING) ridden + walked >= FIRST_MILE_MIN_M
            else walked >= WALK_SPLIT_M && moving.sumOf { it.ms } >= WALK_SPLIT_MS
        val mode = if (pace == Pace.RIDING) lastMileRide else TransportCatalog.WALK.key
        val firstMile = if (isStage) Split(leg.index, atMs, station.point, TransitNetwork.label(station), mode, Reason.BEFORE_LINE, pace, null) else null
        return Boarding(atMs, firstMile)
    }

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
            if (splits.any { it.legIndex == leg.index && it.reason == Reason.WALKED_INSTEAD }) continue
            // A ride recorded from away from its station was got to some other
            // way, and the stand at its start was for that: the bike taxi, not
            // the metro. Before a walk there is nothing to wait for.
            val firstMile = splits.firstOrNull { it.legIndex == leg.index && it.reason == Reason.BEFORE_LINE && Geo.haversineM(leg.from, it.at) > STATION_M }
            val forMode = when {
                firstMile == null -> leg.mode
                firstMile.pace == Pace.RIDING -> firstMile.mode
                else -> continue
            }
            if (!waitedFor(forMode)) continue
            val start = leg.startedAtMs ?: continue
            // The stand around the stage's start: the platform before the
            // tap (the change is tapped on boarding), or the door after it.
            val window = sorted.filter { it.tMs in (start - START_WINDOW_MS)..minOf(leg.completedAtMs ?: lastMs, start + START_WINDOW_MS) }
            val stand = episodes(window).firstOrNull {
                it.pace == Pace.STILL && it.toMs >= start - START_SLACK_MS && it.fromMs <= start + START_SLACK_MS &&
                    Geo.haversineM(it.from, leg.from) <= STATION_M
            } ?: continue
            if (stand.ms < WAIT_MIN_MS || !told.add(stand.fromMs)) continue
            out += Wait(leg.index, stand.fromMs, stand.toMs, stand.from, leg.fromName, forMode, false)
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

    /** One line for the stage a [Reason.BEFORE_LINE] split puts before the ride: "By bike taxi from Qualizeal Office to Durgam Cheruvu Metro (noticed from the movement)". */
    fun firstMileText(s: Split, fromName: String): String {
        val by = if (TransportCatalog.profile(s.mode).key == TransportCatalog.WALK.key) "On foot" else "By ${TransportCatalog.label(s.mode).lowercase()}"
        val from = fromName.takeIf { it.isNotBlank() && it != StageRepair.EN_ROUTE }?.let { " from $it" } ?: ""
        return "$by$from to ${s.placeName} (noticed from the movement)"
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
        val onto = when (TransitNetwork.kindOf(endedMode)) {
            TransitNetwork.Kind.METRO -> "on the metro"
            TransitNetwork.Kind.RAIL -> "on the train"
            TransitNetwork.Kind.WATER -> "on the boat"
            null -> "on"
        }
        return when (s.reason) {
            Reason.BEFORE_LINE -> "${onto.replaceFirstChar { it.uppercase() }} from ${s.placeName} (noticed from the movement)"
            Reason.LEFT_LINE -> "$off at ${s.placeName}; $on from there (noticed from the movement)"
            Reason.WALKED_INSTEAD -> "$off was walked (noticed from the movement)"
        }
    }
}
