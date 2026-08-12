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
                    var alerted = false
                    for (e in events) {
                        val t = (e["eventTime"] as? Number)?.toLong() ?: continue
                        if (t > latest) latest = t
                        if (alert(graph.notifier, e["type"] as? String, f.label)) alerted = true
                    }
                    if (latest != since) seen.edit().putLong(f.accessKey, latest).apply()
                    if (alerted) touchDigest(f.accessKey)

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
        // Flight rule: silence during the expected flying window is normal
        // (flight mode), not a concern.
        val mode = meta["transportMode"] as? String
        val plannedDep = (meta["plannedDeparture"] as? Number)?.toLong()
        val offlineExpected = mode == "FLIGHT" && plannedDep != null &&
            now >= plannedDep - 30 * 60_000L && now <= plannedDep + 9 * 3_600_000L
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
                localHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                privateVehicle = (mode ?: "CAR") in setOf("CAR", "BIKE"),
                offlineExpected = offlineExpected
            )
        )
        // Humanized "Koode Status" for the Home feed — stored so Home renders
        // instantly without any network call.
        getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).edit().putString(
            ref,
            listOf(
                report.level.name,
                if (offlineExpected && freshness == Freshness.OFFLINE) "In flight — offline as expected" else report.headline,
                report.reasons.firstOrNull().orEmpty(),
                now.toString()
            ).joinToString("|")
        ).apply()
        val healthPrefs = getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE)
        val previous = healthPrefs.getString(ref, JourneyHealth.Level.NORMAL.name)
        val currentLevel = report.level.name
        if (currentLevel != previous) {
            if (report.level == JourneyHealth.Level.CONCERN) {
                graph.notifier.showJourneyAttention(label, report.reasons.firstOrNull() ?: report.headline)
                touchDigest(ref)
            } else if (previous == JourneyHealth.Level.CONCERN.name && report.level == JourneyHealth.Level.NORMAL) {
                graph.notifier.showJourneyBackToNormal(label)
                touchDigest(ref)
            }
            healthPrefs.edit().putString(ref, currentLevel).apply()
        }

        maybeDigest(graph, ref, label, journey, report, state)
    }

    /**
     * Periodic reassurance digests — the cadence rules:
     *  - Never for arrived/completed journeys (the arrival alert closed the loop).
     *  - Every 2 hours while the journey is live, so the family stays informed
     *    without opening the app…
     *  - …stretched to every 4 hours during a confirmed overnight rest (news
     *    is not expected, sleep is).
     *  - The clock resets whenever ANY instant alert was just sent, so a
     *    digest never lands minutes after a real notification (no spam).
     */
    private fun maybeDigest(
        graph: com.trippulse.app.di.AppGraph, ref: String, label: String,
        journey: String?, report: JourneyHealth.Report, state: Map<String, Any?>
    ) {
        if (journey == JourneyStatus.ARRIVED.name || journey == JourneyStatus.COMPLETED.name) return
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(DIGEST_PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(ref, 0L)
        if (last == 0L) { prefs.edit().putLong(ref, now).apply(); return }
        val overnight = journey == JourneyStatus.OVERNIGHT.name
        val intervalMs = if (overnight) 4 * 3_600_000L else 2 * 3_600_000L
        if (now - last < intervalMs) return

        val eta = (state["etaLikely"] as? Number)?.toLong()
        val body = buildString {
            append(report.headline)
            if (eta != null && !overnight) append(" · ETA ${com.trippulse.app.core.TimeFmt.clockWithDay(eta, now)}")
        }
        graph.notifier.showTripUpdate(label, body)
        prefs.edit().putLong(ref, now).apply()
    }

    private fun touchDigest(ref: String) {
        getSharedPreferences(DIGEST_PREFS, Context.MODE_PRIVATE)
            .edit().putLong(ref, System.currentTimeMillis()).apply()
    }

    /** Returns true when the event produced an instant notification. */
    private fun alert(notifier: com.trippulse.app.notifications.Notifier, type: String?, label: String): Boolean {
        when (type) {
            EventTypes.TRIP_STARTED -> notifier.showTripStarted()
            EventTypes.SOS_ACTIVATED -> notifier.showSosActive()
            EventTypes.ARRIVAL_DETECTED -> notifier.showTripUpdate("Destination reached", "The traveller has reached the destination. ($label)")
            EventTypes.TRIP_COMPLETED -> notifier.showTripUpdate("Journey completed", "The journey has ended. Access expires 30 minutes after arrival. ($label)")
            EventTypes.OVERNIGHT_CONFIRMED -> notifier.showTripUpdate("Overnight rest", "The traveller is stopping overnight. ($label)")
            else -> return false // non-alert timeline events stay silent
        }
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 3001
        private const val PREFS = "tp_follow_seen"
        private const val HEALTH_PREFS = "tp_follow_health"
        private const val DIGEST_PREFS = "tp_follow_digest"
        const val STATUS_PREFS = "tp_follow_status"
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
