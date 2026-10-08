package com.trippulse.app.domain

import com.trippulse.app.core.Geo
import java.util.PriorityQueue

/**
 * Metro lines and railway stations, read offline (see
 * tools/transit/build_network.py; data © OpenStreetMap contributors, ODbL).
 *
 * Two things a metro ride needs that the phone's fixes cannot give:
 *
 *  - **Where it began and ended, by name.** A geocoder names the area
 *    ("Tarnaka"); a rider names the station they got off at ("Habsiguda").
 *  - **How far it went.** Underground or not, a metro ride is a few fixes
 *    far apart, and straight lines between them cut every bend. The ride is
 *    measured along the track, station to station, and a ride is never
 *    counted shorter than that. Elsewhere in Koode distance errs long rather
 *    than short; this is the same rule.
 *
 * Pure: built from the text of the two files, no Android.
 */
class TransitNetwork(val stations: List<Station>, private val edges: List<List<Edge>>, val rail: List<Station> = emptyList()) {

    data class Station(val lat: Double, val lng: Double, val name: String, val network: String = "") {
        val point: GeoPoint get() = GeoPoint(lat, lng)
    }

    /** Travelling from one station to the next on a line, [metres] along the track. */
    data class Edge(val to: Int, val metres: Double)

    /** A ride between two stations: the track length and the stations passed, in order. */
    data class Ride(val metres: Double, val stations: List<Int>)

    val isEmpty: Boolean get() = stations.isEmpty()

    /** The metro station nearest [p], if one is within [maxM]. */
    fun nearestMetro(p: GeoPoint, maxM: Double = STATION_RADIUS_M): Int? = nearest(stations, p, maxM)

    /** The railway station nearest [p], if one is within [maxM]. */
    fun nearestRail(p: GeoPoint, maxM: Double = RAIL_RADIUS_M): Station? = nearest(rail, p, maxM)?.let { rail[it] }

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
            val (d, at) = queue.poll()
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
     * How far a metro ride from [a] to [b] went along the track: from the
     * station nearest where it began to the one nearest where it ended. Null
     * when either end is not near a station, or the two are not connected.
     */
    fun rideM(a: GeoPoint, b: GeoPoint): Double? {
        val s = nearestMetro(a) ?: return null
        val e = nearestMetro(b) ?: return null
        return ride(s, e)?.metres
    }

    /** The stations a ride from [a] to [b] passes, as points — the line it took. */
    fun ridePath(a: GeoPoint, b: GeoPoint): List<GeoPoint>? {
        val s = nearestMetro(a) ?: return null
        val e = nearestMetro(b) ?: return null
        if (s == e) return null
        return ride(s, e)?.stations?.map { stations[it].point }
    }

