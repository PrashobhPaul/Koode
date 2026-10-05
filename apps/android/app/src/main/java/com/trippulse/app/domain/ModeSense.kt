package com.trippulse.app.domain

import com.trippulse.app.core.Geo

/**
 * Reads, from the last few minutes of fixes, whether the way the traveller is
 * moving has stopped matching the stage they are on: out of the cab and
 * walking, or off the pavement and onto something fast. It only ever
 * suggests; the traveller confirms with one tap.
 */
object ModeSense {

    data class Fix(val tMs: Long, val lat: Double, val lng: Double, val speedMps: Double? = null)

    enum class Hint {
        /** On a vehicle stage, moving at walking pace and getting somewhere. */
        ON_FOOT,
        /** On a walking stage, moving far faster than anyone walks. */
        ON_SOMETHING_FASTER
    }

    /** How far back the fixes are read. */
    const val WINDOW_MS = 5 * 60_000L

    /** Stages a walk can follow without anyone saying so. Your own car or bike is parked, not left. */
    private val RIDDEN = setOf("CAB", "AUTO", "BUS", "METRO")

    fun hint(mode: String?, fixes: List<Fix>, nowMs: Long): Hint? {
        val key = TransportCatalog.profile(mode).key
        if (key != TransportCatalog.WALK.key && key !in RIDDEN) return null
        val recent = fixes.filter { it.tMs in (nowMs - WINDOW_MS)..nowMs }.sortedBy { it.tMs }
        if (recent.size < 4 || recent.last().tMs - recent.first().tMs < 3 * 60_000L) return null
        val speeds = (1 until recent.size).mapNotNull { i ->
            val a = recent[i - 1]; val b = recent[i]
            val dt = b.tMs - a.tMs
            if (dt <= 0) null
            else b.speedMps?.takeIf { it >= 0 }?.times(3.6)
                ?: (Geo.haversineM(GeoPoint(a.lat, a.lng), GeoPoint(b.lat, b.lng)) / dt * 3_600.0)
        }
        if (speeds.size < 3) return null
        val median = speeds.sorted()[speeds.size / 2]
        val fastest = speeds.max()
        val moved = Geo.haversineM(GeoPoint(recent.first().lat, recent.first().lng), GeoPoint(recent.last().lat, recent.last().lng))
        return when {
            // Walking pace, never a burst of traffic, and genuinely going somewhere.
            key in RIDDEN && median in 2.0..7.0 && fastest < 10.0 && moved >= 250.0 -> Hint.ON_FOOT
            key == TransportCatalog.WALK.key && median >= 15.0 && moved >= 800.0 -> Hint.ON_SOMETHING_FASTER
            else -> null
        }
    }
}
