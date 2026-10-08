package com.trippulse.app.data

import android.content.Context
import android.util.Log
import com.trippulse.app.core.RegionDetector
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.TransitIndex
import com.trippulse.app.domain.TransitNetwork
import com.trippulse.app.ui.Links
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Metro, water-metro, ferry, railway and coach stations for where the phone
 * is (built from OpenStreetMap by tools/transit/build_network.py).
 *
 * Only the countries around the phone are held, so the app stays light:
 * the country its mobile network reports ([RegionDetector], no permission
 * needed), and, from where it is, every country with stations within about
 * 100 km ([TransitIndex]) -- so a border town, a ferry to the next country
 * or a train across a border has the stations on both sides. India is
 * bundled (most journeys, and a first journey made offline); any other
 * country is downloaded from Koode's web host the first time it is needed,
 * kept, and refreshed monthly.
 *
 * [current] is empty until a country's data is in, so a caller that cannot
 * wait (a screen drawing) simply measures as before for those first
 * moments. Nothing here ever waits on the network.
 */
object TransitData {
    /** The text of each country held, most recently needed last. */
    private val held = LinkedHashMap<String, String>(8, 0.75f, true)
    @Volatile private var combined: TransitNetwork = TransitNetwork.EMPTY
    @Volatile private var index: TransitIndex? = null
    @Volatile private var lastCell: Pair<Int, Int>? = null
    private val fetching = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).build()
    }

    val current: TransitNetwork get() = combined

    /**
     * The network for where the phone is now: its mobile network's country
     * (India when that cannot be told) and, given [near], the countries
     * around that point. Reads what is on the phone at once and fetches what
     * is missing or a month old in the background.
     */
    fun load(context: Context, near: GeoPoint? = null): TransitNetwork {
        val app = context.applicationContext
        val wanted = LinkedHashSet<String>()
        if (near != null) {
            lastCell = TransitIndex.cellOf(near.lat, near.lng)
            wanted += indexOf(app).near(near.lat, near.lng)
        }
        wanted += runCatching { RegionDetector(context).countryCode() }.getOrNull()?.uppercase() ?: HOME
        for (cc in wanted.reversed()) ensure(app, cc)
        return combined
    }

    /**
     * As the phone moves: loads what the countries around it need when it
     * has moved into another square. Cheap when it has not.
     */
    fun follow(context: Context, p: GeoPoint) {
        if (TransitIndex.cellOf(p.lat, p.lng) == lastCell) return
        load(context, p)
    }

    fun ensure(context: Context, country: String): TransitNetwork {
        val cc = country.uppercase()
        if (!cc.matches(Regex("[A-Z]{2}"))) return combined
        val app = context.applicationContext
        synchronized(this) {
            if (held[cc] == null) readText(app, cc)?.let { put(cc, it) } else held[cc]
        }
        refreshIfDue(app, cc)
        return combined
    }

    private fun put(cc: String, text: String) {
        held[cc] = text
        // India (bundled, and most journeys) and the few countries needed
        // most recently; a traveller crossing a border keeps both sides.
        while (held.size > MAX_HELD) {
            val drop = held.keys.firstOrNull { it != HOME } ?: break
            held.remove(drop)
        }
        combined = runCatching { TransitNetwork.combine(held.values.toList()) }
            .onSuccess { Log.i(TAG, "${held.keys.joinToString()}: ${it.stations.size} stations on lines, ${it.rail.size} railway, ${it.bus.size} bus") }
            .getOrDefault(combined)
    }

    private fun dir(app: Context) = File(app.filesDir, "transit")
    private fun cached(app: Context, cc: String) = File(dir(app), "$cc.txt")

    private fun readText(app: Context, cc: String): String? =
        runCatching { cached(app, cc).takeIf { it.exists() }?.readText() }.getOrNull()
            ?: runCatching { app.assets.open("transit/$cc.txt").bufferedReader().use { it.readText() } }.getOrNull()

    /** The index of countries, from the phone (fetched monthly); empty until it is in. */
    private fun indexOf(app: Context): TransitIndex {
        index?.let { idx -> refreshIfDue(app, INDEX); return idx }
        val file = File(dir(app), "index.json")
        val idx = TransitIndex.parse(runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull())
        if (idx.countries.isNotEmpty()) index = idx
        refreshIfDue(app, INDEX)
        return idx
    }

    private fun refreshIfDue(app: Context, cc: String) {
        val prefs = app.getSharedPreferences("tp_transit", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val isIndex = cc == INDEX
        val file = if (isIndex) File(dir(app), "index.json") else cached(app, cc)
        val bundled = !isIndex && runCatching { app.assets.open("transit/$cc.txt").close(); true }.getOrDefault(false)
        val fresh = file.exists() && now - file.lastModified() < REFRESH_MS
        // A bundled country is checked for newer data at most monthly too.
        val bundledFresh = !file.exists() && bundled && now - prefs.getLong("tried_$cc", 0L) < REFRESH_MS
        val triedRecently = now - prefs.getLong("tried_$cc", 0L) < RETRY_MS
        if (fresh || bundledFresh || triedRecently) return
        // Only countries Koode has data for are asked for.
        if (!isIndex && index?.let { cc !in it.countries } == true && !bundled) return
        synchronized(fetching) { if (!fetching.add(cc)) return }
        prefs.edit().putLong("tried_$cc", now).apply()
        scope.launch {
            try {
                val path = if (isIndex) "data/transit/index.json" else "data/transit/$cc.txt"
                val req = Request.Builder().url("${Links.WEB_VIEWER}$path")
                    .header("User-Agent", "Koode (Android)").get().build()
                val text = client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                    ?: return@launch  // no file for this country: nothing rides here that Koode knows
                if (isIndex) {
                    val idx = TransitIndex.parse(text)
                    if (idx.countries.isEmpty()) return@launch
                    write(file, text)
                    index = idx
                    Log.i(TAG, "index: ${idx.countries.size} countries")
                    return@launch
                }
                if (TransitNetwork.parse(text).isEmpty) return@launch
                write(file, text)
                synchronized(this@TransitData) { if (held.containsKey(cc)) put(cc, text) }
                Log.i(TAG, "$cc downloaded")
            } catch (e: Exception) {
                Log.w(TAG, "Could not download transit data for $cc", e)
            } finally {
                synchronized(fetching) { fetching.remove(cc) }
            }
        }
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private const val TAG = "TransitData"
    /** Koode's home market: bundled, and assumed when the country cannot be told. */
    private const val HOME = "IN"
    private const val INDEX = "index"
    /** India and four more: a border town in Europe has three countries within reach. */
    private const val MAX_HELD = 5
    private const val REFRESH_MS = 30L * 24 * 3600 * 1000
    /** After a failed or empty fetch, ask again no sooner than this. */
    private const val RETRY_MS = 24L * 3600 * 1000
}
