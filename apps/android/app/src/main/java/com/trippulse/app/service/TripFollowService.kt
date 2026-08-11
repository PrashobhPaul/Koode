package com.trippulse.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.trippulse.app.TripPulseApp
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Freshness
import com.trippulse.app.domain.JourneyHealth
import com.trippulse.app.domain.JourneyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Viewer-side alert engine ("forced alerts"). Runs as a foreground service on
 * every phone that has joined a trip with a Trip ID + password, polling the
 * backend and raising high-priority notifications for the moments that matter:
 * trip started, SOS, arrival at the destination, completion and overnight
 * stops. This replaces FCM push entirely — no Google services, no server
 * push infrastructure, nothing to configure.
 *
 * The service stops itself once no followed trip is live (all expired —
 * a trip id self-destructs 30 minutes after the driver reaches the
 * destination — or explicitly left by the viewer).
 */
class TripFollowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as? TripPulseApp ?: run { stopSelf(); return START_NOT_STICKY }
        val graph = app.graph
        startForeground(NOTIF_ID, graph.notifier.buildFollowNotification())

        scope.launch {
            val seen = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            while (isActive) {
                val follows = graph.db.viewerDao().activeList()
                if (follows.isEmpty()) { stopSelf(); return@launch }

                var anyLive = false
                for (f in follows) {
                    val meta = graph.cloud.fetchMeta(f.accessKey)
                    if (meta == null) {
                        // expired (self-destructed) or unreachable; only mark
                        // expired when the backend is reachable at all
                        if (graph.cloud.serverReachable()) graph.db.viewerDao().markExpired(f.accessKey)
                        continue
                    }
                    anyLive = true

                    val since = seen.getLong(f.accessKey, f.joinedAtMs)
                    val events = graph.cloud.fetchEventsSince(f.accessKey, since) ?: continue
                    var latest = since
                    for (e in events) {
                        val t = (e["eventTime"] as? Number)?.toLong() ?: continue
                        if (t > latest) latest = t
                        alert(graph.notifier, e["type"] as? String, f.label)
                    }
                    if (latest != since) seen.edit().putLong(f.accessKey, latest).apply()

                    // Journey Health: notify once when a journey enters
                    // CONCERN, and once when it settles back to normal.
                    checkHealth(graph, f.accessKey, f.label, meta)
                }

                if (!anyLive && follows.all { it.expired }) { stopSelf(); return@launch }
                delay(POLL_MS)
            }
        }
        return START_STICKY
    }

    private suspend fun checkHealth(
        graph: com.trippulse.app.di.AppGraph, ref: String, label: String, meta: Map<String, Any?>
    ) {
        val state = graph.cloud.fetchState(ref) ?: return
        fun ln(k: String): Long? = (state[k] as? Number)?.toLong()
        val now = System.currentTimeMillis()
        val lastAt = ln("lastLocationAt") ?: ln("updatedAt")
        val ageS = if (lastAt != null) (now - lastAt) / 1000 else Long.MAX_VALUE
        val journey = state["status"] as? String
        val terminal = journey == JourneyStatus.ARRIVED.name || journey == JourneyStatus.COMPLETED.name
        val freshness = when {
            terminal -> Freshness.COMPLETED
            ageS <= 60 -> Freshness.LIVE
            ageS <= 300 -> Freshness.RECENT
            ageS <= 900 -> Freshness.STALE
            else -> Freshness.OFFLINE
        }
        val report = JourneyHealth.evaluate(
            JourneyHealth.Inputs(
                nowMs = now,
                journey = journey,
                freshness = freshness,
                sosActive = state["sosActive"] as? Boolean ?: false,
                deviationActive = state["deviationActive"] as? Boolean ?: false,
                batteryPct = ln("battery")?.toInt(),
                foodAtMs = ln("foodAt"),
                waterAtMs = ln("waterAt"),
                lastBreakEndAtMs = ln("lastBreakEndAt"),
                stopStartedAtMs = null,
                drivingSinceMs = ln("drivingSince"),
                overnightType = state["overnightType"] as? String,
                startedAtMs = (meta["startedAt"] as? Number)?.toLong(),
                localHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            )
        )
        val healthPrefs = getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE)
        val previous = healthPrefs.getString(ref, JourneyHealth.Level.NORMAL.name)
        val currentLevel = report.level.name
        if (currentLevel != previous) {
            if (report.level == JourneyHealth.Level.CONCERN) {
                graph.notifier.showJourneyAttention(label, report.reasons.firstOrNull() ?: report.headline)
            } else if (previous == JourneyHealth.Level.CONCERN.name && report.level == JourneyHealth.Level.NORMAL) {
                graph.notifier.showJourneyBackToNormal(label)
            }
            healthPrefs.edit().putString(ref, currentLevel).apply()
        }
    }

    private fun alert(notifier: com.trippulse.app.notifications.Notifier, type: String?, label: String) {
        when (type) {
            EventTypes.TRIP_STARTED -> notifier.showTripStarted()
            EventTypes.SOS_ACTIVATED -> notifier.showSosActive()
            EventTypes.ARRIVAL_DETECTED -> notifier.showTripUpdate("Destination reached", "The driver has reached the destination. ($label)")
            EventTypes.TRIP_COMPLETED -> notifier.showTripUpdate("Trip completed", "The trip has ended. Access expires 30 minutes after arrival. ($label)")
            EventTypes.OVERNIGHT_CONFIRMED -> notifier.showTripUpdate("Overnight rest", "The driver is stopping overnight. ($label)")
            else -> {} // non-alert timeline events stay silent
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 3001
        private const val PREFS = "tp_follow_seen"
        private const val HEALTH_PREFS = "tp_follow_health"
        private const val POLL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, TripFollowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
