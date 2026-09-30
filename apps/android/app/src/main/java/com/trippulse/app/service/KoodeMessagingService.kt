package com.trippulse.app.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.trippulse.app.TripPulseApp
import com.trippulse.app.data.EventCodec
import com.trippulse.app.notifications.FollowerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives server pushes for journeys this phone follows.
 *
 * FCM delivers a high-priority message here even when Koode is closed, which
 * is the whole point: the Circle hears every update without having the app
 * open. The message carries the event exactly as the traveller's device
 * recorded it; this turns it into the same notification the in-app follow
 * service would have shown — and the shared ledger in [FollowerNotifications]
 * guarantees it is shown once, whichever path gets there first.
 *
 * A push also restarts the follow service when this phone is following a live
 * journey. Push only speaks when the traveller's phone does; noticing that it
 * has gone *silent* is the follow service's job, so it must be running.
 */
class KoodeMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["kind"] != KIND_EVENT) return
        val app = applicationContext as? TripPulseApp ?: return
        val graph = app.graph

        val accessKey = data["accessKey"].orEmpty()
        val tripId = data["tripId"].orEmpty()
        val type = data["type"] ?: return
        val eventTime = data["eventTime"]?.toLongOrNull() ?: return
        val payload = runCatching { EventCodec.payloadFromJson(data["payload"] ?: "{}") }
            .getOrDefault(emptyMap())

        // onMessageReceived runs on a background thread with a ~20s budget;
        // a local lookup is well inside it.
        val follow = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(5_000) {
                val dao = graph.db.viewerDao()
                (if (accessKey.isNotEmpty()) dao.byKey(accessKey) else null)
                    ?: (if (tripId.isNotEmpty()) dao.byKey(tripId) else null)
            }
        } ?: return // this phone no longer follows that journey

        FollowerNotifications.notify(
            this, graph.notifier, follow.accessKey, follow.label, type, eventTime, payload
        )

        if (!follow.expired) {
            // A high-priority push grants a brief window in which a foreground
            // service may start; if the platform refuses, the next app open
            // or boot restarts it instead.
            try {
                TripFollowService.start(this)
            } catch (e: Exception) {
                Log.w(TAG, "Could not restart the follow service from a push", e)
            }
        }
    }

    override fun onNewToken(token: String) {
        val app = applicationContext as? TripPulseApp ?: return
        val graph = app.graph
        scope.launch {
            graph.push.onNewToken(token)
            runCatching { graph.db.viewerDao().activeList() }.getOrDefault(emptyList())
                .forEach { runCatching { graph.push.ensureRegistered(it) } }
        }
    }

    private companion object {
        const val TAG = "KoodePush"
        /** The data-message kind the tp-push Edge Function sends. */
        const val KIND_EVENT = "tp_event"
    }
}
