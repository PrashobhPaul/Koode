package com.trippulse.app.data

import com.trippulse.app.core.SettingsStore
import com.trippulse.app.core.TripCredentials
import com.trippulse.app.data.local.TripPulseDb
import com.trippulse.app.data.local.ViewerTripEntity
import com.trippulse.app.data.remote.TripCloud
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Freshness
import com.trippulse.app.domain.JourneyStatus
import com.trippulse.app.domain.TripConfig
import kotlinx.coroutines.flow.Flow

/**
 * Viewer-side access. A follower reaches a journey either with the journey id
 * plus its passcode (an instant capability) or with the journey id alone, in
 * which case the traveller approves them by name.
 *
 * The one rule this file exists to protect: **a journey is over only when its
 * traveller says so.** Every other outcome — no network, an unreachable
 * server, a capability that has not been granted yet, a phone that has gone
 * flat in a tunnel — is reported as "we haven't heard for a while". Telling a
 * waiting parent "Journey ended" because a REST call returned null is exactly
 * the failure this app exists to avoid.
 */
class ViewerRepository(
    private val db: TripPulseDb,
    private val cloud: TripCloud,
    private val settings: SettingsStore,
    private val cfg: TripConfig
) {
    sealed interface JoinResult {
        data class Ok(val accessKey: String, val tripId: String, val label: String) : JoinResult
        /** The credentials don't match a live journey. */
        object InvalidCredentials : JoinResult
        /** We could not reach the service at all — say so, don't blame the user. */
        object Unreachable : JoinResult
        object CloudUnavailable : JoinResult
    }

    fun savedFlow(): Flow<List<ViewerTripEntity>> = db.viewerDao().allFlow()

    /**
     * Follow a journey with id + passcode.
     *
     * The two failure modes are kept strictly apart, because they need
     * different words on screen: a wrong passcode is the follower's to fix, an
     * unreachable server is not.
     */
    suspend fun join(tripIdInput: String, secretInput: String, viewerName: String? = null): JoinResult {
        if (!cloud.isAvailable()) return JoinResult.CloudUnavailable
        val tripId = TripCredentials.resolve(tripIdInput) ?: return JoinResult.InvalidCredentials
        val secret = TripCredentials.normalize(secretInput)
        val accessKey = TripCredentials.accessKey(tripId, secret)

        if (cloud.ensureAuth() == null) return JoinResult.CloudUnavailable
        val meta = cloud.fetchMeta(accessKey)
        if (meta == null) {
            // Distinguish "wrong credentials" from "no signal" before saying
            // anything to the follower.
            return if (cloud.serverReachable()) JoinResult.InvalidCredentials else JoinResult.Unreachable
        }

        val label = labelOf(meta)
        val now = System.currentTimeMillis()
        db.viewerDao().upsert(
            ViewerTripEntity(
                accessKey = accessKey, tripId = tripId, label = label,
                joinedAtMs = now, lastOpenedAtMs = now, expired = false,
                endedAtMs = null, lastSeenAtMs = now, unreachableSinceMs = null
            )
        )
        // Tell the traveller who is watching, then start the local alert engine
        // so this phone gets forced notifications for start/SOS/arrival.
        viewerName?.takeIf { it.isNotBlank() }?.let { cloud.registerViewer(accessKey, it) }
        cloud.subscribeTopic(accessKey)
        return JoinResult.Ok(accessKey, tripId, label)
    }

    /** Names of everyone currently following this journey. */
    suspend fun viewers(accessKey: String): List<String> = cloud.fetchViewers(accessKey)

    sealed interface IdJoinResult {
        data class Ok(val ref: String, val label: String) : IdJoinResult
        object Pending : IdJoinResult
        object Denied : IdJoinResult
        object NotFound : IdJoinResult
        object Unreachable : IdJoinResult
        object CloudUnavailable : IdJoinResult
    }

    /**
     * Journey-id-only follow: no passcode. This device requests access with the
     * follower's name; the traveller must approve before anything is readable.
     */
    suspend fun requestJoinById(tripIdInput: String, viewerName: String): IdJoinResult {
        if (!cloud.isAvailable()) return IdJoinResult.CloudUnavailable
        val tripId = TripCredentials.resolve(tripIdInput) ?: return IdJoinResult.NotFound
        val status = cloud.requestJoin(tripId, viewerName.ifBlank { "Viewer" })
            ?: return IdJoinResult.Unreachable
        return mapStatus(tripId, status)
    }

    /** Polled while the join screen shows "waiting for approval". */
    suspend fun pollJoinStatus(tripIdInput: String): IdJoinResult {
        if (!cloud.isAvailable()) return IdJoinResult.CloudUnavailable
        val tripId = TripCredentials.resolve(tripIdInput) ?: return IdJoinResult.NotFound
        val status = cloud.joinStatus(tripId) ?: return IdJoinResult.Unreachable
        return mapStatus(tripId, status)
    }

    private suspend fun mapStatus(tripId: String, status: String): IdJoinResult = when (status) {
        "APPROVED" -> {
            val meta = cloud.fetchMeta(tripId)
            val label = meta?.let { labelOf(it) } ?: tripId
            val now = System.currentTimeMillis()
            db.viewerDao().upsert(
                ViewerTripEntity(
                    accessKey = tripId, tripId = tripId, label = label,
                    joinedAtMs = now, lastOpenedAtMs = now, expired = false,
                    endedAtMs = null, lastSeenAtMs = now, unreachableSinceMs = null
                )
            )
            cloud.subscribeTopic(tripId)
            IdJoinResult.Ok(tripId, label)
        }
        "PENDING", "NONE" -> IdJoinResult.Pending
        "DENIED" -> IdJoinResult.Denied
        "NOT_FOUND" -> IdJoinResult.NotFound
        else -> IdJoinResult.Unreachable
    }

    private fun labelOf(meta: Map<String, Any?>): String = buildString {
        append(meta["origin"] ?: "Journey")
        append(" → ")
        append(meta["destination"] ?: "")
    }.trim().removeSuffix("→").trim()

    /** The follower removes a journey from their own list. Local only. */
    suspend fun unfollow(ref: String) {
        cloud.unsubscribeTopic(ref)
        db.viewerDao().deleteByKey(ref)
    }

    // ---- live reads -------------------------------------------------------

    fun currentStateFlow(accessKey: String): Flow<Map<String, Any?>?> =
        cloud.currentStateFlow(accessKey) { st -> statePollMs(st) }

    fun metaFlow(accessKey: String): Flow<Map<String, Any?>?> =
        cloud.metaFlow(accessKey) { metaPollMs() }

    fun eventsFlow(accessKey: String): Flow<List<Map<String, Any?>>> =
        cloud.eventsFlow(accessKey) { st -> eventsPollMs(st) }

    /**
     * How long to wait before asking the server again.
     *
     * Polling is the follower's battery bill, and it should be proportional to
     * how fast the picture can actually change. A moving car earns a frequent
     * check; a train resting overnight, or a journey the traveller already
     * ended, earns almost none.
     */
    private fun statePollMs(state: Map<String, Any?>?): Long {
        val refresh = settings.current.viewerRefresh
        val status = state?.get("status") as? String
        val ended = state?.get("endedByOwner") as? Boolean ?: false
        if (ended || status == JourneyStatus.COMPLETED.name || status == JourneyStatus.EXPIRED.name) {
            return FINISHED_POLL_MS
        }
        val idle = status == JourneyStatus.OVERNIGHT.name || status == JourneyStatus.PAUSED.name ||
            status == JourneyStatus.STOPPED.name || status == JourneyStatus.LONG_STOP.name ||
            status == JourneyStatus.ARRIVED.name
        return (if (idle) refresh.idleS else refresh.activeS) * 1000
    }

    private fun metaPollMs(): Long = (settings.current.viewerRefresh.idleS * 4) * 1000

    private fun eventsPollMs(events: List<Map<String, Any?>>?): Long {
        // The timeline is a slower-moving thing than the map dot; three times
        // the state cadence keeps it feeling live without tripling the cost.
        val base = settings.current.viewerRefresh.activeS * 3
        return (if (events.isNullOrEmpty()) base * 2 else base) * 1000
    }

    suspend fun serverOffsetMs(): Long = cloud.serverTimeOffsetMs()

    suspend fun touch(accessKey: String) = db.viewerDao().touch(accessKey, System.currentTimeMillis())

    // ---- "is it over?" ----------------------------------------------------

    /**
     * The single place that decides a followed journey has ended.
     *
     * Requires a positive statement from the traveller's own device: either
     * their live state says COMPLETED / endedByOwner, or a TRIP_COMPLETED event
     * is present in the journey's log. Absence of data is never evidence.
     */
    fun isEndedByOwner(state: Map<String, Any?>?, events: List<Map<String, Any?>>?): Boolean {
        if (state?.get("endedByOwner") as? Boolean == true) return true
        val status = state?.get("status") as? String
        if (status == JourneyStatus.COMPLETED.name) return true
        return events?.any { (it["type"] as? String) == EventTypes.TRIP_COMPLETED } == true
    }

    /** Records that the traveller ended this journey. */
    suspend fun markEnded(ref: String, atMs: Long = System.currentTimeMillis()) =
        db.viewerDao().markEndedByOwner(ref, atMs)

    suspend fun markSeen(ref: String) =
        db.viewerDao().markSeen(ref, System.currentTimeMillis())

    suspend fun markUnreachable(ref: String) =
        db.viewerDao().markUnreachable(ref, System.currentTimeMillis())

    /** Computes freshness from the last update age and server clock skew. */
    fun freshness(state: Map<String, Any?>?, serverOffsetMs: Long): Freshness {
        if (state == null) return Freshness.UNKNOWN
        val status = state["status"] as? String
        if (status == JourneyStatus.COMPLETED.name || status == JourneyStatus.EXPIRED.name) {
            return Freshness.COMPLETED
        }
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

    private companion object {
        /** A finished journey still refreshes, just rarely — 5 minutes. */
        const val FINISHED_POLL_MS = 300_000L
    }
}
