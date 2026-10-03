package com.trippulse.app.domain

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Toll crossings from location alone — no SMS, no special permission.
 *
 * Toll plazas are known points (OpenStreetMap `barrier=toll_booth`, refreshed
 * by the backend). A crossing is recorded when the vehicle's path — the
 * segment between two consecutive good fixes — passes within [HIT_RADIUS_M]
 * of a booth. Checking the segment, not just the fixes, catches a plaza driven
 * through at speed between two samples.
 *
 * A plaza is mapped as many booths (one per lane, both directions), so once a
 * crossing is recorded, any booth within [SAME_PLAZA_M] is the same plaza for
 * [SAME_PLAZA_MS]: one plaza, one toll.
 *
 * Pure and unit-tested; the caller feeds fixes and records the crossing.
 */
object TollPlazas {

    data class Plaza(val id: String, val lat: Double, val lng: Double, val name: String?)

    /** A crossing already recorded on this journey, for de-duplication. */
    data class Recent(val lat: Double, val lng: Double, val atMs: Long)

    /** Booth within this distance of the vehicle's path: the vehicle went through it. */
    const val HIT_RADIUS_M = 90.0
    /** Booths this close to a recorded crossing belong to the same plaza... */
    const val SAME_PLAZA_M = 1_500.0
    /** ...for this long (a slow queue, a halt at the plaza, a U-turn). */
    const val SAME_PLAZA_MS = 30 * 60_000L
    /** Fixes further apart than this are a gap, not a path: nothing is inferred across it. */
    const val MAX_SEGMENT_M = 3_000.0
    /** A plaza's name is taken from a named booth this close when the hit one has none. */
    const val NAME_RADIUS_M = 600.0
    /** Fixes less accurate than this are not trusted to place the vehicle at a lane. */
    const val MAX_ACCURACY_M = 75.0

    /** A fixed-cell grid over the plazas for fast "what's near here" lookups. */
    class Index(plazas: List<Plaza>) {
        private val cells: Map<Long, List<Plaza>> = plazas.groupBy { cellKey(cellOf(it.lat), cellOf(it.lng)) }
        val size: Int = plazas.size

        /** Plazas within [radiusM] of the point. */
        fun near(lat: Double, lng: Double, radiusM: Double): List<Plaza> {
            val span = (radiusM / METRES_PER_DEG_LAT / CELL_DEG).toInt() + 1
            val r = cellOf(lat); val c = cellOf(lng)
            val out = ArrayList<Plaza>()
            for (dr in -span..span) for (dc in -span..span) {
                cells[cellKey(r + dr, c + dc)]?.forEach { p ->
                    if (distanceM(lat, lng, p.lat, p.lng) <= radiusM) out += p
                }
            }
            return out
        }
    }

    /**
     * The plaza the vehicle just drove through between two fixes, or null.
     * [recent] are this journey's already recorded crossings.
     */
    fun crossed(
        index: Index,
        fromLat: Double, fromLng: Double,
        toLat: Double, toLng: Double,
        atMs: Long,
        recent: List<Recent>
    ): Plaza? {
        val seg = distanceM(fromLat, fromLng, toLat, toLng)
        if (seg > MAX_SEGMENT_M) return null
        val midLat = (fromLat + toLat) / 2
        val midLng = (fromLng + toLng) / 2
        val hit = index.near(midLat, midLng, seg / 2 + HIT_RADIUS_M)
            .map { it to distanceToSegmentM(it.lat, it.lng, fromLat, fromLng, toLat, toLng) }
            .filter { it.second <= HIT_RADIUS_M }
            .minByOrNull { it.second }
            ?.first ?: return null
        val samePlaza = recent.any {
            atMs - it.atMs in 0..SAME_PLAZA_MS && distanceM(it.lat, it.lng, hit.lat, hit.lng) <= SAME_PLAZA_M
        }
        if (samePlaza) return null
        // Usually only one booth of a plaza carries its name: borrow it.
        val name = hit.name ?: index.near(hit.lat, hit.lng, NAME_RADIUS_M)
            .filter { it.name != null }
            .minByOrNull { distanceM(hit.lat, hit.lng, it.lat, it.lng) }?.name
        return hit.copy(name = name)
    }

    /** Whether a fix is good enough to take part in detection. */
    fun usable(accuracyM: Double?): Boolean = accuracyM != null && accuracyM <= MAX_ACCURACY_M

    /** A plaza on a road, with when the vehicle would have passed it. */
    data class OnPath(val plaza: Plaza, val atMs: Long)

