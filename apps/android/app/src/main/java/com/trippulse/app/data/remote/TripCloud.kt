package com.trippulse.app.data.remote

import android.content.Context
import com.trippulse.app.BuildConfig
import com.trippulse.app.data.EventCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Supabase (Postgres + PostgREST) transport — open-source stack, free tier,
 * plain HTTPS, no SDKs, no auth service. This is a transport only: the durable
 * source of truth is the local Room store. Every method is a no-op / returns
 * unavailable when Supabase is not configured, so the app runs fully in LOCAL
 * mode until supabase.properties is filled in.
 *
 * Security model (enforced in supabase/schema.sql, not here):
 *  - access_key (SHA-256 of tripId:secret) grants read-only access while the
 *    trip is not expired.
 *  - owner_token is generated on this device at first write and kept in
 *    app-private storage; every write RPC verifies it, so only the driver
 *    device that created a trip can write or modify it.
 */
class TripCloud(private val appContext: Context) {

    sealed interface Ack {
        object Acked : Ack
        object AlreadyExists : Ack
        object Denied : Ack
        object Retryable : Ack
    }

    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isAvailable(): Boolean = baseUrl.isNotBlank() && anonKey.isNotBlank()

    /** No auth service in this design — the capability tokens ARE the auth. */
    suspend fun ensureAuth(): String? = if (isAvailable()) "capability" else null

    /** No server-side onDisconnect over REST; the viewer freshness model
     *  (LIVE/RECENT/STALE/OFFLINE from lastLocationAt age) covers this. */
    fun armOnDisconnect(accessKey: String) {}

    // -----------------------------------------------------------------------
    // Owner token: random, generated once per trip on the driver device,
    // never shared and never displayed. Held in app-private preferences.
    // -----------------------------------------------------------------------

    private fun ownerToken(accessKey: String): String {
        val prefs = appContext.getSharedPreferences("tp_owner_tokens", Context.MODE_PRIVATE)
        prefs.getString(accessKey, null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(accessKey, token).apply()
        return token
    }

    // -----------------------------------------------------------------------
    // RPC plumbing
    // -----------------------------------------------------------------------

    private val json = "application/json; charset=utf-8".toMediaType()

    /** POSTs /rest/v1/rpc/{fn}; returns the raw body, or null on any failure. */
    private suspend fun rpc(fn: String, args: Map<String, Any?>): String? =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) return@withContext null
            try {
                val req = Request.Builder()
                    .url("$baseUrl/rest/v1/rpc/$fn")
                    .addHeader("apikey", anonKey)
                    .addHeader("Authorization", "Bearer $anonKey")
                    .addHeader("Content-Type", "application/json")
                    .post(EventCodec.payloadToJson(args).toRequestBody(json))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else (resp.body?.string() ?: "")
                }
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun rpcBool(fn: String, args: Map<String, Any?>): Boolean =
        rpc(fn, args)?.trim() == "true"

