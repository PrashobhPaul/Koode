package com.trippulse.app.data.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Firebase Realtime Database transport (docs/spec/75-77, 13). This is a
 * transport only — the durable source of truth is the local Room store. Every
 * method is a no-op / returns unavailable when Firebase is not configured, so
 * the app runs fully in LOCAL mode until google-services.json is added.
 *
 * Schema:
 *   /trips/{accessKey}/meta
 *   /trips/{accessKey}/currentState   (overwritten — live source of truth)
 *   /trips/{accessKey}/events/{eventId}   (append-only, write-once)
 *   /trips/{accessKey}/locations/{autoId}
 */
class FirebaseCloud(private val appContext: Context) {

    sealed interface Ack {
        object Acked : Ack
        object AlreadyExists : Ack
        object Denied : Ack
        object Retryable : Ack
    }

    private val available: Boolean by lazy { FirebaseApp.getApps(appContext).isNotEmpty() }

    /**
     * Resolves the Realtime Database instance. google-services.json only
     * contains a `firebase_url` when it was downloaded AFTER the RTDB instance
     * was created; without it FirebaseDatabase.getInstance() throws and cloud
     * sync silently dies. Fall back to the default-instance URL derived from
     * the project id so cloud mode works either way.
     */
    private val db: FirebaseDatabase? by lazy {
        if (!available) return@lazy null
        runCatching {
            val opts = FirebaseApp.getInstance().options
            val url = opts.databaseUrl
            if (!url.isNullOrBlank()) FirebaseDatabase.getInstance()
            else FirebaseDatabase.getInstance("https://${opts.projectId}-default-rtdb.firebaseio.com")
        }.getOrNull()
    }
    private val auth: FirebaseAuth? by lazy {
        if (available) runCatching { FirebaseAuth.getInstance() }.getOrNull() else null
    }

    fun isAvailable(): Boolean = available

    /** Enable disk persistence so RTDB itself buffers writes across restarts. */
    fun enablePersistence() {
        runCatching { db?.setPersistenceEnabled(true) }
    }

    private fun trip(accessKey: String): DatabaseReference? =
        db?.getReference("trips")?.child(accessKey)

    /** Ensures an anonymous session exists; returns the uid or null. */
    suspend fun ensureAuth(): String? {
        val a = auth ?: return null
        a.currentUser?.let { return it.uid }
        return runCatching {
            withTimeoutOrNull(15_000) { a.signInAnonymously().await() }?.user?.uid
        }.getOrNull()
    }

    suspend fun writeMeta(accessKey: String, meta: Map<String, Any?>): Boolean {
        val ref = trip(accessKey)?.child("meta") ?: return false
        val uid = ensureAuth() ?: return false
        // Stamp the owner uid so the database rules can restrict writes to the
        // driver device; viewers authenticate with a different uid and are
        // read-only.
        val withOwner = meta.toMutableMap().apply { put("ownerUid", uid) }
        return runCatching {
            withTimeoutOrNull(15_000) { ref.setValue(withOwner).await() } != null
        }.getOrDefault(false)
    }

    suspend fun setExpiry(accessKey: String, expiresAtMs: Long): Boolean {
        val ref = trip(accessKey)?.child("meta")?.child("expiresAt") ?: return false
        return runCatching {
            withTimeoutOrNull(15_000) { ref.setValue(expiresAtMs).await() } != null
        }.getOrDefault(false)
    }

    /** Overwrites the live-state node. This is the freshness source of truth. */
    suspend fun pushCurrentState(accessKey: String, state: Map<String, Any?>): Boolean {
        val ref = trip(accessKey)?.child("currentState") ?: return false
        return runCatching {
            withTimeoutOrNull(12_000) { ref.setValue(state).await() } != null
        }.getOrDefault(false)
    }

    /** Marks connectivity OFFLINE server-side if the driver device disconnects. */
    fun armOnDisconnect(accessKey: String) {
        val ref = trip(accessKey)?.child("currentState") ?: return
        runCatching {
            ref.child("connectivity").onDisconnect().setValue("OFFLINE")
            ref.child("lastSeenAt").onDisconnect().setValue(ServerValue.TIMESTAMP)
        }
    }