    /**
     * The plazas a road passes through, for a stretch the phone was silent
     * on: [path] is the road between where the record stopped ([fromMs]) and
     * where it resumed ([toMs]); each plaza is timed by its share of the
     * road's length. Plazas with a crossing already [recorded] nearby are
     * left out, as is any plaza already found on this path.
     */
    fun alongPath(index: Index, path: List<GeoPoint>, fromMs: Long, toMs: Long, recorded: List<Recent>): List<OnPath> {
        if (path.size < 2 || index.size == 0 || toMs <= fromMs) return emptyList()
        val total = (1 until path.size).sumOf { distanceM(path[it - 1].lat, path[it - 1].lng, path[it].lat, path[it].lng) }
        if (total <= 0) return emptyList()
        val out = ArrayList<OnPath>()
        var run = 0.0
        for (i in 1 until path.size) {
            val a = path[i - 1]; val b = path[i]
            val seg = distanceM(a.lat, a.lng, b.lat, b.lng)
            val hit = index.near((a.lat + b.lat) / 2, (a.lng + b.lng) / 2, seg / 2 + HIT_RADIUS_M)
                .map { it to distanceToSegmentM(it.lat, it.lng, a.lat, a.lng, b.lat, b.lng) }
                .filter { it.second <= HIT_RADIUS_M }
                .minByOrNull { it.second }?.first
            // Timed by where on the road the plaza sits, not the segment's middle.
            val atMs = hit?.let { fromMs + ((run + seg * fractionAlong(it.lat, it.lng, a.lat, a.lng, b.lat, b.lng)) / total * (toMs - fromMs)).toLong() }
            run += seg
            if (hit == null || atMs == null) continue
            val known = recorded.any { distanceM(it.lat, it.lng, hit.lat, hit.lng) <= SAME_PLAZA_M } ||
                out.any { distanceM(it.plaza.lat, it.plaza.lng, hit.lat, hit.lng) <= SAME_PLAZA_M }
            if (known) continue
            val name = hit.name ?: index.near(hit.lat, hit.lng, NAME_RADIUS_M)
                .filter { it.name != null }
                .minByOrNull { distanceM(hit.lat, hit.lng, it.lat, it.lng) }?.name
            out += OnPath(hit.copy(name = name), atMs)
        }
        return out
    }

    // ---- storage: "id,lat,lng,name" lines; names may contain commas ----

    fun encode(plazas: List<Plaza>): String = plazas.joinToString("\n") {
        "${it.id},${"%.6f".format(java.util.Locale.ROOT, it.lat)},${"%.6f".format(java.util.Locale.ROOT, it.lng)},${it.name.orEmpty().replace('\n', ' ')}"
    }

    fun decode(text: String?): List<Plaza> = text.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split(',', limit = 4)
        if (parts.size < 3) return@mapNotNull null
        val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
        val lng = parts[2].toDoubleOrNull() ?: return@mapNotNull null
        Plaza(parts[0], lat, lng, parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() })
    }.toList()

    // ---- geometry: local equirectangular, exact enough over a few kilometres ----

    private const val METRES_PER_DEG_LAT = 111_320.0
    private const val CELL_DEG = 0.05

    private fun cellOf(deg: Double): Int = floor(deg / CELL_DEG).toInt()
    private fun cellKey(r: Int, c: Int): Long = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val kx = METRES_PER_DEG_LAT * cos(Math.toRadians((lat1 + lat2) / 2))
        val dx = (lng2 - lng1) * kx
        val dy = (lat2 - lat1) * METRES_PER_DEG_LAT
        return sqrt(dx * dx + dy * dy)
    }

    /** How far along the segment a→b the point's foot falls, 0 at a, 1 at b. */
    fun fractionAlong(lat: Double, lng: Double, aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val k = cos(Math.toRadians(aLat)) * METRES_PER_DEG_LAT
        val dx = (bLng - aLng) * k; val dy = (bLat - aLat) * METRES_PER_DEG_LAT
        val px = (lng - aLng) * k; val py = (lat - aLat) * METRES_PER_DEG_LAT
        val len2 = dx * dx + dy * dy
        if (len2 <= 0.0) return 0.0
        return ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
    }

    fun distanceToSegmentM(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double, bLat: Double, bLng: Double
    ): Double {
        val kx = METRES_PER_DEG_LAT * cos(Math.toRadians(pLat))
        val ax = (aLng - pLng) * kx; val ay = (aLat - pLat) * METRES_PER_DEG_LAT
        val bx = (bLng - pLng) * kx; val by = (bLat - pLat) * METRES_PER_DEG_LAT
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9) return sqrt(ax * ax + ay * ay)
        val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }
}
