package com.trippulse.app.data.routing

import com.trippulse.app.core.Geo
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.RoutePlan
import com.trippulse.app.domain.TripConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Abstraction so the ETA engine never depends on a concrete map provider. */
interface RoutingProvider {
    /** Returns a route, or null if this provider cannot answer right now. */
    suspend fun route(origin: GeoPoint, destination: GeoPoint): RoutePlan?
}

/**
 * Google Routes API v2 (computeRoutes REST). Requires a Maps API key with the
 * Routes API enabled. Uses a FieldMask to fetch only duration, distance and the
 * encoded polyline, which keeps the response small and the quota cost low.
 */
class GoogleRoutesProvider(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient()
) : RoutingProvider {

    override suspend fun route(origin: GeoPoint, destination: GeoPoint): RoutePlan? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext null
            try {
                val body = JSONObject().apply {
                    put("origin", latLng(origin))
                    put("destination", latLng(destination))
                    put("travelMode", "DRIVE")
                    put("routingPreference", "TRAFFIC_AWARE")
                    put("polylineQuality", "OVERVIEW")
                }.toString()

                val req = Request.Builder()
                    .url("https://routes.googleapis.com/directions/v2:computeRoutes")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Goog-Api-Key", apiKey)
                    .addHeader(
                        "X-Goog-FieldMask",
                        "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline"
                    )
                    .post(body.toRequestBody(JSON))
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val json = resp.body?.string() ?: return@withContext null
                    val routes = JSONObject(json).optJSONArray("routes") ?: return@withContext null
                    if (routes.length() == 0) return@withContext null
                    val r = routes.getJSONObject(0)
                    val distanceM = r.optDouble("distanceMeters", 0.0)
                    val durationS = parseDuration(r.optString("duration", "0s"))
                    val encoded = r.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
                    val poly = if (encoded.isNotBlank()) Geo.decodePolyline(encoded) else emptyList()
                    RoutePlan(distanceM, durationS, poly, "google", System.currentTimeMillis())
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun latLng(p: GeoPoint) = JSONObject().apply {
        put("location", JSONObject().apply {
            put("latLng", JSONObject().apply {
                put("latitude", p.lat)
                put("longitude", p.lng)
            })
        })
    }

    /** Routes API returns duration like "1234s". */
    private fun parseDuration(d: String): Long =
        d.removeSuffix("s").toLongOrNull() ?: 0L

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Deterministic fallback used whenever there is no key or the network is down.
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

/** Tries Google first, falls back deterministically so ETA always resolves. */
class CompositeRouting(
    private val google: GoogleRoutesProvider?,
    private val fallback: FallbackRoutingProvider
) : RoutingProvider {
    override suspend fun route(origin: GeoPoint, destination: GeoPoint): RoutePlan {
        google?.route(origin, destination)?.let { return it }
        return fallback.route(origin, destination)
    }
}
