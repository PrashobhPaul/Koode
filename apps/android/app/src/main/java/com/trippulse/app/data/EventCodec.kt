package com.trippulse.app.data

import com.trippulse.app.data.local.EventEntity
import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.TripEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises the type-specific event [payload] to/from JSON (org.json, no
 * kotlinx-serialization dependency) and converts between the domain [TripEvent]
 * and the persisted [EventEntity].
 */
object EventCodec {

    fun payloadToJson(payload: Map<String, Any?>): String {
        val o = JSONObject()
        for ((k, v) in payload) o.put(k, wrap(v))
        return o.toString()
    }

    fun payloadFromJson(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(json)
            val map = LinkedHashMap<String, Any?>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = unwrap(o.get(k))
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun wrap(v: Any?): Any {
        return when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val o = JSONObject()
                for ((k, mv) in v) o.put(k.toString(), wrap(mv))
                o
            }
            is List<*> -> {
                val a = JSONArray()
                for (item in v) a.put(wrap(item))
                a
            }
            else -> v
        }
    }

    private fun unwrap(v: Any?): Any? {
        return when (v) {
            JSONObject.NULL -> null
            is JSONObject -> {
                val map = LinkedHashMap<String, Any?>()
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = unwrap(v.get(k))
                }
                map
            }
            is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
            else -> v
        }
    }

    fun toEntity(e: TripEvent, nowMs: Long, sensitive: Boolean): EventEntity = EventEntity(
        eventId = e.eventId,
        tripId = e.tripId,
        type = e.type,
        eventTimeMs = e.eventTimeMs,
        receivedAtMs = nowMs,
        lat = e.lat,
        lng = e.lng,
        accuracyM = e.accuracyM,
        source = e.source.name,
        payloadJson = payloadToJson(e.payload),
        schemaVersion = e.schemaVersion,
        priority = EventTypes.priorityFor(e.type),
        syncStatus = "PENDING",
        retryCount = 0,
        lastAttemptAtMs = null,
        sensitive = sensitive
    )

    fun toDomain(e: EventEntity): TripEvent = TripEvent(
        eventId = e.eventId,
        tripId = e.tripId,
        type = e.type,
        eventTimeMs = e.eventTimeMs,
        lat = e.lat,
        lng = e.lng,
        accuracyM = e.accuracyM,
        source = runCatching { EventSource.valueOf(e.source) }.getOrDefault(EventSource.SYSTEM_INFERRED),
        payload = payloadFromJson(e.payloadJson),
        schemaVersion = e.schemaVersion
    )

    /** Cloud-facing JSON for an event record (docs/spec/13). */
    fun toCloudMap(e: EventEntity): Map<String, Any?> = buildMap {
        put("type", e.type)
        put("eventTime", e.eventTimeMs)
        put("receivedAt", e.receivedAtMs)
        if (e.lat != null) put("lat", e.lat)
        if (e.lng != null) put("lng", e.lng)
        if (e.accuracyM != null) put("accuracy", e.accuracyM)
        put("source", e.source)
        put("schemaVersion", e.schemaVersion)
        // sensitive event content is not published; only a marker is
        if (e.sensitive) {
            put("sensitive", true)
        } else {
            put("payload", payloadFromJson(e.payloadJson))
        }
    }
}
