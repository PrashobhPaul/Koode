package com.trippulse.app.domain.report

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every place name a report knows, by position.
 *
 * A report never prints a bare coordinate as a place (only the emergency
 * last-known-position document adds coordinates, and even there beside a
 * name). Names come, best first, from: a place the traveller saved, the name
 * the app recorded with an entry when it was logged, the journey's own ends,
 * and names the phone's geocoder supplied for the few points still unnamed
 * (see [JourneyStory.namingTargets]). Lookups never touch the network: the
 * book is filled first, then read.
 */
class PlaceBook {
    private data class Named(val lat: Double, val lng: Double, val name: String, val rank: Int)
    private val names = ArrayList<Named>()

    /** Ranks: lower wins when two names are equally close. */
    object Rank { const val SAVED = 0; const val RECORDED = 1; const val END = 2; const val GEOCODED = 3 }

    fun add(lat: Double?, lng: Double?, name: String?, rank: Int = Rank.RECORDED) {
        if (lat == null || lng == null) return
        val n = clean(name) ?: return
        names += Named(lat, lng, n, rank)
    }

    val size: Int get() = names.size

    /** The name at (or within [withinM] of) a point, or null. */
    fun nameAt(lat: Double?, lng: Double?, withinM: Double = NEAR_M): String? {
        if (lat == null || lng == null) return null
        return names
            .map { it to distanceM(lat, lng, it.lat, it.lng) }
            .filter { it.second <= withinM }
            .minWithOrNull(compareBy<Pair<Named, Double>> { it.first.rank }.thenBy { it.second })
            ?.first?.name
    }

    /**
     * Always a name for a point the book can reach: the place itself, or
     * "about 12 km from Tirupur" when the nearest known name is further
     * off. Null only when nothing is known within [FAR_M].
     */
    fun describe(lat: Double?, lng: Double?): String? {
        if (lat == null || lng == null) return null
        nameAt(lat, lng)?.let { return it }
        val nearest = names.minByOrNull { distanceM(lat, lng, it.lat, it.lng) } ?: return null
        val d = distanceM(lat, lng, nearest.lat, nearest.lng)
        if (d > FAR_M) return null
        return "about ${(d / 1000).toInt().coerceAtLeast(1)} km from ${nearest.name}"
    }

    companion object {
        /** Close enough to share a name: a town's spread, not a district's. */
        const val NEAR_M = 3_000.0
        /** Beyond this, "about N km from X" stops helping anyone. */
        const val FAR_M = 40_000.0

        /** Geocoder and stored names arrive as "Kamachipuram, Pallapalayam"; keep them, trimmed. */
        fun clean(name: String?): String? = name?.trim()?.takeIf { it.isNotEmpty() && !looksLikeCoordinates(it) }

        private fun looksLikeCoordinates(s: String) = Regex("""^-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?$""").matches(s)

        fun distanceM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(bLat - aLat)
            val dLng = Math.toRadians(bLng - aLng)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
