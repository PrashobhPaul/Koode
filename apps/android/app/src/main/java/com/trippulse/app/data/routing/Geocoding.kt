package com.trippulse.app.data.routing

import com.trippulse.app.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Finding a place, the easy way — with no API key and no billing.
 *
 * One search box understands three kinds of input:
 *  1. **A place name**, searched as you type via Photon (komoot's free,
 *     OpenStreetMap-based geocoder, built for autocomplete). Nominatim is kept
 *     only as a fallback when Photon can't be reached, because its usage policy
 *     forbids per-keystroke search.
 *  2. **A Google Maps link**, pasted or shared into Koode from the Google Maps
 *     app. See [GoogleMapsLink].
 *  3. **Raw coordinates** such as `10.5276, 76.2144`.
 */
class PlaceSearch(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    /** [detail] is the locality line shown under the name in result lists. */
    data class Place(val name: String, val point: GeoPoint, val detail: String = "")

    suspend fun search(query: String, limit: Int = 8, near: GeoPoint? = null): List<Place> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < 2) return@withContext emptyList()
            GoogleMapsLink.coordinates(q)?.let { return@withContext listOf(Place("Pinned location", it, "%.5f, %.5f".format(it.lat, it.lng))) }
            if (GoogleMapsLink.extractUrl(q) != null) return@withContext listOfNotNull(resolveLink(q))
            photon(q, limit, near) ?: nominatim(q, limit)
        }

    /**
     * Turns text shared from Google Maps into a place.
     *
     * Google shares either a full URL (coordinates inside) or a short
     * `maps.app.goo.gl` link that redirects to one. When the final URL still
     * carries no coordinates — Google sometimes shares a place by name only —
     * the place name is looked up instead, and the result says so.
     */
    suspend fun resolveLink(sharedText: String, near: GeoPoint? = null): Place? = withContext(Dispatchers.IO) {
        val url = GoogleMapsLink.extractUrl(sharedText) ?: return@withContext null
        val sharedName = GoogleMapsLink.sharedName(sharedText)

        var parsed = GoogleMapsLink.parse(url)
        if (parsed.point == null) {
            val expanded = expand(url)
            if (expanded != null) parsed = GoogleMapsLink.parse(expanded)
        }
        val name = sharedName ?: parsed.name
        parsed.point?.let { return@withContext Place(name ?: "Place from Google Maps", it, "From Google Maps") }
        // Google's links for named places ("Copy link", Share) usually carry
        // only the name and address — the coordinates are drawn in by script
        // on Google's side. So look the address up: most specific first, then
        // ever broader, and say plainly when only the area could be found.
        name?.let { n ->
            val shown = GoogleMapsLink.displayName(n)
            GoogleMapsLink.lookupCandidates(n).forEachIndexed { i, candidate ->
                val hit = (photon(candidate, 1, near) ?: if (i == 0) nominatim(candidate, 1) else null)?.firstOrNull()
                    ?: return@forEachIndexed
                return@withContext hit.copy(
                    name = shown,
                    detail = if (i == 0) "Matched by address — check the pin"
                    else "Only the area matched ($candidate) — move the pin to the exact spot"
                )
            }
        }
        null
    }

    /** Follows a short link's redirects and returns the final URL. */
    private fun expand(url: String): String? = try {
        val req = Request.Builder().url(url)
            // A browser user agent gets the ordinary redirect rather than an app-install page.
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            .get().build()
        client.newCall(req).execute().use { resp -> resp.request.url.toString() }
    } catch (_: Exception) { null }

    private fun photon(q: String, limit: Int, near: GeoPoint?): List<Place>? = try {
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("lang", "en")
            .apply {
                if (near != null) {
                    addQueryParameter("lat", "%.4f".format(near.lat))
                    addQueryParameter("lon", "%.4f".format(near.lng))
                }
            }.build()
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val features = JSONObject(resp.body?.string() ?: return null).optJSONArray("features") ?: return emptyList()
            (0 until features.length()).mapNotNull { i -> photonPlace(features.optJSONObject(i)) }
                .distinctBy { "${it.name}|${it.detail}" }
        }
    } catch (_: Exception) { null }

    private fun photonPlace(f: JSONObject?): Place? {
        f ?: return null
        val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val lng = coords.optDouble(0, Double.NaN); val lat = coords.optDouble(1, Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return null
        val p = f.optJSONObject("properties") ?: JSONObject()
        fun s(k: String) = p.optString(k).takeIf { it.isNotBlank() }
        val street = listOfNotNull(s("housenumber"), s("street")).joinToString(" ").ifBlank { null }
        val name = s("name") ?: street ?: s("city") ?: return null
        val detail = listOfNotNull(
            street.takeIf { s("name") != null },
            s("district") ?: s("locality"),
            s("city").takeIf { it != name },
            s("state"),
            s("country")
        ).distinct().joinToString(", ")
        return Place(name, GeoPoint(lat, lng), detail)
    }

    private fun nominatim(q: String, limit: Int): List<Place> = try {
        val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("q", q)
            .build()
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONArray(resp.body?.string() ?: return emptyList())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                val parts = o.optString("display_name").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                Place(parts.firstOrNull() ?: q, GeoPoint(lat, lon), parts.drop(1).take(3).joinToString(", "))
            }
        }
    } catch (_: Exception) { emptyList() }

    private companion object {
        const val USER_AGENT = "Koode/6.5 (Android; family journey sharing; github.com/PrashobhPaul/Koode)"
    }
}