    /**
     * A metro stretch as it is drawn: from its first fix along the line's
     * stations to its last, instead of the straight chords between fixes a
     * train gives. The stretch is returned as it was when its ends are not
     * at the network (a ride still between stations is drawn to the last
     * one it passed, within [LINE_END_M]).
     */
    fun followLine(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size < 2 || isEmpty) return points
        val s = nearestMetro(points.first()) ?: return points
        val e = nearestMetro(points.last(), LINE_END_M) ?: return points
        if (s == e) return points
        val path = ride(s, e)?.stations ?: return points
        return listOf(points.first()) + path.map { stations[it].point } + points.last()
    }

    /**
     * What to call the point a stage began or ended at, when one side of it
     * was a ride on rails: the station, if the point is at one. Null
     * otherwise, so the usual naming applies.
     */
    fun stationLabel(p: GeoPoint, modes: Collection<String>): String? {
        val keys = modes.map { TransportCatalog.profile(it).key }.toSet()
        if (TransportCatalog.METRO.key in keys) {
            nearestMetro(p)?.let { return metroLabel(stations[it].name) }
        }
        if (TransportCatalog.TRAIN.key in keys) {
            nearestRail(p)?.let { return railLabel(it.name) }
        }
        return null
    }

    private fun nearest(list: List<Station>, p: GeoPoint, maxM: Double): Int? {
        var best = -1
        var bestM = maxM
        // A cheap box first: most stations are nowhere near.
        val dLat = maxM / 110_540.0
        val dLng = maxM / (111_320.0 * kotlin.math.cos(Math.toRadians(p.lat)).coerceAtLeast(0.01))
        for (i in list.indices) {
            val s = list[i]
            if (kotlin.math.abs(s.lat - p.lat) > dLat || kotlin.math.abs(s.lng - p.lng) > dLng) continue
            val m = Geo.haversineM(p, s.point)
            if (m <= bestM && s.name.isNotBlank()) { best = i; bestM = m }
        }
        return best.takeIf { it >= 0 }
    }

    companion object {
        /**
         * How near a point must be to a metro station to be at it. The tap
         * that says "now on the metro" is made at the gate or on the stairs,
         * and a stop on the way is rarely closer than a kilometre to the next.
         */
        const val STATION_RADIUS_M = 450.0

        /** Railway stations are bigger and spread further: platforms, yards, the forecourt. */
        const val RAIL_RADIUS_M = 600.0

        /** A ride drawn mid-way is drawn to a station up to this far from the train. */
        const val LINE_END_M = 1_200.0

        /** Two stations this close are one interchange, whatever each line calls it. */
        const val INTERCHANGE_M = 250.0

        val EMPTY = TransitNetwork(emptyList(), emptyList())

        fun metroLabel(name: String): String = "$name Metro"

        private val RAIL_WORDS = Regex("""\b(station|junction|jn|terminus|terminal|halt|cantt|cantonment|central)\b""", RegexOption.IGNORE_CASE)

        fun railLabel(name: String): String = if (RAIL_WORDS.containsMatchIn(name)) name else "$name station"

        /**
         * Reads metro_network.txt ("S|lat|lng|name|network" rows, then
         * "L|network|line|colour|a b:metres c:metres …") and, optionally,
         * rail_stations.txt ("lat|lng|name"). A malformed row is skipped,
         * never fatal.
         */
        fun parse(metroText: String?, railText: String? = null): TransitNetwork {
            val stations = ArrayList<Station>()
            val lines = ArrayList<List<String>>()
            metroText?.lineSequence()?.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val f = line.split('|')
                when (f[0]) {
                    "S" -> if (f.size >= 4) {
                        val lat = f[1].toDoubleOrNull(); val lng = f[2].toDoubleOrNull()
                        if (lat != null && lng != null) stations += Station(lat, lng, f[3].trim(), f.getOrNull(4)?.trim().orEmpty())
                    }
                    "L" -> if (f.size >= 5) lines += f[4].trim().split(' ').filter { it.isNotBlank() }
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
                for (cell in cells) {
                    val parts = cell.split(':')
                    val s = parts[0].toIntOrNull()
                    if (s == null) { last = -1; continue }
                    val m = parts.getOrNull(1)?.toDoubleOrNull()
                    if (last >= 0 && m != null) {
                        link(last, s, m)
                        // The other direction is usually its own line; if it
                        // is missing, the same track serves both ways.
                        if (adj[s][last] == null) link(s, last, m)
                    }
                    last = s
                }
            }
            // Interchanges: changing lines costs no distance ridden.
            for (i in stations.indices) for (j in i + 1 until stations.size) {
                val a = stations[i]; val b = stations[j]
                if (kotlin.math.abs(a.lat - b.lat) > 0.003 || kotlin.math.abs(a.lng - b.lng) > 0.003) continue
                if (Geo.haversineM(a.point, b.point) <= INTERCHANGE_M) { link(i, j, 0.0); link(j, i, 0.0) }
            }
            val rail = ArrayList<Station>()
            railText?.lineSequence()?.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val f = line.split('|')
                if (f.size < 3) return@forEach
                val lat = f[0].toDoubleOrNull(); val lng = f[1].toDoubleOrNull()
                if (lat != null && lng != null && f[2].isNotBlank()) rail += Station(lat, lng, f[2].trim())
            }
            return TransitNetwork(stations, adj.map { m -> m.map { Edge(it.key, it.value) } }, rail)
        }
    }
}
