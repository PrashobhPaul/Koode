package com.trippulse.app.domain

import com.trippulse.app.core.Geo
import java.util.PriorityQueue

/**
 * Metro, railway, water-metro and ferry lines, and railway and bus stations, read
 * offline (see tools/transit/build_network.py; data © OpenStreetMap
 * contributors, ODbL). One file per country; India is bundled, other
 * countries are downloaded when the phone is there.
 *
 * Two things a ride on rails or water needs that the phone's fixes cannot give:
 *
 *  - **Where it began and ended, by name.** A geocoder names the area
 *    ("Tarnaka"); a rider names the station they got off at ("Habsiguda").
 *  - **How far it went.** A train or a boat gives a few fixes far apart, and
 *    straight lines between them cut every bend. The ride is measured along
 *    the track (or the boat's course), station to station, and is never
 *    counted shorter than that. Elsewhere in Koode distance errs long rather
 *    than short; this is the same rule.
 *
 * Pure: built from the text of the files, no Android.
 */
class TransitNetwork(
    val stations: List<Station>,
    private val edges: List<List<Edge>>,
    val rail: List<Station> = emptyList(),
    /** Bus and coach stations, for naming where a bus stage began or ended. */
    val bus: List<Station> = emptyList(),
    /** Between two stations, the quickest way (a Shinkansen beside a local line); [edges] keep the shortest. */
    private val fastEdges: List<List<Edge>> = edges,
    /** The named lines of the files ("Blue Line", "Yamanote Line"), in the order read. */
    val lines: List<Line> = emptyList(),
    /** For each station, the [lines] that call at it. */
    private val served: List<Set<Int>> = emptyList()
) {

    /** A line as its network names it, and the colour it is drawn in ("blue", "#4169E1"; blank when not mapped). */
    data class Line(val network: String, val name: String, val colour: String)

    /** Part of a ride on one line: from station [from] to station [to]. */
    data class Run(val line: Line, val from: Int, val to: Int)

    /**
     * A metro station ([Kind.METRO]), a water-metro / ferry terminal
     * ([Kind.WATER]) or a station on a mapped railway line ([Kind.RAIL]).
     */
    data class Station(val lat: Double, val lng: Double, val name: String, val network: String = "", val kind: Kind = Kind.METRO) {
        val point: GeoPoint get() = GeoPoint(lat, lng)
    }

    enum class Kind(val code: Char) { METRO('M'), WATER('W'), RAIL('R') }

    /**
     * Travelling from one station to the next, [metres] along the track, on
     * a line of [speed] class: high-speed ('H'), express ('X') or local ('L')
     * rail, metro ('M'), water ('W'), or the walk between two stations of an
     * interchange ('I'), at [cruiseKmh] between stops (0: the class's usual,
     * [CRUISE_KMH]).
     */
    data class Edge(
        val to: Int, val metres: Double, val speed: Char = 'M', val cruiseKmh: Double = 0.0,
        /** The track between the two stations, its bends, when it was mapped cleanly (its ends left out). */
        val path: List<GeoPoint> = emptyList()
    ) {
        /** The time it takes: the run at cruising speed, and the stop (or the change of trains) at its end. */
        val seconds: Double get() =
            metres / (cruiseKmh.takeIf { it > 0 } ?: CRUISE_KMH[speed] ?: 35.0) * 3.6 + (DWELL_S[speed] ?: 30.0)
    }

    /**
     * A ride between two stations: the track length, the stations passed, in
     * order, and how long the ride takes at its lines' usual speeds.
     */
    data class Ride(val metres: Double, val stations: List<Int>, val seconds: Double = 0.0) {
        /** Its average speed, km/h. */
        val kmh: Double get() = if (seconds > 0) metres / seconds * 3.6 else 0.0
    }

    val isEmpty: Boolean get() = stations.isEmpty() && rail.isEmpty() && bus.isEmpty()

    /** The station of [kind] nearest [p], if one is within [maxM]. */
    fun nearestStation(p: GeoPoint, kind: Kind, maxM: Double = radiusOf(kind)): Int? =
        nearest(stations, p, maxM) { it.kind == kind }

    /** Where [s] (from this network or one read from the same files) is in this one. */
    fun indexOf(s: Station): Int? = nearest(stations, s.point, 5.0) { it.kind == s.kind && it.name == s.name }

    /** The metro station nearest [p], if one is within [maxM]. */
    fun nearestMetro(p: GeoPoint, maxM: Double = STATION_RADIUS_M): Int? = nearestStation(p, Kind.METRO, maxM)

    /**
     * The railway station nearest [p], if one is within [maxM]: one on a
     * mapped line first, any railway station otherwise.
     */
    fun nearestRail(p: GeoPoint, maxM: Double = RAIL_RADIUS_M): Station? =
        nearestStation(p, Kind.RAIL, maxM)?.let { stations[it] } ?: nearest(rail, p, maxM) { true }?.let { rail[it] }

    /** The bus or coach station nearest [p], if one is within [maxM]. */
    fun nearestBus(p: GeoPoint, maxM: Double = BUS_RADIUS_M): Station? = nearest(bus, p, maxM) { true }?.let { bus[it] }

    /**
     * The shortest ride between stations [from] and [to] along the lines,
     * changing lines where two stations are one (same place, or a short walk
     * apart). Null when they are not connected.
     */
    fun ride(from: Int, to: Int): Ride? = search(from, to, edges) { it.metres }

    /**
     * The quickest ride between stations [from] and [to]: what a timetable
     * would offer (the Shinkansen, not the local line beside it), for the
     * time a ride still has to go.
     */
    fun fastest(from: Int, to: Int): Ride? = search(from, to, fastEdges) { it.seconds }

    private fun search(from: Int, to: Int, edges: List<List<Edge>>, cost: (Edge) -> Double): Ride? {
        if (from !in stations.indices || to !in stations.indices) return null
        if (from == to) return Ride(0.0, listOf(from))
        val dist = DoubleArray(stations.size) { Double.MAX_VALUE }
        val prev = IntArray(stations.size) { -1 }
        val via = arrayOfNulls<Edge>(stations.size)
        dist[from] = 0.0
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        queue += 0.0 to from
        while (queue.isNotEmpty()) {
            val (d, at) = queue.poll() ?: break
            if (d > dist[at]) continue
            if (at == to) break
            for (e in edges[at]) {
                val nd = d + cost(e)
                if (nd < dist[e.to]) { dist[e.to] = nd; prev[e.to] = at; via[e.to] = e; queue += nd to e.to }
            }
        }
        if (dist[to] == Double.MAX_VALUE) return null
        val path = ArrayList<Int>()
        var metres = 0.0
        var seconds = 0.0
        var at = to
        while (at != -1) {
            path += at
            via[at]?.let { metres += it.metres; seconds += it.seconds }
            at = prev[at]
        }
        return Ride(metres, path.reversed(), seconds)
    }

    /**
     * How far a ride of [kind] from [a] to [b] went along the track: from the
     * station nearest where it began to the one nearest where it ended. Null
     * when either end is not near a station, or the two are not connected.
     */
    fun rideM(a: GeoPoint, b: GeoPoint, kind: Kind = Kind.METRO): Double? {
        val s = nearestStation(a, kind) ?: return null
        val e = nearestStation(b, kind) ?: return null
        return ride(s, e)?.metres
    }

    /**
     * The ride still ahead of a train, metro or boat at [here], to the
     * station nearest [dest] (up to [DEST_STATION_M] from it: a journey's
     * destination is rarely the station itself). From the station nearest
     * the train, which may be one behind it: a little long, never short.
     * Null when either end is off the network.
     */
    fun ahead(here: GeoPoint, dest: GeoPoint, kind: Kind): Ride? {
        val s = nearestStation(here, kind, AHEAD_SEARCH_M) ?: return null
        val e = nearestStation(dest, kind, DEST_STATION_M) ?: return null
        return fastest(s, e)
    }

    /**
     * A ride as it is drawn: from its first fix along the line's stations to
     * its last, instead of the straight chords between the few fixes a train
     * or a boat gives. Returned as it was when its ends are not at the
     * network (a ride still under way is drawn to the last station it passed,
     * within [LINE_END_M]).
     */
    fun followLine(points: List<GeoPoint>, kind: Kind = Kind.METRO): List<GeoPoint> {
        if (points.size < 2 || stations.isEmpty()) return points
        val s = nearestStation(points.first(), kind) ?: return points
        val e = nearestStation(points.last(), kind, LINE_END_M) ?: return points
        if (s == e) return points
        val path = ride(s, e)?.stations ?: return points
        return listOf(points.first()) + track(path) + points.last()
    }

    /**
     * The track through [path] (station numbers, in order): each station,
     * and between two the bends of the line as mapped, where they are.
     */
    fun track(path: List<Int>): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        for ((i, s) in path.withIndex()) {
            if (i > 0) out += between(path[i - 1], s)
            out += stations[s].point
        }
        return out
    }

    /**
     * The track a vehicle of [kind] moved along from [from] to [to], for the
     * map to glide it round the bends: the line through the stations nearest
     * each, with a station's worth of track either side so a move between
     * two stations is still on it. Null when the track does not explain the
     * move (off the line, or far from it), so the glide is a straight line.
     */
    fun glidePath(from: GeoPoint, to: GeoPoint, kind: Kind): List<GeoPoint>? {
        val s = nearestStation(from, kind, GLIDE_SEARCH_M) ?: return null
        val e = nearestStation(to, kind, GLIDE_SEARCH_M) ?: return null
        val core = if (s == e) listOf(s) else ride(s, e)?.stations ?: return null
        fun beyond(at: Int, notVia: Int?, p: GeoPoint): Int? =
            edges[at].map { it.to }.filter { it != notVia && stations[it].kind == kind }
                .minByOrNull { n -> MapStages.snapDistanceM(track(listOf(at, n)), p) ?: Double.MAX_VALUE }
        val path = ArrayList<Int>()
        beyond(core.first(), core.getOrNull(1), from)?.let { path += it }
        path += core
        beyond(core.last(), core.getOrNull(core.size - 2), to)?.let { path += it }
        return MapStages.alongRoad(track(path), from, to, snapM = GLIDE_SNAP_M)
    }

    /**
     * How far [p] is from the nearest line of [kind]: the track between two
     * stations as mapped (its bends where they were, the straight run where
     * not), or a station itself. Null when no station of the kind is within
     * [searchM]: the place is nowhere near the network at all.
     */
    fun offLineM(p: GeoPoint, kind: Kind, searchM: Double = OFF_LINE_SEARCH_M): Double? {
        var best: Double? = null
        for ((i, s) in stations.withIndex()) {
            if (s.kind != kind) continue
            val d = com.trippulse.app.core.Geo.haversineM(p, s.point)
            if (d > searchM) continue
            if (best == null || d < best) best = d
            for (e in edges[i]) {
                if (e.speed == 'I' || stations[e.to].kind != kind) continue
                val line = ArrayList<GeoPoint>(e.path.size + 2)
                line += s.point; line += e.path; line += stations[e.to].point
                val off = MapStages.snapDistanceM(line, p) ?: continue
                if (off < best!!) best = off
            }
        }
        return best
    }

    /** The mapped bends from station [a] to [b] (either direction), or none. */
    fun between(a: Int, b: Int): List<GeoPoint> =
        edges.getOrNull(a)?.firstOrNull { it.to == b }?.path?.takeIf { it.isNotEmpty() }
            ?: edges.getOrNull(b)?.firstOrNull { it.to == a }?.path?.asReversed().orEmpty()

    /**
     * What to call the point a stage began or ended at, when one side of it
     * was a ride on rails or water: the station or terminal, if the point is
     * at one. Null otherwise, so the usual naming applies.
     */
    fun stationLabel(p: GeoPoint, modes: Collection<String>): String? {
        val keys = modes.map { TransportCatalog.profile(it).key }.toSet()
        for (kind in keys.mapNotNull { kindOf(it) }.distinct()) {
            nearestStation(p, kind)?.let { return label(stations[it]) }
        }
        if (TransportCatalog.TRAIN.key in keys) {
            nearestRail(p)?.let { return railLabel(it.name) }
        }
        if (TransportCatalog.BUS.key in keys) {
            nearestBus(p)?.let { return busLabel(it.name) }
        }
        return null
    }

    /** The lines that call at station [station]. */
    fun linesAt(station: Int): Set<Int> = served.getOrNull(station).orEmpty()

    /**
     * The lines a ride through [path] (station numbers, in order) was made
     * on: as few as can carry it, each as far as it goes before a change.
     * Two stations both on a line, one after the other, are a stretch of
     * it; the walk between two stations of an interchange ends a line.
     * Stretches no named line covers are left out.
     */
    fun runs(path: List<Int>): List<Run> {
        val out = ArrayList<Run>()
        var i = 0
        while (i < path.size - 1) {
            if (isInterchange(path[i], path[i + 1])) { i++; continue }
            var on = linesAt(path[i]) intersect linesAt(path[i + 1])
            if (on.isEmpty()) { i++; continue }
            var j = i + 1
            while (j < path.size - 1 && !isInterchange(path[j], path[j + 1])) {
                val still = on intersect linesAt(path[j + 1])
                if (still.isEmpty()) break
                on = still; j++
            }
            val line = lines[on.min()]
            // One line seen twice in a row (its two directions, a data gap): one run.
            val prev = out.lastOrNull()
            if (prev != null && prev.line.name == line.name && prev.line.network == line.network && prev.to == path[i]) out[out.lastIndex] = prev.copy(to = path[j])
            else out += Run(line, path[i], path[j])
            i = j
        }
        return out
    }

    private fun isInterchange(a: Int, b: Int): Boolean =
        (edges.getOrNull(a)?.firstOrNull { it.to == b } ?: edges.getOrNull(b)?.firstOrNull { it.to == a })?.speed == 'I'

    /**
     * The lines of a ride as a rider says them: "Blue Line", "Blue Line,
     * then Red Line at Ameerpet". Null when no named line carried it.
     */
    fun lineText(runs: List<Run>): String? {
        if (runs.isEmpty()) return null
        return buildString {
            append(runs.first().line.name)
            for (r in runs.drop(1)) append(", then ${r.line.name} at ${stations[r.from].name}")
        }
    }

    /**
     * The line (or lines) a ride of [kind] from [from] to [to] is on: the
     * quickest ride between the stations nearest each, as a timetable
     * would offer it. Null off the network or where no line is named.
     */
    fun lineFor(from: GeoPoint, to: GeoPoint, kind: Kind, toWithinM: Double = DEST_STATION_M): String? {
        val s = nearestStation(from, kind) ?: return null
        val e = nearestStation(to, kind, toWithinM) ?: return null
        if (s == e) return linesAt(s).singleOrNull()?.let { lines[it].name }
        return lineText(runs(fastest(s, e)?.stations ?: return null))
    }

    /**
     * The station a journey of [kind] from [from] to [to] is best begun at:
     * of the stations within [FIRST_MILE_SEARCH_M], the one that gets there
     * soonest, counting the way to it (walked when it is near, ridden by
     * road otherwise, with the wait for the ride) and the ride from it to
     * the station nearest [to]. Not always the nearest: a station a little
     * further on the line that goes there beats the near one on the line
     * that does not. Null when no station is in reach.
     */
    fun boardingStation(from: GeoPoint, to: GeoPoint, kind: Kind): Int? {
        val end = nearestStation(to, kind, DEST_STATION_M)
        var best: Int? = null
        var bestS = Double.MAX_VALUE
        for ((i, s) in stations.withIndex()) {
            if (s.kind != kind || s.name.isBlank()) continue
            val m = Geo.haversineM(from, s.point)
            if (m > FIRST_MILE_SEARCH_M) continue
            val wayS = firstMileSeconds(m)
            val rideS = when {
                end == null -> 0.0
                end == i -> 0.0
                else -> fastest(i, end)?.seconds ?: continue
            }
            if (wayS + rideS < bestS) { bestS = wayS + rideS; best = i }
        }
        return best
    }

    private fun nearest(list: List<Station>, p: GeoPoint, maxM: Double, ok: (Station) -> Boolean): Int? {
        var best = -1
        var bestM = maxM
        // A cheap box first: most stations are nowhere near.
        val dLat = maxM / 110_540.0
        val dLng = maxM / (111_320.0 * kotlin.math.cos(Math.toRadians(p.lat)).coerceAtLeast(0.01))
        for (i in list.indices) {
            val s = list[i]
            if (kotlin.math.abs(s.lat - p.lat) > dLat || kotlin.math.abs(s.lng - p.lng) > dLng) continue
            if (s.name.isBlank() || !ok(s)) continue
            val m = Geo.haversineM(p, s.point)
            if (m <= bestM) { best = i; bestM = m }
        }
        return best.takeIf { it >= 0 }
    }

    companion object {
        /**
         * How near a point must be to a station to be at it. The tap that
         * says "now on the metro" is made at the gate or on the stairs, and a
         * stop on the way is rarely closer than a kilometre to the next.
         */
        const val STATION_RADIUS_M = 450.0

        /** Railway stations are bigger and spread further: platforms, yards, the forecourt. */
        const val RAIL_RADIUS_M = 600.0

        /** A bus station is a yard and its bays; a stop beyond this is somewhere else. */
        const val BUS_RADIUS_M = 250.0

        /** A gliding vehicle looks for its line this far around it, and may be this far off the track. */
        /** How far around a place the lines are looked for, for how far off them it is. */
        const val OFF_LINE_SEARCH_M = 4_000.0
        const val GLIDE_SEARCH_M = 2_500.0
        const val GLIDE_SNAP_M = 150.0

        /** How far a moving train may be from the station nearest it: a high-speed line's are 30-60 km apart. */
        const val AHEAD_SEARCH_M = 30_000.0

        /** How far from a station a ride's destination may be and still be where the ride is going. */
        const val DEST_STATION_M = 3_000.0

        /**
         * Speeds between stops, km/h, when a line does not give its own:
         * high-speed, express and local rail, metro, water, and walking
         * between the stations of an interchange. A line's own (the file's
         * "H270") is its country's: a Shinkansen holds 270, an Acela 120.
         */
        val CRUISE_KMH = mapOf('H' to 220.0, 'X' to 110.0, 'L' to 55.0, 'M' to 35.0, 'W' to 25.0, 'I' to 4.5)

        /** Seconds at each stop; for an interchange, the change of trains. */
        val DWELL_S = mapOf('H' to 90.0, 'X' to 60.0, 'L' to 40.0, 'M' to 30.0, 'W' to 120.0, 'I' to 180.0)

        fun radiusOf(kind: Kind): Double = if (kind == Kind.RAIL) RAIL_RADIUS_M else STATION_RADIUS_M

        /** How far from where a journey begins a station is looked for to begin a ride on rails or water at. */
        const val FIRST_MILE_SEARCH_M = 4_000.0

        /** A station this near (as the crow flies) is walked to; further, a ride by road is taken. */
        const val WALK_TO_STATION_M = 1_000.0

        /**
         * The time the way to a station [m] away (as the crow flies) takes:
         * walked at 4.5 km/h when it is near, otherwise ridden through a
         * city at 18 km/h after five minutes finding the ride, the road a
         * quarter longer than the straight line.
         */
        fun firstMileSeconds(m: Double): Double =
            if (m <= WALK_TO_STATION_M) m * 1.25 / (4.5 / 3.6) else 300.0 + m * 1.25 / (18.0 / 3.6)

        /**
         * The usual wait on the platform, seconds, for a ride of [kind]: half
         * the time between trains at the hours people travel. For the time
         * a journey still has to go while it is waited for.
         */
        fun platformWaitS(kind: Kind): Double = when (kind) {
            Kind.METRO -> 240.0
            Kind.WATER -> 600.0
            Kind.RAIL -> 600.0
        }

        /** A ride drawn mid-way is drawn to a station up to this far from the train. */
        const val LINE_END_M = 1_200.0

        /** Two stations this close are one interchange, whatever each line calls it. */
        const val INTERCHANGE_M = 250.0

        /** Two stations of one name this close are one interchange (Kashmere Gate's lines). */
        const val SAME_NAME_INTERCHANGE_M = 800.0

        val EMPTY = TransitNetwork(emptyList(), emptyList())

        /** The kind of network a mode rides, if any: the metro, a water metro / ferry / ship, the railway. */
        fun kindOf(mode: String?): Kind? = when (TransportCatalog.profile(mode).key) {
            TransportCatalog.METRO.key -> Kind.METRO
            TransportCatalog.FERRY.key, TransportCatalog.SHIP.key -> Kind.WATER
            TransportCatalog.TRAIN.key -> Kind.RAIL
            else -> null
        }

        private val METRO_WORD = Regex("""m[eé]tro""", RegexOption.IGNORE_CASE)
        private val WATER_METRO = Regex("""water\s+metro""", RegexOption.IGNORE_CASE)
        private val TERMINAL_WORDS = Regex("""\b(jetty|terminal|pier|ferry|ghat|wharf|dock|harbou?r|landing|quay|port)\b""", RegexOption.IGNORE_CASE)
        private val RAIL_WORDS = Regex("""\b(station|junction|jn|terminus|terminal|halt|cantt|cantonment|central|hbf|hauptbahnhof|bahnhof|gare|estaci[oó]n|stazione|centraal)\b|駅$""", RegexOption.IGNORE_CASE)
        private val BUS_WORDS = Regex("""\b(bus|coach|busbahnhof|zob|gare routi[eè]re|autobuses|autostazione|terminal|depot|stand)\b|バス""", RegexOption.IGNORE_CASE)

        /**
         * What riders call a station: "Habsiguda Metro" where the network is
         * a metro, "Times Sq-42 St station" on New York's subway, "Vyttila
         * Water Metro" on Kochi's boats, a ferry terminal by its own name.
         */
        fun label(s: Station): String = when (s.kind) {
            Kind.WATER -> when {
                WATER_METRO.containsMatchIn(s.network) -> "${s.name} Water Metro"
                TERMINAL_WORDS.containsMatchIn(s.name) -> s.name
                else -> "${s.name} ferry"
            }
            Kind.METRO -> if (METRO_WORD.containsMatchIn(s.network)) "${s.name} Metro"
                else if (RAIL_WORDS.containsMatchIn(s.name)) s.name else "${s.name} station"
            Kind.RAIL -> railLabel(s.name)
        }

        /** A station of [kind] not yet known by name: "the metro station" until the phone reaches one. */
        fun stationWord(kind: Kind): String = when (kind) {
            Kind.METRO -> "the metro station"
            Kind.WATER -> "the jetty"
            Kind.RAIL -> "the railway station"
        }

        fun railLabel(name: String): String = if (RAIL_WORDS.containsMatchIn(name)) name else "$name station"

        private const val PATH_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /**
         * A track as the files write it (tools/transit/build_network.py,
         * encode_path): each point's change from the one before in 1e-5
         * degrees, five bits a letter with a sixth for "more", zigzag-signed.
         */
        fun decodePath(text: String): List<GeoPoint> {
            val out = ArrayList<GeoPoint>()
            var lat = 0L; var lng = 0L; var i = 0
            while (i < text.length) {
                val v = LongArray(2)
                for (k in 0..1) {
                    var acc = 0L; var shift = 0
                    while (true) {
                        if (i >= text.length) return out
                        val c = PATH_ALPHABET.indexOf(text[i++])
                        if (c < 0) return out
                        acc = acc or ((c and 0x1F).toLong() shl shift)
                        shift += 5
                        if (c and 0x20 == 0) break
                    }
                    v[k] = (acc shr 1) xor -(acc and 1)
                }
                lat += v[0]; lng += v[1]
                out += GeoPoint(lat / 1e5, lng / 1e5)
            }
            return out
        }

        /** "Victoria Coach Station", "Port Authority Bus Terminal"; a bare name gets "bus station". */
        fun busLabel(name: String): String = if (BUS_WORDS.containsMatchIn(name)) name else "$name bus station"

        /**
         * Reads one country's file: "S|lat|lng|name|network|kind" stations,
         * "L|network|line|colour|a b:metres c:metres …" lines (station numbers
         * within the file), "R|lat|lng|name|network" stations on railway lines
         * and "Q|network|line|colour|class|a b:metres …" the lines (numbering
         * the R rows), "T|lat|lng|name" railway stations and "B|lat|lng|name"
         * bus and coach stations. A malformed row is skipped, never fatal.
         */
        fun parse(text: String?): TransitNetwork = combine(listOf(text))

        /** Several countries' files as one network. */
        fun combine(texts: List<String?>): TransitNetwork {
            val stations = ArrayList<Station>()
            // Each line: its stations with the metres and the track from the one
            // before, its speed class (null: the metro's or the water's, by its
            // stations) and, for a railway line, the speed its trains keep.
            data class Cell(val station: Int, val metres: Double?, val path: List<GeoPoint>)
            val lines = ArrayList<Triple<List<Cell>, Char?, Double>>()
            // Each line's name, by its place in [lines]; the same name on one network is one line (its two directions).
            val named = ArrayList<Line>()
            val lineOf = ArrayList<Int>()
            fun nameLine(f: List<String>) {
                val line = Line(f[1].trim(), f[2].trim(), f.getOrNull(3)?.trim().orEmpty())
                if (line.name.isBlank()) { lineOf += -1; return }
                val known = named.indexOfFirst { it.network == line.network && it.name == line.name }
                lineOf += if (known >= 0) known else { named += line; named.lastIndex }
            }
            val rail = ArrayList<Station>()
            val bus = ArrayList<Station>()
            fun cells(field: String, local: Map<Int, Int>) = field.trim().split(' ').filter { it.isNotBlank() }.map { cell ->
                val parts = cell.split(':')
                val s = parts[0].toIntOrNull()?.let { local[it] } ?: -1
                Cell(s, parts.getOrNull(1)?.toDoubleOrNull(), parts.getOrNull(2)?.let { decodePath(it) }.orEmpty())
            }
            fun place(f: List<String>): Pair<Double, Double>? {
                val lat = f.getOrNull(1)?.toDoubleOrNull() ?: return null
                val lng = f.getOrNull(2)?.toDoubleOrNull() ?: return null
                return lat to lng
            }
            for (text in texts) {
                val local = HashMap<Int, Int>()
                val localRail = HashMap<Int, Int>()
                var count = 0
                var railCount = 0
                text?.lineSequence()?.forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val f = line.split('|')
                    when (f[0]) {
                        "S" -> {
                            val n = count++
                            if (f.size < 4) return@forEach
                            val lat = f[1].toDoubleOrNull(); val lng = f[2].toDoubleOrNull()
                            if (lat == null || lng == null) return@forEach
                            val kind = if (f.getOrNull(5)?.trim() == "W") Kind.WATER else Kind.METRO
                            local[n] = stations.size
                            stations += Station(lat, lng, f[3].trim(), f.getOrNull(4)?.trim().orEmpty(), kind)
                        }
                        "L" -> if (f.size >= 5) { lines += Triple(cells(f[4], local), null, 0.0); nameLine(f) }
                        "R" -> {
                            val n = railCount++
                            val at = place(f) ?: return@forEach
                            if (f.size < 4) return@forEach
                            localRail[n] = stations.size
                            stations += Station(at.first, at.second, f[3].trim(), f.getOrNull(4)?.trim().orEmpty(), Kind.RAIL)
                        }
                        "Q" -> if (f.size >= 6) {
                            val klass = f[4].trim()
                            lines += Triple(cells(f[5], localRail), klass.firstOrNull()?.takeIf { it in "HXL" } ?: 'L', klass.drop(1).toDoubleOrNull() ?: 0.0)
                            nameLine(f)
                        }
                        "T" -> if (f.size >= 4) {
                            val at = place(f)
                            if (at != null && f[3].isNotBlank()) rail += Station(at.first, at.second, f[3].trim())
                        }
                        "B" -> if (f.size >= 4) {
                            val at = place(f)
                            if (at != null && f[3].isNotBlank()) bus += Station(at.first, at.second, f[3].trim())
                        }
                    }
                }
            }
            val adj = List(stations.size) { HashMap<Int, Edge>() }
            val fast = List(stations.size) { HashMap<Int, Edge>() }
            fun link(a: Int, b: Int, m: Double, speed: Char, cruise: Double = 0.0, path: List<GeoPoint> = emptyList()) {
                if (a == b || a !in stations.indices || b !in stations.indices) return
                val e = Edge(b, m, speed, cruise, path)
                // The shortest track between two stations, and the quickest line along it.
                if (adj[a][b].let { it == null || m < it.metres }) adj[a][b] = e
                if (fast[a][b].let { it == null || e.seconds < it.seconds }) fast[a][b] = e
            }
            val served = List(stations.size) { HashSet<Int>() }
            for ((n, line) in lines.withIndex()) {
                val (cells, klass, cruise) = line
                val name = lineOf.getOrElse(n) { -1 }
                var last = -1
                for ((s, m, path) in cells) {
                    if (s < 0) { last = -1; continue }
                    if (name >= 0) served[s] += name
                    if (last >= 0 && m != null) {
                        // Never less than the straight line between the two
                        // stations: a ride cannot be shorter than that.
                        val hop = maxOf(m, Geo.haversineM(stations[last].point, stations[s].point))
                        val speed = klass ?: if (stations[s].kind == Kind.WATER) 'W' else 'M'
                        val back = fast[s][last] == null
                        link(last, s, hop, speed, cruise, path)
                        // The other direction is usually its own line; if it
                        // is missing, the same track serves both ways.
                        if (back) link(s, last, hop, speed, cruise, path.asReversed())
                    }
                    last = s
                }
            }
            // Interchanges: changing lines costs the walk between the two
            // stations (it was travelled, and it keeps a chain of nearby
            // stations from becoming a free shortcut). Sorted by latitude so
            // only neighbours are compared.
            val order = stations.indices.sortedBy { stations[it].lat }
            for (x in order.indices) {
                val a = stations[order[x]]
                for (y in x + 1 until order.size) {
                    val b = stations[order[y]]
                    if (b.lat - a.lat > 0.008) break
                    if (a.kind != b.kind || kotlin.math.abs(a.lng - b.lng) > 0.01) continue
                    val m = Geo.haversineM(a.point, b.point)
                    val same = a.name.isNotBlank() && a.name.equals(b.name, ignoreCase = true)
                    if (m <= INTERCHANGE_M || (same && m <= SAME_NAME_INTERCHANGE_M)) {
                        link(order[x], order[y], m, 'I'); link(order[y], order[x], m, 'I')
                    }
                }
            }
            return TransitNetwork(stations, adj.map { it.values.toList() }, rail, bus, fast.map { it.values.toList() }, named, served)
        }
    }
}
