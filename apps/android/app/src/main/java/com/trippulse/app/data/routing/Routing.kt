package com.trippulse.app.data.routing

import com.trippulse.app.core.Geo
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.RoutePlan
import com.trippulse.app.domain.TripConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Abstraction so the ETA engine never depends on a concrete map provider. */
interface RoutingProvider {
    /** Returns a route, or null if this provider cannot answer right now. */
    suspend fun route(origin: GeoPoint, destination: GeoPoint): RoutePlan?
}

/**
 * OSRM routing over the public demo server (router.project-osrm.org). Free,
 * keyless and unmetered — no Google/paid API involved. Uses the standard
 * /route/v1/driving endpoint with `overview=full&geometries=polyline`, which
 * returns distance (metres), duration (seconds) and a Google-format encoded
 * polyline that our existing decoder already understands.
 *
 * The demo server is community-run with no SLA, so every failure path returns
 * null and the [FallbackRoutingProvider] keeps ETA working.
 */
class OsrmRoutingProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient()
) : RoutingProvider {

    override suspend fun route(origin: GeoPoint, destination: GeoPoint): RoutePlan? =
        withContext(Dispatchers.IO) {
            try {
                // OSRM wants lng,lat pairs.
                val coords = String.format(
                    Locale.US, "%.6f,%.6f;%.6f,%.6f",
                    origin.lng, origin.lat, destination.lng, destination.lat
                )
                val req = Request.Builder()
                    .url("$baseUrl/route/v1/driving/$coords?overview=full&geometries=polyline&steps=false")
                    .header("User-Agent", "TripPulse/1.0 (Android)")
                    .get()
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val json = resp.body?.string() ?: return@withContext null
                    val root = JSONObject(json)
                    if (root.optString("code") != "Ok") return@withContext null
                    val routes = root.optJSONArray("routes") ?: return@withContext null
                    if (routes.length() == 0) return@withContext null
                    val r = routes.getJSONObject(0)
                    val distanceM = r.optDouble("distance", 0.0)
                    val durationS = r.optDouble("duration", 0.0).toLong()
                    val encoded = r.optString("geometry").orEmpty()
                    val poly = if (encoded.isNotBlank()) Geo.decodePolyline(encoded) else emptyList()
                    RoutePlan(distanceM, durationS, poly, "osrm", System.currentTimeMillis())
                }
            } catch (_: Exception) {
                null
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://router.project-osrm.org"
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Deterministic fallback used whenever the routing service is unreachable.
 * Estimates road distance from straight-line distance and a configurable
 * average speed. Produces no polyline, so route-deviation stays disabled.
 */
class FallbackRoutingProvider(private val cfg: TripConfig) : RoutingProvider {
    override suspend fun route(origin: GeoPoint, destination: GeoPoint): RoutePlan {
        val straight = Geo.haversineM(origin, destination)
        val roadM = straight * cfg.roadDistanceFactor
        val speedMps = cfg.fallbackAvgSpeedKmh / 3.6
        val durationS = if (speedMps > 0) (roadM / speedMps).toLong() else 0L
        return RoutePlan(roadM, durationS, emptyList(), "fallback", System.currentTimeMillis())
    }
}

/** Tries OSRM first, falls back deterministically so ETA always resolves. */
class CompositeRouting(
    private val primary: RoutingProvider?,
    private val fallback: FallbackRoutingProvider
) : RoutingProvider {
    override suspend fun route(origin: GeoPoint, destination: GeoPoint): RoutePlan {
        primary?.route(origin, destination)?.let { return it }
        return fallback.route(origin, destination)
    }
}
