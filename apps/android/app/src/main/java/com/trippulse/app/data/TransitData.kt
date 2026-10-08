package com.trippulse.app.data

import android.content.Context
import android.util.Log
import com.trippulse.app.core.RegionDetector
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
 * Metro, water-metro, ferry and railway stations for the country the phone
 * is in (built from OpenStreetMap by tools/transit/build_network.py).
 *
 * Only the country the phone is in is held, so the app stays light: India
 * is bundled (most journeys, and a first journey made offline); any other
 * country is downloaded from Koode's web host the first time the phone is
 * there, kept, and refreshed monthly. The country is the mobile network's
 * ([RegionDetector]), which needs no permission and changes at a border.
 *
 * [current] is empty until the country's data is in, so a caller that
 * cannot wait (a screen drawing) simply measures as before for those first
 * moments. Nothing here ever waits on the network.
 */
object TransitData {
    @Volatile private var loaded: Map<String, TransitNetwork> = emptyMap()
    @Volatile private var combined: TransitNetwork = TransitNetwork.EMPTY
    private val fetching = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).build()
    }

    val current: TransitNetwork get() = combined

    /**
     * The network for where the phone is now (India when the country cannot
     * be told). Reads what is on the phone at once and, when the country's
     * file is missing or a month old, fetches it in the background.
     */
    fun load(context: Context): TransitNetwork {
        val cc = runCatching { RegionDetector(context).countryCode() }.getOrNull() ?: HOME
        return ensure(context, cc)
    }

    fun ensure(context: Context, country: String): TransitNetwork {
        val cc = country.uppercase()
        if (!cc.matches(Regex("[A-Z]{2}"))) return combined
        val app = context.applicationContext
        if (cc !in loaded) synchronized(this) {
            if (cc !in loaded) read(app, cc)?.let { put(cc, it) }
        }
        refreshIfDue(app, cc)
        return combined
    }

    private fun put(cc: String, net: TransitNetwork) {
        // Home plus the country the phone is in: a traveller crossing a border
        // keeps both; nobody needs a third.
        val keep = (loaded - cc).filterKeys { it == HOME }
        loaded = keep + (cc to net)
        combined = if (loaded.size == 1) net else TransitNetwork.combine(loaded.keys.map { textOf[it] })
    }

    /** The text each loaded network was read from, for combining. */
    private val textOf = HashMap<String, String>()

    private fun cached(app: Context, cc: String) = File(File(app.filesDir, "transit"), "$cc.txt")

    private fun read(app: Context, cc: String): TransitNetwork? {
        val text = runCatching { cached(app, cc).takeIf { it.exists() }?.readText() }.getOrNull()
            ?: runCatching { app.assets.open("transit/$cc.txt").bufferedReader().use { it.readText() } }.getOrNull()
            ?: return null
        return runCatching { TransitNetwork.parse(text) }
            .onSuccess {
                textOf[cc] = text
                Log.i(TAG, "$cc: ${it.stations.size} metro and ferry stations, ${it.rail.size} railway stations")
            }
            .onFailure { Log.w(TAG, "Could not read transit data for $cc", it) }
            .getOrNull()
    }

    private fun refreshIfDue(app: Context, cc: String) {
        val prefs = app.getSharedPreferences("tp_transit", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val file = cached(app, cc)
        val bundled = runCatching { app.assets.open("transit/$cc.txt").close(); true }.getOrDefault(false)
        val fresh = file.exists() && now - file.lastModified() < REFRESH_MS
        // A bundled country is checked for newer data at most monthly too.
        val bundledFresh = !file.exists() && bundled && now - prefs.getLong("tried_$cc", 0L) < REFRESH_MS
        val triedRecently = now - prefs.getLong("tried_$cc", 0L) < RETRY_MS
        if (fresh || bundledFresh || triedRecently) return
        synchronized(fetching) { if (!fetching.add(cc)) return }
        prefs.edit().putLong("tried_$cc", now).apply()
        scope.launch {
            try {
                val req = Request.Builder().url("${Links.WEB_VIEWER}data/transit/$cc.txt")
                    .header("User-Agent", "Koode (Android)").get().build()
                val text = client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                    ?: return@launch  // no file for this country: nothing rides here that Koode knows
                val net = TransitNetwork.parse(text)
                if (net.isEmpty) return@launch
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "$cc.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                synchronized(this@TransitData) { textOf[cc] = text; put(cc, net) }
                Log.i(TAG, "$cc downloaded: ${net.stations.size} stations, ${net.rail.size} railway stations")
            } catch (e: Exception) {
                Log.w(TAG, "Could not download transit data for $cc", e)
            } finally {
                synchronized(fetching) { fetching.remove(cc) }
            }
        }
    }

    private const val TAG = "TransitData"
    /** Koode's home market: bundled, and assumed when the country cannot be told. */
    private const val HOME = "IN"
    private const val REFRESH_MS = 30L * 24 * 3600 * 1000
    /** After a failed or empty fetch, ask again no sooner than this. */
    private const val RETRY_MS = 24L * 3600 * 1000
}