    /** For RPCs returning text — PostgREST wraps scalars in JSON quotes. */
    private suspend fun rpcText(fn: String, args: Map<String, Any?>): String? =
        rpc(fn, args)?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }

    private suspend fun rpcObject(fn: String, args: Map<String, Any?>): Map<String, Any?>? {
        val body = rpc(fn, args)?.trim() ?: return null
        if (body.isBlank() || body == "null") return null
        return runCatching { EventCodec.payloadFromJson(body) }.getOrNull()
    }

    private suspend fun rpcArray(fn: String, args: Map<String, Any?>): List<Map<String, Any?>>? {
        val body = rpc(fn, args)?.trim() ?: return null
        if (body.isBlank() || body == "null") return null
        return runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                (arr.get(i) as? JSONObject)?.let { EventCodec.payloadFromJson(it.toString()) }
            }
        }.getOrNull()
    }

    // -----------------------------------------------------------------------
    // Driver-side writes (owner_token verified server-side)
    // -----------------------------------------------------------------------

    suspend fun writeMeta(accessKey: String, meta: Map<String, Any?>): Boolean {
        val expiresMs = (meta["expiresAt"] as? Number)?.toLong()
            ?: System.currentTimeMillis() + 36L * 3600 * 1000
        return rpcBool(
            "tp_upsert_meta",
            mapOf(
                "p_access_key" to accessKey,
                "p_owner_token" to ownerToken(accessKey),
                "p_meta" to meta,
                "p_expires_ms" to expiresMs
            )
        )
    }

    suspend fun setExpiry(accessKey: String, expiresAtMs: Long): Boolean = rpcBool(
        "tp_set_expiry",
        mapOf(
            "p_access_key" to accessKey,
            "p_owner_token" to ownerToken(accessKey),
            "p_expires_ms" to expiresAtMs
        )
    )

    /** Overwrites the live-state row. This is the freshness source of truth. */
    suspend fun pushCurrentState(accessKey: String, state: Map<String, Any?>): Boolean = rpcBool(
        "tp_push_state",
        mapOf(
            "p_access_key" to accessKey,
            "p_owner_token" to ownerToken(accessKey),
            "p_state" to state
        )
    )

    /**
     * Idempotent, write-once event append (INSERT .. ON CONFLICT DO NOTHING
     * server-side), which is what makes offline retry/re-sync safe.
     */
    suspend fun writeEvent(accessKey: String, eventId: String, value: Map<String, Any?>): Ack {
        val eventTime = (value["eventTime"] as? Number)?.toLong() ?: System.currentTimeMillis()
        return when (rpcText(
            "tp_append_event",
            mapOf(
                "p_access_key" to accessKey,
                "p_owner_token" to ownerToken(accessKey),
                "p_event_id" to eventId,
                "p_event" to value,
                "p_event_time" to eventTime
            )
        )) {
            "ACKED" -> Ack.Acked
            "EXISTS" -> Ack.AlreadyExists
            "DENIED" -> Ack.Denied
            else -> Ack.Retryable
        }
    }

    /** Batch location samples in a single RPC. */
    suspend fun writeLocations(accessKey: String, samples: Map<String, Map<String, Any?>>): Boolean {
        if (samples.isEmpty()) return true
        return rpcBool(
            "tp_append_locations",
            mapOf(
                "p_access_key" to accessKey,
                "p_owner_token" to ownerToken(accessKey),
                "p_samples" to samples
            )
        )
    }

    // -----------------------------------------------------------------------
    // Viewer identity: a permanent random token for THIS phone. Combined with
    // owner approval it replaces passwords for trip-id-only viewing.
    // -----------------------------------------------------------------------

    fun viewerToken(): String {
        val prefs = appContext.getSharedPreferences("tp_viewer_identity", Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("token", token).apply()
        return token
    }

    /** Ask to follow a trip with only its trip id + this viewer's name.
     *  Returns PENDING / APPROVED / DENIED / NOT_FOUND (or null offline). */
    suspend fun requestJoin(tripId: String, viewerName: String): String? =
        rpcText("tp_request_join", mapOf(
            "p_trip_id" to tripId, "p_viewer_token" to viewerToken(), "p_viewer_name" to viewerName))

    suspend fun joinStatus(tripId: String): String? =
        rpcText("tp_join_status", mapOf("p_trip_id" to tripId, "p_viewer_token" to viewerToken()))

    /** Owner: everyone who requested access with a device token. */
    suspend fun fetchJoinRequests(accessKey: String): List<Map<String, Any?>> =
        rpcArray("tp_get_join_requests", mapOf(
            "p_access_key" to accessKey, "p_owner_token" to ownerToken(accessKey))) ?: emptyList()

    /** Owner: approve or deny a viewer's device. */
    suspend fun setViewerStatus(accessKey: String, viewerToken: String, approve: Boolean): Boolean =
        rpcBool("tp_set_viewer_status", mapOf(
            "p_access_key" to accessKey, "p_owner_token" to ownerToken(accessKey),
            "p_viewer_token" to viewerToken, "p_status" to if (approve) "APPROVED" else "DENIED"))

    // -----------------------------------------------------------------------
    // Viewer-side reads. The `ref` is either a 64-char access key (id+password
    // capability) or a "TP-…" trip id (device must be owner-approved) — the
    // formats can't collide, so one string carries both modes everywhere.
    // -----------------------------------------------------------------------

    private fun isTripIdRef(ref: String) = ref.startsWith("TP-")

    suspend fun fetchMeta(ref: String): Map<String, Any?>? =
        if (isTripIdRef(ref))
            rpcObject("tp_get_meta_t", mapOf("p_trip_id" to ref, "p_viewer_token" to viewerToken()))
        else
            rpcObject("tp_get_meta", mapOf("p_access_key" to ref))

    suspend fun fetchState(ref: String): Map<String, Any?>? =
        if (isTripIdRef(ref))
            rpcObject("tp_get_state_t", mapOf("p_trip_id" to ref, "p_viewer_token" to viewerToken()))
        else
            rpcObject("tp_get_state", mapOf("p_access_key" to ref))

    suspend fun fetchEventsSince(ref: String, sinceMs: Long): List<Map<String, Any?>>? =
        if (isTripIdRef(ref))
            rpcArray("tp_get_events_t", mapOf("p_trip_id" to ref, "p_viewer_token" to viewerToken(), "p_since" to sinceMs))
        else
            rpcArray("tp_get_events", mapOf("p_access_key" to ref, "p_since" to sinceMs))

    suspend fun registerViewer(accessKey: String, name: String): Boolean =
        rpcBool("tp_register_viewer", mapOf("p_access_key" to accessKey, "p_viewer" to name))

    suspend fun fetchViewers(accessKey: String): List<String> {
        val body = rpc("tp_get_viewers", mapOf("p_access_key" to accessKey))?.trim() ?: return emptyList()
        return runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    /** Cheap liveness probe (used to distinguish "expired" from "offline"). */
    suspend fun serverReachable(): Boolean = rpc("tp_now", emptyMap()) != null

    /** Server clock skew so freshness math uses server time. */
    suspend fun serverTimeOffsetMs(): Long {
        val server = rpc("tp_now", emptyMap())?.trim()?.toLongOrNull() ?: return 0L
        return server - System.currentTimeMillis()
    }

    // -----------------------------------------------------------------------
    // Viewer subscriptions: REST polling emitted as flows, with the interval
    // decided AFTER each read rather than fixed up front.
    //
    // A fixed 5-second poll is a battery bill the follower pays for twelve
    // hours to watch a train that reports once a minute. Handing the caller a
    // `nextDelay` callback lets the repository price each read against what
    // actually changed — see ViewerRepository.statePollMs.
    // -----------------------------------------------------------------------

    fun currentStateFlow(
        accessKey: String,
        nextDelayMs: (Map<String, Any?>?) -> Long
    ): Flow<Map<String, Any?>?> = pollingFlow(nextDelayMs) { fetchState(accessKey) }

    fun metaFlow(
        accessKey: String,
        nextDelayMs: (Map<String, Any?>?) -> Long
    ): Flow<Map<String, Any?>?> = pollingFlow(nextDelayMs) { fetchMeta(accessKey) }

    fun eventsFlow(
        accessKey: String,
        nextDelayMs: (List<Map<String, Any?>>?) -> Long
    ): Flow<List<Map<String, Any?>>> =
        // A failed read stays null all the way through the polling loop so the
        // backoff can see it, and only becomes an empty list at the very edge.
        pollingFlow(nextDelayMs) { fetchEventsSince(accessKey, 0L) }
            .map { it ?: emptyList() }

    /**
     * Emits, then waits for however long the caller says this result is worth.
     *
     * Backs off on consecutive failures so a phone with no signal in a tunnel
     * doesn't spend the journey retrying every few seconds.
     */
    private fun <T> pollingFlow(nextDelayMs: (T) -> Long, fetch: suspend () -> T): Flow<T> =
        flow {
            var failures = 0
            while (true) {
                val result = fetch()
                failures = if (result == null) (failures + 1).coerceAtMost(MAX_BACKOFF_STEPS) else 0
                emit(result)
                val base = nextDelayMs(result).coerceIn(MIN_POLL_MS, MAX_POLL_MS)
                val backoff = 1L shl failures          // 1x, 2x, 4x, 8x, 16x
                delay((base * backoff).coerceAtMost(MAX_POLL_MS))
            }
        }.distinctUntilChanged().flowOn(Dispatchers.IO)

    // -----------------------------------------------------------------------
    // Viewer alerts: instead of FCM topics, joining devices run the local
    // follow service, which polls and raises forced notifications.
    // -----------------------------------------------------------------------

    fun subscribeTopic(accessKey: String) {
        if (!isAvailable()) return
        com.trippulse.app.service.TripFollowService.start(appContext)
    }

    fun unsubscribeTopic(accessKey: String) {
        // The follow service stops itself when no followed journeys remain.
    }

    private companion object {
        const val MIN_POLL_MS = 4_000L
        const val MAX_POLL_MS = 600_000L
        const val MAX_BACKOFF_STEPS = 4
    }
}
