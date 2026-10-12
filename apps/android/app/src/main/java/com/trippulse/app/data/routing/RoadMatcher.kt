package com.trippulse.app.data.routing

import com.trippulse.app.core.Geo
import com.trippulse.app.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

/**
 * The road a stretch of fixes was driven along, from OSRM's map matching:
 * the fixes are laid on the roads they fit, and the road between them comes
 * back with its bends. Nothing here is required: every failure returns null
 * and the stretch is drawn from its fixes as before.
 *
 * The same community server the planned route comes from, the same terms.
 * A stretch is sent as at most [MAX_POINTS] fixes (the server's limit), each
 * with how sure the phone was of it, so a wandering fix is not forced onto
 * a road it was never on.
 */
class RoadMatcher(
    private val baseUrl: String = OsrmRoutingProvider.DEFAULT_BASE_URL,
    private val client: OkHttpClient = OsrmRoutingProvider.defaultClient()
) {

    /** A fix to match: where, when, and how sure. */
    data class Fix(val tMs: Long, val lat: Double, val lng: Double, val accuracyM: Double)

    /**
     * The road through [fixes] (at most [MAX_POINTS]), or null when the
     * server could not match them well enough: no answer, a low
     * confidence, or a road far longer than the fixes' own line (a
     * matching that wandered off to find a road).
     */
    suspend fun match(fixes: List<Fix>): List<GeoPoint>? = withContext(Dispatchers.IO) {
        if (fixes.size < 2 || fixes.size > MAX_POINTS) return@withContext null
        try {
            val coords = fixes.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lng, it.lat) }
            val stamps = fixes.joinToString(";") { (it.tMs / 1000).toString() }
            val radii = fixes.joinToString(";") { String.format(Locale.US, "%.0f", it.accuracyM.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)) }
            val req = Request.Builder()
                .url("$baseUrl/match/v1/driving/$coords?overview=full&geometries=polyline&steps=false&gaps=ignore&tidy=true&timestamps=$stamps&radiuses=$radii")
                .header("User-Agent", "TripPulse/1.0 (Android)")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = JSONObject(resp.body?.string() ?: return@withContext null)
                if (root.optString("code") != "Ok") return@withContext null
                val matchings = root.optJSONArray("matchings") ?: return@withContext null
                if (matchings.length() == 0) return@withContext null
                val path = ArrayList<GeoPoint>()
                var confidence = 1.0
                for (i in 0 until matchings.length()) {
                    val m = matchings.getJSONObject(i)
                    confidence = minOf(confidence, m.optDouble("confidence", 0.0))
                    val piece = Geo.decodePolyline(m.optString("geometry").orEmpty())
                    path += if (path.isEmpty()) piece else piece.drop(1)
                }
                plausible(fixes, path, confidence)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** The matched road, when it is a road the fixes could have been on. */
    internal fun plausible(fixes: List<Fix>, path: List<GeoPoint>, confidence: Double): List<GeoPoint>? {
        if (path.size < 2 || confidence < MIN_CONFIDENCE) return null
        val own = Geo.pathLengthM(fixes.map { GeoPoint(it.lat, it.lng) })
        val matched = Geo.pathLengthM(path)
        if (matched > own * MAX_STRETCH + 100.0 || matched < own * MIN_STRETCH) return null
        // Its ends are where the fixes' ends are.
        if (Geo.haversineM(path.first(), GeoPoint(fixes.first().lat, fixes.first().lng)) > END_SLACK_M) return null
        if (Geo.haversineM(path.last(), GeoPoint(fixes.last().lat, fixes.last().lng)) > END_SLACK_M) return null
        return path
    }

    companion object {
        const val MAX_POINTS = 100
        const val MIN_RADIUS_M = 10.0
        const val MAX_RADIUS_M = 50.0
        const val MIN_CONFIDENCE = 0.15
        /** A matched road at most this much longer than the fixes' own line (bends add length; a detour adds more). */
        const val MAX_STRETCH = 1.35
        const val MIN_STRETCH = 0.7
        const val END_SLACK_M = 120.0
    }
}
