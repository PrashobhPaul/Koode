package com.trippulse.app.data

import android.content.Context
import com.trippulse.app.core.Geo
import com.trippulse.app.data.local.LocationSampleEntity
import com.trippulse.app.data.local.TripLegEntity
import com.trippulse.app.data.routing.RoadMatcher
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.MapStages
import com.trippulse.app.domain.TransportCatalog
import org.json.JSONArray
import java.io.File

/**
 * A journey's path as it is drawn once the journey is over: the stretches
 * made by road laid along the roads they were driven on (see
 * [RoadMatcher]), the rest as recorded. Kept on the phone as one small
 * file per journey, read by the summary map, the report and the web
 * viewer's copy in the final live state.
 *
 * A point carries a time, spread along each matched stretch between the
 * times of the fixes it came from, so a replay still runs at the pace the
 * journey was made and a stage still ends where it ended.
 */
object DrawnPaths {

    data class Point(val tMs: Long, val lat: Double, val lng: Double) {
        val point: GeoPoint get() = GeoPoint(lat, lng)
    }

    private fun dir(app: Context) = File(app.filesDir, "drawn")
    private fun file(app: Context, tripId: String) = File(dir(app), "$tripId.json")

    fun exists(app: Context, tripId: String): Boolean = file(app, tripId).exists()

    fun load(app: Context, tripId: String): List<Point>? = runCatching {
        val f = file(app, tripId)
        if (!f.exists()) return null
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val p = arr.getJSONArray(i)
            Point(p.getLong(0), p.getDouble(1), p.getDouble(2))
        }.takeIf { it.size >= 2 }
    }.getOrNull()

    fun save(app: Context, tripId: String, points: List<Point>) {
        runCatching {
            dir(app).mkdirs()
            val arr = JSONArray()
            for (p in points) arr.put(JSONArray().put(p.tMs).put(Math.round(p.lat * 1e6) / 1e6).put(Math.round(p.lng * 1e6) / 1e6))
            file(app, tripId).writeText(arr.toString())
        }
    }

    fun delete(app: Context, tripId: String) { runCatching { file(app, tripId).delete() } }

    /** The drawn path in the shape the map and the report read the record in. */
    fun asSamples(tripId: String, points: List<Point>): List<LocationSampleEntity> = points.map {
        LocationSampleEntity(tripId = tripId, tMs = it.tMs, lat = it.lat, lng = it.lng, accuracyM = 0.0, speedMps = null, bearing = null, syncStatus = "DRAWN")
    }

    /**
     * Draws a journey from its record and stages: each stretch made by road
     * is matched to the roads, in pieces of at most [RoadMatcher.MAX_POINTS]
     * fixes thinned to the ones that shape the line; a piece the server
     * cannot match stays as recorded. Null when nothing could be drawn
     * better than the record itself.
     */
    suspend fun draw(samples: List<LocationSampleEntity>, legs: List<TripLegEntity>, matcher: RoadMatcher): List<Point>? {
        if (samples.size < 2) return null
        val pts = samples.map { GeoPoint(it.lat, it.lng) }
        val stages = MapStages.of(legs.map { it.startedAtMs to it.mode })
        val runs = MapStages.runs(samples.map { it.tMs }, stages, legs.firstOrNull()?.mode)
        val out = ArrayList<Point>()
        var improved = false
        for (r in runs) {
            val seg = samples.subList(r.from, r.to + 1)
            val drawn = if (byRoad(r.mode)) matchRun(seg, matcher)?.also { improved = true } else null
            val piece = drawn ?: seg.map { Point(it.tMs, it.lat, it.lng) }
            out += if (out.isEmpty()) piece else piece.drop(1)
        }
        return out.takeIf { improved }
    }

    /** What is drawn along the roads: anything on wheels that a map of roads knows. */
    fun byRoad(mode: String?): Boolean = when (TransportCatalog.profile(mode).key) {
        "CAR", "BIKE", "CAB", "BIKE_TAXI", "AUTO", "BUS" -> true
        else -> false
    }

    private suspend fun matchRun(seg: List<LocationSampleEntity>, matcher: RoadMatcher): List<Point>? {
        if (seg.size < 2) return null
        // The fixes that shape the line, at most MAX_POINTS a piece.
        val kept = MapStages.simplify(seg.map { GeoPoint(it.lat, it.lng) }, THIN_M).map { seg[it] }
        val pieces = kept.windowed(RoadMatcher.MAX_POINTS, RoadMatcher.MAX_POINTS - 1, partialWindows = true).filter { it.size >= 2 }
        val out = ArrayList<Point>()
        var any = false
        for (piece in pieces) {
            val road = matcher.match(piece.map { RoadMatcher.Fix(it.tMs, it.lat, it.lng, it.accuracyM) })
            val drawn = if (road != null) { any = true; timed(road, piece.first().tMs, piece.last().tMs) }
                else piece.map { Point(it.tMs, it.lat, it.lng) }
            out += if (out.isEmpty()) drawn else drawn.drop(1)
        }
        return out.takeIf { any }
    }

    /** [road] with times spread along it from [fromMs] to [toMs] by distance. */
    internal fun timed(road: List<GeoPoint>, fromMs: Long, toMs: Long): List<Point> {
        val total = Geo.pathLengthM(road).takeIf { it > 0 } ?: return road.map { Point(fromMs, it.lat, it.lng) }
        var run = 0.0
        return road.mapIndexed { i, p ->
            if (i > 0) run += Geo.haversineM(road[i - 1], p)
            Point(fromMs + ((toMs - fromMs) * (run / total)).toLong(), p.lat, p.lng)
        }
    }

    /** The path thinned for the web viewer: at most [maxPoints], the ones that shape it. */
    fun thinned(points: List<Point>, toleranceM: Double, maxPoints: Int): List<Point> {
        var tol = toleranceM
        var kept = MapStages.simplify(points.map { it.point }, tol)
        while (kept.size > maxPoints) { tol *= 2; kept = MapStages.simplify(points.map { it.point }, tol) }
        return kept.map { points[it] }
    }

    /** A fix closer than this to the line through its neighbours adds nothing the road does not. */
    private const val THIN_M = 12.0
}
