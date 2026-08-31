package com.trippulse.app.domain

import org.json.JSONObject

/**
 * Storage for the per-mode detail map.
 *
 * Kept next to [TravelDetails] rather than in the data layer because the shape
 * is domain knowledge: the keys are [DetailKeys], the values are always plain
 * strings, and both the timeline and the PDF read it back. Blank values are
 * dropped on the way in, so an untouched optional field costs nothing and a
 * round-trip never turns "not filled in" into "filled in with nothing".
 */
object LegDetails {

    fun toJson(values: Map<String, String>): String? {
        val kept = values.filterValues { it.isNotBlank() }
        if (kept.isEmpty()) return null
        val o = JSONObject()
        for ((k, v) in kept) o.put(k, v)
        return o.toString()
    }

    fun fromJson(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            buildMap {
                for (k in o.keys()) {
                    val v = o.optString(k, "")
                    if (v.isNotBlank()) put(k, v)
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** The one-line vehicle description for a stored leg. */
    fun summaryOf(mode: String?, json: String?): String =
        TravelDetails.summary(mode, fromJson(json))
}
