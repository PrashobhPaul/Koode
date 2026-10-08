package com.trippulse.app.data

import android.content.Context
import android.util.Log
import com.trippulse.app.domain.TransitNetwork

/**
 * The metro network and railway stations bundled with the app (built from
 * OpenStreetMap by tools/transit/build_network.py), read once per process.
 *
 * [current] is empty until [load] has run, so a caller that cannot wait
 * (a screen drawing) simply measures as before for those first moments; the
 * app graph loads it at start-up in the background.
 */
object TransitData {
    @Volatile private var loaded: TransitNetwork? = null

    val current: TransitNetwork get() = loaded ?: TransitNetwork.EMPTY

    fun load(context: Context): TransitNetwork = loaded ?: synchronized(this) {
        loaded ?: read(context).also { loaded = it }
    }

    private fun read(context: Context): TransitNetwork {
        fun asset(name: String): String? = runCatching {
            context.assets.open(name).bufferedReader().use { it.readText() }
        }.getOrNull()
        return runCatching { TransitNetwork.parse(asset("metro_network.txt"), asset("rail_stations.txt")) }
            .onSuccess { Log.i("TransitData", "${it.stations.size} metro stations, ${it.rail.size} railway stations") }
            .getOrElse { Log.w("TransitData", "Could not read transit data", it); TransitNetwork.EMPTY }
    }
}
