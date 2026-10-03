package com.trippulse.app.domain

import com.trippulse.app.core.Geo

/**
 * Distance covered, reconstructed from the location samples the phone kept.
 *
 * The live count (TripManager.onLocation) adds up fix-to-fix distance while
 * moving, which is right as long as the fixes keep coming. When the phone
 * goes quiet -- the service killed, the phone switched off, a long tunnel --
 * and reporting starts again later, the stretch driven in between has no
 * fixes. It still happened, and a journey's distance must not shrink because
 * the phone did. This ledger walks the samples and credits every pair: the
 * usual fix-to-fix distance while moving, and for a gap the distance from
 * where the phone fell silent to where it reappeared, scaled from the
 * straight line to a road and, when the planned route is known, kept within
 * what that route allows.
 *
 * The same rule is applied live to the first fix after a silence, and to the
 * stored record whenever a journey is reloaded or reported on, so the number
 * on the phone, in the follower's view and on paper agree.
 */
object DistanceLedger {

    data class Point(val tMs: Long, val lat: Double, val lng: Double, val speedMps: Double? = null)

    /** A stretch with no samples, and the distance credited for it. */
    data class Gap(val fromMs: Long, val toMs: Long, val metres: Double)

    data class Result(val totalM: Double, val gaps: List<Gap>) {
        /** How much of the total is estimated rather than measured. */
        val estimatedM: Double get() = gaps.sumOf { it.metres }
    }

    /**
     * Longer than any pause between samples while tracking (the slowest
     * cadence takes a fix every five minutes while parked).
     */
    const val GAP_MS = 6 * 60_000L

    /** Below this the phone sat where it was; a gap is only a drive if it moved. */
    const val GAP_MIN_M = 300.0

    /** What a long stretch's road is to its straight line: a highway hardly bends. */
    const val HIGHWAY_FACTOR = 1.12

    /**
     * Straight line to road. [shortFactor] (the app's usual 1.27) for a hop
     * of up to 10 km, easing to [HIGHWAY_FACTOR] by 100 km: a phone that
     * reappears a hundred kilometres on got there by a main road.
     */
    fun roadFactor(lineM: Double, shortFactor: Double): Double {
        val far = minOf(shortFactor, HIGHWAY_FACTOR)
        if (lineM <= 10_000) return shortFactor
        if (lineM >= 100_000) return far
        return shortFactor + (far - shortFactor) * (lineM - 10_000) / 90_000
    }

    /**
     * What a silence from [a] to [b] is worth: the straight line scaled to a
     * road, never less than the straight line, and never more than the
     * planned route has left to give ([roomM], when the plan is known).
     */
    fun gapCredit(a: GeoPoint, b: GeoPoint, roadFactor: Double, roomM: Double? = null): Double {
        val line = Geo.haversineM(a, b)
        if (line < GAP_MIN_M) return 0.0
        val road = line * roadFactor(line, roadFactor)
        return if (roomM != null && roomM > line) minOf(road, roomM) else if (roomM != null) line else road
    }

    /** Walks the record from the first sample to the last. */
    fun reconstruct(points: List<Point>, restartSpeedKmh: Double, roadFactor: Double): Result {
        var total = 0.0
        val gaps = ArrayList<Gap>()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dt = b.tMs - a.tMs
            if (dt <= 0) continue
            val pa = GeoPoint(a.lat, a.lng)
            val pb = GeoPoint(b.lat, b.lng)
            if (dt >= GAP_MS) {
                val m = gapCredit(pa, pb, roadFactor)
                if (m > 0) { gaps += Gap(a.tMs, b.tMs, m); total += m }
            } else {
                val d = Geo.haversineM(pa, pb)
                // Same rule as the live count: only while moving, so a parked
                // phone's wander does not add up to kilometres.
                val impliedKmh = d / dt * 3600.0
                val reportedKmh = b.speedMps?.let { it * 3.6 }
                if (impliedKmh >= restartSpeedKmh || (reportedKmh != null && reportedKmh >= restartSpeedKmh)) total += d
            }
        }
        return Result(total, gaps)
    }

    /** The number to show: the live count, or the record when it knows more. */
    fun reconcile(liveM: Double, record: Result): Double = maxOf(liveM, record.totalM)
}
