package com.trippulse.app.core

import android.content.Context
import org.json.JSONObject

/**
 * The traveller's own car and bike, remembered on this phone.
 *
 * Once a private vehicle has been described (name, registration, fuel), later
 * journeys start with it filled in and collapsed to one line, so nobody types
 * their number plate before every trip. Stored per mode; never leaves the phone.
 */
object VehicleMemory {
    private fun prefs(c: Context) = c.getSharedPreferences("koode_vehicles", Context.MODE_PRIVATE)

    fun load(c: Context, mode: String): Map<String, String> = runCatching {
        val json = JSONObject(prefs(c).getString(mode, null) ?: return emptyMap())
        json.keys().asSequence().associateWith { json.optString(it) }.filterValues { it.isNotBlank() }
    }.getOrDefault(emptyMap())

    fun save(c: Context, mode: String, details: Map<String, String>) {
        val clean = details.filterValues { it.isNotBlank() }
        if (clean.isEmpty()) return
        prefs(c).edit().putString(mode, JSONObject(clean).toString()).apply()
    }
}
