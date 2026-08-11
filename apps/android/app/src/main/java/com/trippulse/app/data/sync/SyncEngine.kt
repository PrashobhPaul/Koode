package com.trippulse.app.data.sync

import com.trippulse.app.data.EventCodec
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.EventEntity
import com.trippulse.app.data.local.TripPulseDb
import com.trippulse.app.data.remote.FirebaseCloud
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.TripConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Two-lane synchronization (docs/spec/74-79, 27).
 *
 * Lane A (live state): highest priority, pushed frequently by TripManager so
 * viewers stay as close to live as conditions allow.
 *
 * Lane B (durable backlog): events and location samples that must never be lost.
 * On reconnect the order is deliberate — current state first (so the viewer
 * becomes live immediately), then SOS, then other critical events, then
 * location batches. A network outage affects WHEN the server receives an event,
 * never WHETHER it exists.
 */
class SyncEngine(
    private val db: TripPulseDb,
    private val cloud: FirebaseCloud,
    private val cfg: TripConfig
) {
    /** Set by TripManager: invoked once an SOS_ACTIVATED event is acknowledged. */
    var onSosDelivered: (suspend (tripId: String) -> Unit)? = null

    private val drainMutex = Mutex()
    @Volatile private var lastStatePushMs = 0L

    fun cloudAvailable(): Boolean = cloud.isAvailable()

    /** Lane A. Throttled by [TripConfig.currentStateMinIntervalS]. */
    suspend fun pushLiveState(trip: ActiveTripEntity, state: Map<String, Any?>, force: Boolean = false) {
        if (!trip.cloudEnabled || !cloud.isAvailable()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastStatePushMs < cfg.currentStateMinIntervalS * 1000) return
        lastStatePushMs = now
        cloud.pushCurrentState(trip.accessKey, state)
    }

    /** Ensure meta exists in the cloud (first connect or after re-enable). */
    suspend fun ensureMeta(trip: ActiveTripEntity, meta: Map<String, Any?>) {
        if (!trip.cloudEnabled || !cloud.isAvailable()) return
        if (trip.metaSynced) return
        if (cloud.ensureAuth() == null) return
        if (cloud.writeMeta(trip.accessKey, meta)) {
            db.tripDao().setMetaSynced(trip.tripId, true)
            cloud.armOnDisconnect(trip.accessKey)
        }
    }

    /** Overwrite meta unconditionally (e.g. after a destination change). */
    suspend fun writeMetaUpdate(trip: ActiveTripEntity, meta: Map<String, Any?>) {
        if (!trip.cloudEnabled || !cloud.isAvailable()) return
        if (cloud.ensureAuth() == null) return
        cloud.writeMeta(trip.accessKey, meta)
    }

    /**
     * Lane B. Drains pending events (priority order) then location batches.
     * Safe to call repeatedly; a mutex prevents overlapping drains.
     */
    suspend fun drain(trip: ActiveTripEntity) {
        if (!trip.cloudEnabled || !cloud.isAvailable()) return
        if (cloud.ensureAuth() == null) return
        drainMutex.withLock {
            // recover any events stuck in UPLOADING from a previous killed drain
            db.eventDao().resetUploading()

            var batch = db.eventDao().pendingByPriority(50)
            while (batch.isNotEmpty()) {
                for (e in batch) {
                    val ok = uploadEvent(trip, e)
                    if (!ok) return@withLock  // stop on hard failure; retry later
                }
                batch = db.eventDao().pendingByPriority(50)
            }

            drainLocations(trip)
        }
    }

    private suspend fun uploadEvent(trip: ActiveTripEntity, e: EventEntity): Boolean {
        val now = System.currentTimeMillis()
        db.eventDao().setStatus(e.eventId, "UPLOADING", now)
        return when (cloud.writeEvent(trip.accessKey, e.eventId, EventCodec.toCloudMap(e))) {
            FirebaseCloud.Ack.Acked, FirebaseCloud.Ack.AlreadyExists -> {
                db.eventDao().setStatus(e.eventId, "ACKED", System.currentTimeMillis())
                if (e.type == EventTypes.SOS_ACTIVATED) onSosDelivered?.invoke(trip.tripId)
                true
            }
            FirebaseCloud.Ack.Denied -> {
                // permanent: rules rejected it and it doesn't already exist
                db.eventDao().setStatus(e.eventId, "FAILED_PERMANENT", System.currentTimeMillis())
                true // don't block the rest of the queue on one bad event
            }
            FirebaseCloud.Ack.Retryable -> {
                db.eventDao().setStatusRetry(e.eventId, "FAILED_RETRYABLE", System.currentTimeMillis())
                false
            }
        }
    }

    private suspend fun drainLocations(trip: ActiveTripEntity) {
        var samples = db.locationDao().pendingBatch(trip.tripId, cfg.locationUploadBatch)
        while (samples.isNotEmpty()) {
            val map = LinkedHashMap<String, Map<String, Any?>>()
            for (s in samples) {
                map["loc_${s.tMs}_${s.autoId}"] = buildMap {
                    put("t", s.tMs)
                    put("lat", s.lat)
                    put("lng", s.lng)
                    put("accuracy", s.accuracyM)
                    s.speedMps?.let { put("speed", it) }
                    s.bearing?.let { put("bearing", it) }
                }
            }
            val ok = cloud.writeLocations(trip.accessKey, map)
            if (!ok) return
            db.locationDao().markSynced(samples.map { it.autoId })
            samples = db.locationDao().pendingBatch(trip.tripId, cfg.locationUploadBatch)
        }
        // keep the buffer bounded on long journeys
        db.locationDao().compactAcked(trip.tripId, cfg.locationCompactionThreshold)
    }
}
