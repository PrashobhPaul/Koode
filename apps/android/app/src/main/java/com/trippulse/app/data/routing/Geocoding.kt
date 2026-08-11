package com.trippulse.app.data.routing

import com.trippulse.app.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Free place-name search via OpenStreetMap's Nominatim service — no API key,
 * no billing. Used by the create-trip screen so main locations can be found
 * by typing a name instead of hunting on the map.
 *
 * Nominatim usage policy: identify the app via User-Agent and keep request
 * volume tiny (explicit search-button presses only, never per keystroke).
 */
class PlaceSearch(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    data class Place(val name: String, val point: GeoPoint)

    suspend fun search(query: String, limit: Int = 6): List<Place> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < 2) return@withContext emptyList()
            try {
                val url = "https://nominatim.openstreetmap.org/search".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("format", "jsonv2")
                    .addQueryParameter("limit", limit.toString())
                    .addQueryParameter("q", q)
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "TripPulse/3.0 (Android; family trip sharing)")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val arr = JSONArray(resp.body?.string() ?: return@withContext emptyList())
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                        val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                        Place(o.optString("display_name").ifBlank { q }, GeoPoint(lat, lon))
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
}
