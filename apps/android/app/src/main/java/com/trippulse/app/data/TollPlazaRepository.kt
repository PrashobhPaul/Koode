package com.trippulse.app.data

import android.content.Context
import android.util.Log
import com.trippulse.app.data.remote.TripCloud
import com.trippulse.app.domain.TollPlazas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The toll plazas Koode counts crossings at (OpenStreetMap toll booths,
 * © OpenStreetMap contributors, ODbL).
 *
 * Offline-first: the list downloaded last, else the copy bundled into the
 * APK at release time, else none (manual "Toll crossed" still works). It is
 * refreshed from the Koode backend at most every few days, and only
 * re-downloaded when the backend has a newer version.
 */
class TollPlazaRepository(
    private val context: Context,
    private val cloud: TripCloud,
    private val scope: CoroutineScope
) {
    private val file = File(context.filesDir, "toll_plazas.csv")
    private val prefs = context.getSharedPreferences("tp_toll_plazas", Context.MODE_PRIVATE)
    private val refreshLock = Mutex()

    @Volatile private var cached: TollPlazas.Index? = null

    /** The current plaza index; loaded on first use. */
    fun index(): TollPlazas.Index = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    /** Checks the backend for a newer list, at most every [CHECK_EVERY_MS]. */
    fun refresh(force: Boolean = false) {
        scope.launch {
            refreshLock.withLock {
                val now = System.currentTimeMillis()
                if (!force && now - prefs.getLong(KEY_CHECKED, 0L) < CHECK_EVERY_MS) return@withLock
                val (version, body) = cloud.tollPlazas(currentVersion()) ?: return@withLock
                prefs.edit().putLong(KEY_CHECKED, now).apply()
                if (body.isNullOrBlank()) return@withLock
                val plazas = TollPlazas.decode(body)
                if (plazas.size < MIN_PLAUSIBLE) return@withLock  // a broken list never replaces a good one
                runCatching {
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.writeText(body)
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                    prefs.edit().putLong(KEY_VERSION, version).apply()
                    cached = TollPlazas.Index(plazas)
                    Log.i(TAG, "Toll plazas updated: ${plazas.size} (version $version)")
                }.onFailure { Log.w(TAG, "Could not store toll plazas", it) }
            }
        }
    }

    private fun currentVersion(): Long? =
        prefs.getLong(KEY_VERSION, 0L).takeIf { it > 0 && file.exists() } ?: bundledVersion()

    private fun load(): TollPlazas.Index {
        val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
            ?: runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull()
        return TollPlazas.Index(TollPlazas.decode(text))
    }

    private fun bundledVersion(): Long? = runCatching {
        context.assets.open(ASSET_VERSION).bufferedReader().use { it.readText().trim().toLong() }
    }.getOrNull()

    private companion object {
        const val TAG = "TollPlazas"
        const val ASSET = "toll_plazas.csv"
        const val ASSET_VERSION = "toll_plazas.version"
        const val KEY_VERSION = "version"
        const val KEY_CHECKED = "checked_at"
        const val CHECK_EVERY_MS = 3L * 24 * 3600 * 1000
        const val MIN_PLAUSIBLE = 500
    }
}