/**
 * Pure parsing of Google Maps share links — no network, fully unit-tested.
 *
 * Coordinates are taken in order of how precisely they mark *the place*:
 * the `!3d…!4d…` pin data first, then query parameters (`q`, `ll`,
 * `destination`, `center`, `query`), then the `@lat,lng` camera centre,
 * which is where the map was looking rather than the pin itself.
 */
object GoogleMapsLink {

    data class Parsed(val point: GeoPoint?, val name: String?)

    private val URL_RE = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val PIN_RE = Regex("""!3d(-?\d{1,3}\.\d+)!4d(-?\d{1,3}\.\d+)""")
    private val AT_RE = Regex("""@(-?\d{1,3}\.\d+),(-?\d{1,3}\.\d+)""")
    private val PAIR_RE = Regex("""^\s*(-?\d{1,3}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)\s*$""")
    private val PLACE_RE = Regex("""/maps/place/([^/@?]+)""")

    /** The first Google Maps URL in [text], if any. */
    fun extractUrl(text: String): String? =
        URL_RE.findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }.firstOrNull { isGoogleMaps(it) }

    fun isGoogleMaps(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty()
        return host == "maps.app.goo.gl" || host == "goo.gl" && path.startsWith("/maps") ||
            host == "g.co" || host.startsWith("maps.google.") ||
            (host.contains("google.") && path.startsWith("/maps")) ||
            host == "consent.google.com"
    }

    /**
     * What to look up for a place Google named but didn't locate: the full
     * text, then the address without the leading business name, then ever
     * broader address parts, and finally the bare name. Commas and the Arabic
     * comma both split parts.
     */
    fun lookupCandidates(text: String): List<String> {
        val parts = text.split(',', '\u060C').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        return (listOf(text.trim()) + (1 until parts.size).map { parts.drop(it).joinToString(", ") } + parts.first())
            .distinct()
    }

    /** The place's own name — the first part of "LuLu Mall, NH 66, Edappally, Kochi". */
    fun displayName(text: String): String =
        text.split(',', '\u060C').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: text.trim()

    /** "10.5276, 76.2144" typed or pasted directly. */
    fun coordinates(text: String): GeoPoint? =
        PAIR_RE.matchEntire(text)?.let { valid(it.groupValues[1], it.groupValues[2]) }

    /**
     * The place name Google puts ahead of the link when sharing, e.g.
     * `"LuLu Mall\nhttps://maps.app.goo.gl/…"`. Null when the text is only a link.
     */
    fun sharedName(text: String): String? {
        val url = URL_RE.find(text)?.range?.first ?: return null
        return text.substring(0, url).lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            ?.trim('"', '\u201C', '\u201D')?.takeIf { it.length in 2..120 }
    }

    fun parse(rawUrl: String): Parsed {
        val url = unwrapConsent(rawUrl)
        val decoded = decode(url)
        val name = PLACE_RE.find(url)?.groupValues?.get(1)?.let { decode(it.replace('+', ' ')).trim() }
            ?.takeIf { it.isNotBlank() && coordinates(it) == null }

        PIN_RE.findAll(decoded).lastOrNull()?.let { m ->
            valid(m.groupValues[1], m.groupValues[2])?.let { return Parsed(it, name) }
        }
        val http = url.toHttpUrlOrNull()
        var textQuery: String? = null
        if (http != null) {
            for (key in listOf("q", "ll", "destination", "center", "query", "daddr")) {
                val v = http.queryParameter(key)?.trim() ?: continue
                val pair = coordinates(v.removePrefix("loc:"))
                if (pair != null) return Parsed(pair, name)
                if (textQuery == null && v.isNotBlank() && key in setOf("q", "query", "destination", "daddr")) textQuery = v
            }
        }
        AT_RE.find(decoded)?.let { m -> valid(m.groupValues[1], m.groupValues[2])?.let { return Parsed(it, name ?: textQuery) } }
        return Parsed(null, name ?: textQuery)
    }

    /** EU users are bounced via consent.google.com with the real URL in `continue`. */
    private fun unwrapConsent(url: String): String {
        val http = url.toHttpUrlOrNull() ?: return url
        return if (http.host.startsWith("consent.")) http.queryParameter("continue") ?: url else url
    }

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun valid(lat: String, lng: String): GeoPoint? {
        val la = lat.toDoubleOrNull() ?: return null
        val lo = lng.toDoubleOrNull() ?: return null
        if (la !in -90.0..90.0 || lo !in -180.0..180.0 || (la == 0.0 && lo == 0.0)) return null
        return GeoPoint(la, lo)
    }
}
