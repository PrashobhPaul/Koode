package com.trippulse.app.domain

import org.json.JSONObject

/**
 * The last known picture of a journey this phone follows: who is travelling,
 * where they are, where they're going and when they should get there.
 *
 * The follow service writes one after every successful read, so Home can
 * draw the person on its map and say "42 km left · arriving about 6:40 pm"
 * instantly, without a network call — and still say something honest when
 * the network is the thing that's missing ([lastLocationAtMs] dates it).
 */
data class FollowSnapshot(
    val ownerName: String?,
    val mode: String?,
    val lat: Double?,
    val lng: Double?,
    val originLat: Double?,
    val originLng: Double?,
    val destLat: Double?,
    val destLng: Double?,
    val destination: String?,
    val distanceRemainingM: Double?,
    val etaLikelyMs: Long?,
    val lastLocationAtMs: Long?,
    val sosActive: Boolean
) {
    val hasPosition: Boolean get() = lat != null && lng != null

    fun encode(): String = JSONObject().apply {
        ownerName?.let { put("ownerName", it) }
        mode?.let { put("mode", it) }
        lat?.let { put("lat", it) }
        lng?.let { put("lng", it) }
        originLat?.let { put("originLat", it) }
        originLng?.let { put("originLng", it) }
        destLat?.let { put("destLat", it) }
        destLng?.let { put("destLng", it) }
        destination?.let { put("destination", it) }
        distanceRemainingM?.let { put("distanceRemainingM", it) }
        etaLikelyMs?.let { put("etaLikely", it) }
        lastLocationAtMs?.let { put("lastLocationAt", it) }
        put("sosActive", sosActive)
    }.toString()

    companion object {
        /** Built from a followed journey's server meta + live state. */
        fun from(meta: Map<String, Any?>, state: Map<String, Any?>): FollowSnapshot {
            fun d(m: Map<String, Any?>, k: String) = (m[k] as? Number)?.toDouble()
            fun l(m: Map<String, Any?>, k: String) = (m[k] as? Number)?.toLong()
            return FollowSnapshot(
                ownerName = (meta["ownerName"] as? String)?.trim()?.ifBlank { null },
                mode = meta["transportMode"] as? String,
                lat = d(state, "lat"), lng = d(state, "lng"),
                originLat = d(meta, "originLat"), originLng = d(meta, "originLng"),
                destLat = d(meta, "destLat"), destLng = d(meta, "destLng"),
                destination = (meta["destination"] as? String)?.ifBlank { null },
                distanceRemainingM = d(state, "distanceRemainingM"),
                etaLikelyMs = l(state, "etaLikely"),
                lastLocationAtMs = l(state, "lastLocationAt") ?: l(state, "updatedAt"),
                sosActive = state["sosActive"] as? Boolean ?: false
            )
        }

        fun decode(raw: String?): FollowSnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                fun d(k: String) = if (o.has(k)) o.optDouble(k).takeUnless { it.isNaN() } else null
                fun l(k: String) = if (o.has(k)) o.optLong(k) else null
                fun s(k: String) = if (o.has(k)) o.optString(k).ifBlank { null } else null
                FollowSnapshot(
                    ownerName = s("ownerName"), mode = s("mode"),
                    lat = d("lat"), lng = d("lng"),
                    originLat = d("originLat"), originLng = d("originLng"),
                    destLat = d("destLat"), destLng = d("destLng"),
                    destination = s("destination"),
                    distanceRemainingM = d("distanceRemainingM"),
                    etaLikelyMs = l("etaLikely"),
                    lastLocationAtMs = l("lastLocationAt"),
                    sosActive = o.optBoolean("sosActive", false)
                )
            }.getOrNull()
        }
    }
}
