package com.trippulse.app.domain

import com.trippulse.app.core.Geo
import java.util.PriorityQueue

/**
 * Metro lines, water-metro and ferry routes, and railway stations, read
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
class TransitNetwork(val stations: List<Station>, private val edges: List<List<Edge>>, val rail: List<Station> = emptyList()) {

    /** A metro station ([Kind.METRO]) or a water-metro / ferry terminal ([Kind.WATER]). */
    data class Station(val lat: Double, val lng: Double, val name: String, val network: String = "", val kind: Kind = Kind.METRO) {
        val point: GeoPoint get() = GeoPoint(lat, lng)
    }

    enum class Kind(val code: Char) { METRO('M'), WATER('W') }

    /** Travelling from one station to the next on a line, [metres] along the track. */
    data class Edge(val to: Int, val metres: Double)

    /** A ride between two stations: the track length and the stations passed, in order. */
    data class Ride(val metres: Double, val stations: List<Int>)

    val isEmpty: Boolean get() = stations.isEmpty() && rail.isEmpty()

    /** The station of [kind] nearest [p], if one is within [maxM]. */
    fun nearestStation(p: GeoPoint, kind: Kind, maxM: Double = STATION_RADIUS_M): Int? =
        nearest(stations, p, maxM) { it.kind == kind }

    /** The metro station nearest [p], if one is within [maxM]. */
    fun nearestMetro(p: GeoPoint, maxM: Double = STATION_RADIUS_M): Int? = nearestStation(p, Kind.METRO, maxM)

    /** The railway station nearest [p], if one is within [maxM]. */
    fun nearestRail(p: GeoPoint, maxM: Double = RAIL_RADIUS_M): Station? = nearest(rail, p, maxM) { true }?.let { rail[it] }

    /**
     * The shortest ride between stations [from] and [to] along the lines,
     * changing lines where two stations are one (same place, or a short walk
     * apart). Null when they are not connected.
     */
    fun ride(from: Int, to: Int): Ride? {
        if (from !in stations.indices || to !in stations.indices) return null
        if (from == to) return Ride(0.0, listOf(from))
        val dist = DoubleArray(stations.size) { Double.MAX_VALUE }
        val prev = IntArray(stations.size) { -1 }
        dist[from] = 0.0
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        queue += 0.0 to from
        while (queue.isNotEmpty()) {
            val (d, at) = queue.poll() ?: break
            if (d > dist[at]) continue
            if (at == to) break
            for (e in edges[at]) {
                val nd = d + e.metres
                if (nd < dist[e.to]) { dist[e.to] = nd; prev[e.to] = at; queue += nd to e.to }
            }
        }
        if (dist[to] == Double.MAX_VALUE) return null
        val path = ArrayList<Int>()
        var at = to
        while (at != -1) { path += at; at = prev[at] }
        return Ride(dist[to], path.reversed())
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
        return listOf(points.first()) + path.map { stations[it].point } + points.last()
    }

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
        return null
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

        /** A ride drawn mid-way is drawn to a station up to this far from the train. */
        const val LINE_END_M = 1_200.0

        /** Two stations this close are one interchange, whatever each line calls it. */
        const val INTERCHANGE_M = 250.0

        /** Two stations of one name this close are one interchange (Kashmere Gate's lines). */
        const val SAME_NAME_INTERCHANGE_M = 800.0

        val EMPTY = TransitNetwork(emptyList(), emptyList())

        /** The kind of network a mode rides, if any: the metro, or a water metro / ferry. */
        fun kindOf(mode: String?): Kind? = when (TransportCatalog.profile(mode).key) {
            TransportCatalog.METRO.key -> Kind.METRO
            TransportCatalog.FERRY.key -> Kind.WATER
            else -> null
        }

        private val METRO_WORD = Regex("""m[eé]tro""", RegexOption.IGNORE_CASE)
        private val WATER_METRO = Regex("""water\s+metro""", RegexOption.IGNORE_CASE)
        private val TERMINAL_WORDS = Regex("""\b(jetty|terminal|pier|ferry|ghat|wharf|dock|harbou?r|landing|quay|port)\b""", RegexOption.IGNORE_CASE)
        private val RAIL_WORDS = Regex("""\b(station|junction|jn|terminus|terminal|halt|cantt|cantonment|central)\b""", RegexOption.IGNORE_CASE)

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
        }

        fun railLabel(name: String): String = if (RAIL_WORDS.containsMatchIn(name)) name else "$name station"

        /**
         * Reads one country's file: "S|lat|lng|name|network|kind" stations,
         * "L|network|line|colour|a b:metres c:metres …" lines (station numbers
         * within the file) and "T|lat|lng|name" railway stations. A malformed
         * row is skipped, never fatal.
         */
        fun parse(text: String?): TransitNetwork = combine(listOf(text))

        /** Several countries' files as one network. */
        fun combine(texts: List<String?>): TransitNetwork {
            val stations = ArrayList<Station>()
            val lines = ArrayList<List<Pair<Int, Double?>>>()
            val rail = ArrayList<Station>()
            for (text in texts) {
                val local = HashMap<Int, Int>()
                var count = 0
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
                        "L" -> if (f.size >= 5) lines += f[4].trim().split(' ').filter { it.isNotBlank() }.map { cell ->
                            val parts = cell.split(':')
                            val s = parts[0].toIntOrNull()?.let { local[it] } ?: -1
                            s to parts.getOrNull(1)?.toDoubleOrNull()
                        }
                        "T" -> if (f.size >= 4) {
                            val lat = f[1].toDoubleOrNull(); val lng = f[2].toDoubleOrNull()
                            if (lat != null && lng != null && f[3].isNotBlank()) rail += Station(lat, lng, f[3].trim())
                        }
                    }
                }
            }
            val adj = List(stations.size) { HashMap<Int, Double>() }
            fun link(a: Int, b: Int, m: Double) {
                if (a == b || a !in stations.indices || b !in stations.indices) return
                val old = adj[a][b]
                if (old == null || m < old) adj[a][b] = m
            }
            for (cells in lines) {
                var last = -1
                for ((s, m) in cells) {
                    if (s < 0) { last = -1; continue }
                    if (last >= 0 && m != null) {
                        // Never less than the straight line between the two
                        // stations: a ride cannot be shorter than that.
                        val hop = maxOf(m, Geo.haversineM(stations[last].point, stations[s].point))
                        link(last, s, hop)
                        // The other direction is usually its own line; if it
                        // is missing, the same track serves both ways.
                        if (adj[s][last] == null) link(s, last, hop)
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
                        link(order[x], order[y], m); link(order[y], order[x], m)
                    }
                }
            }
            return TransitNetwork(stations, adj.map { m -> m.map { Edge(it.key, it.value) } }, rail)
        }
    }
}
