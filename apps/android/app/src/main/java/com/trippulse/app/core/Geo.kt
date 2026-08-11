package com.trippulse.app.core

import com.trippulse.app.domain.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geo math used by the domain engines. No Android dependencies so all of
 * it is unit-testable on the JVM.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_008.8

    fun haversineM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    fun pathLengthM(path: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until path.size) total += haversineM(path[i - 1], path[i])
        return total
    }

    /**
     * Distance in meters from [p] to segment [a]-[b], plus the projection
     * parameter t in [0,1]. Uses a local equirectangular projection which is
     * accurate for the sub-10km distances route-deviation checks care about.
     */
    fun distancePointToSegmentM(p: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<Double, Double> {
        val lat0 = Math.toRadians(p.lat)
        val mPerLng = cos(lat0) * 111_320.0
        val mPerLat = 110_540.0
        val ax = (a.lng - p.lng) * mPerLng
        val ay = (a.lat - p.lat) * mPerLat
        val bx = (b.lng - p.lng) * mPerLng
        val by = (b.lat - p.lat) * mPerLat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        return sqrt(px * px + py * py) to t
    }

    /** Minimum distance in meters from [p] to a polyline path. */
    fun minDistanceToPathM(p: GeoPoint, path: List<GeoPoint>): Double {
        if (path.isEmpty()) return Double.MAX_VALUE
        if (path.size == 1) return haversineM(p, path[0])
        var best = Double.MAX_VALUE
        for (i in 1 until path.size) {
            val (d, _) = distancePointToSegmentM(p, path[i - 1], path[i])
            if (d < best) best = d
        }
        return best
    }

    /**
     * Remaining distance in meters along [path] from the point on the path
     * nearest to [p] to the end of the path.
     */
    fun remainingAlongPathM(p: GeoPoint, path: List<GeoPoint>): Double {
        if (path.size < 2) return if (path.isEmpty()) 0.0 else haversineM(p, path.last())
        var bestDist = Double.MAX_VALUE
        var bestIdx = 0
        var bestT = 0.0
        for (i in 1 until path.size) {
            val (d, t) = distancePointToSegmentM(p, path[i - 1], path[i])
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
                bestT = t
            }
        }
        val a = path[bestIdx - 1]
        val b = path[bestIdx]
        val proj = GeoPoint(a.lat + (b.lat - a.lat) * bestT, a.lng + (b.lng - a.lng) * bestT)
        var remaining = haversineM(proj, b)
        for (i in bestIdx + 1 until path.size) remaining += haversineM(path[i - 1], path[i])
        return remaining
    }

    /** Decodes a Google encoded polyline into a list of points. */
    fun decodePolyline(encoded: String): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(GeoPoint(lat / 1e5, lng / 1e5))
        }
        return out
    }
}
