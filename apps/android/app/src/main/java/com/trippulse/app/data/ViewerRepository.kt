package com.trippulse.app.data

import com.trippulse.app.core.TripCredentials
import com.trippulse.app.data.local.TripPulseDb
import com.trippulse.app.data.local.ViewerTripEntity
import com.trippulse.app.data.remote.TripCloud
import com.trippulse.app.domain.Freshness
import com.trippulse.app.domain.TripConfig
import kotlinx.coroutines.flow.Flow

/**
 * Viewer-side access (docs/spec/31, 83-84, 101-102). A viewer authenticates
 * with trip id + secret, which derive the access key (the RTDB capability).
 * Access is read-only and expires with the trip; a denied/expired read is
 * reported as such rather than leaking whether the trip exists.
 */
class ViewerRepository(
    private val db: TripPulseDb,
    private val cloud: TripCloud,
    private val cfg: TripConfig
) {
    sealed interface JoinResult {
        data class Ok(val accessKey: String, val tripId: String, val label: String) : JoinResult
        object InvalidOrExpired : JoinResult
        object CloudUnavailable : JoinResult
    }

    fun savedFlow(): Flow<List<ViewerTripEntity>> = db.viewerDao().allFlow()

    suspend fun join(tripIdInput: String, secretInput: String, viewerName: String? = null): JoinResult {
        if (!cloud.isAvailable()) return JoinResult.CloudUnavailable
        val tripId = TripCredentials.normalize(tripIdInput)
        val secret = TripCredentials.normalize(secretInput)
        val accessKey = TripCredentials.accessKey(tripId, secret)

        if (cloud.ensureAuth() == null) return JoinResult.CloudUnavailable
        val meta = cloud.fetchMeta(accessKey) ?: return JoinResult.InvalidOrExpired

        val expiresAt = (meta["expiresAt"] as? Number)?.toLong()
        if (expiresAt != null && expiresAt < System.currentTimeMillis()) {
            return JoinResult.InvalidOrExpired
        }
        val label = buildString {
            append(meta["origin"] ?: "Trip")
            append(" → ")
            append(meta["destination"] ?: "")
        }
        val now = System.currentTimeMillis()
        db.viewerDao().upsert(
            ViewerTripEntity(
                accessKey = accessKey, tripId = tripId, label = label,
                joinedAtMs = now, lastOpenedAtMs = now, expired = false
            )
        )
        // Tell the driver who is watching, then start the local alert engine
        // so this phone gets forced notifications for start/SOS/arrival.
        viewerName?.takeIf { it.isNotBlank() }?.let { cloud.registerViewer(accessKey, it) }
        cloud.subscribeTopic(accessKey)
        return JoinResult.Ok(accessKey, tripId, label)
    }

    /** Names of everyone currently following this trip (registered at join). */
    suspend fun viewers(accessKey: String): List<String> = cloud.fetchViewers(accessKey)

    fun currentStateFlow(accessKey: String): Flow<Map<String, Any?>?> = cloud.currentStateFlow(accessKey)
    fun metaFlow(accessKey: String): Flow<Map<String, Any?>?> = cloud.metaFlow(accessKey)
    fun eventsFlow(accessKey: String): Flow<List<Map<String, Any?>>> = cloud.eventsFlow(accessKey)

    suspend fun serverOffsetMs(): Long = cloud.serverTimeOffsetMs()

    suspend fun touch(accessKey: String) = db.viewerDao().touch(accessKey, System.currentTimeMillis())

    suspend fun leave(accessKey: String) {
        cloud.unsubscribeTopic(accessKey)
        db.viewerDao().markExpired(accessKey)
    }

    /** Computes freshness from the last update age and server clock skew. */
    fun freshness(state: Map<String, Any?>?, serverOffsetMs: Long): Freshness {
        if (state == null) return Freshness.UNKNOWN
        val status = state["status"] as? String
        if (status == "COMPLETED" || status == "EXPIRED") return Freshness.COMPLETED
        val lastAt = (state["lastLocationAt"] as? Number)?.toLong()
            ?: (state["updatedAt"] as? Number)?.toLong()
            ?: return Freshness.UNKNOWN
        val serverNow = System.currentTimeMillis() + serverOffsetMs
        val ageS = (serverNow - lastAt) / 1000
        val connectivity = state["connectivity"] as? String
        if (connectivity == "OFFLINE" && ageS > cfg.freshnessRecentS) return Freshness.OFFLINE
        return when {
            ageS <= cfg.freshnessLiveS -> Freshness.LIVE
            ageS <= cfg.freshnessRecentS -> Freshness.RECENT
            ageS <= cfg.freshnessStaleS -> Freshness.STALE
            else -> Freshness.OFFLINE
        }
    }
}