    /**
     * Idempotent event write. Rules make events write-once (!data.exists()), so
     * a duplicate is rejected with permission-denied; we then confirm existence
     * and treat it as an ack. This is what makes offline ret/re-sync safe.
     */
    suspend fun writeEvent(accessKey: String, eventId: String, value: Map<String, Any?>): Ack {
        val ref = trip(accessKey)?.child("events")?.child(eventId) ?: return Ack.Retryable
        return try {
            val done = withTimeoutOrNull(15_000) { ref.setValue(value).await(); true }
            if (done == true) Ack.Acked else Ack.Retryable
        } catch (e: Exception) {
            val denied = e.message?.contains("permission", ignoreCase = true) == true ||
                e.message?.contains("denied", ignoreCase = true) == true
            if (denied) {
                // could be write-once rejection: verify the event already exists
                val exists = runCatching {
                    withTimeoutOrNull(10_000) { ref.get().await() }?.exists() == true
                }.getOrDefault(false)
                if (exists) Ack.AlreadyExists else Ack.Denied
            } else {
                Ack.Retryable
            }
        }
    }

    /** Batch location samples under locations/ using a multi-path update. */
    suspend fun writeLocations(accessKey: String, samples: Map<String, Map<String, Any?>>): Boolean {
        if (samples.isEmpty()) return true
        val ref = trip(accessKey)?.child("locations") ?: return false
        val update = HashMap<String, Any?>()
        for ((id, v) in samples) update[id] = v
        return runCatching {
            withTimeoutOrNull(15_000) { ref.updateChildren(update).await() } != null
        }.getOrDefault(false)
    }

    suspend fun fetchMeta(accessKey: String): Map<String, Any?>? {
        val ref = trip(accessKey)?.child("meta") ?: return null
        return runCatching {
            val snap = withTimeoutOrNull(15_000) { ref.get().await() } ?: return null
            @Suppress("UNCHECKED_CAST")
            snap.value as? Map<String, Any?>
        }.getOrNull()
    }

    /** Reads the one-off server clock skew so freshness math uses server time. */
    suspend fun serverTimeOffsetMs(): Long {
        val ref = db?.getReference(".info/serverTimeOffset") ?: return 0L
        return runCatching {
            val snap = withTimeoutOrNull(8_000) { ref.get().await() } ?: return 0L
            (snap.value as? Number)?.toLong() ?: 0L
        }.getOrDefault(0L)
    }

    // ---- viewer subscriptions ----

    fun currentStateFlow(accessKey: String): Flow<Map<String, Any?>?> =
        nodeFlow(trip(accessKey)?.child("currentState")) { snap ->
            @Suppress("UNCHECKED_CAST")
            snap.value as? Map<String, Any?>
        }

    fun metaFlow(accessKey: String): Flow<Map<String, Any?>?> =
        nodeFlow(trip(accessKey)?.child("meta")) { snap ->
            @Suppress("UNCHECKED_CAST")
            snap.value as? Map<String, Any?>
        }

    fun eventsFlow(accessKey: String): Flow<List<Map<String, Any?>>> {
        val query: Query? = trip(accessKey)?.child("events")?.limitToLast(500)
        return callbackFlow {
            if (query == null) { trySend(emptyList()); awaitClose { }; return@callbackFlow }
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = ArrayList<Map<String, Any?>>()
                    for (child in snapshot.children) {
                        @Suppress("UNCHECKED_CAST")
                        val m = (child.value as? Map<String, Any?>)?.toMutableMap() ?: continue
                        m["eventId"] = child.key
                        list.add(m)
                    }
                    trySend(list)
                }
                override fun onCancelled(error: DatabaseError) { close(error.toException()) }
            }
            query.addValueEventListener(listener)
            awaitClose { query.removeEventListener(listener) }
        }
    }

    private fun <T> nodeFlow(ref: DatabaseReference?, map: (DataSnapshot) -> T): Flow<T?> =
        callbackFlow {
            if (ref == null) { trySend(null); awaitClose { }; return@callbackFlow }
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) { trySend(map(snapshot)) }
                override fun onCancelled(error: DatabaseError) { close(error.toException()) }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }

    // ---- FCM topics (best-effort) ----

    fun subscribeTopic(accessKey: String) {
        if (!available) return
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic("trip_$accessKey") }
    }

    fun unsubscribeTopic(accessKey: String) {
        if (!available) return
        runCatching { FirebaseMessaging.getInstance().unsubscribeFromTopic("trip_$accessKey") }
    }
}
