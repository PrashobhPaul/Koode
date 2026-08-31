package com.trippulse.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.trippulse.app.TripPulseApp
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Freshness
import com.trippulse.app.domain.Darkness
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
 * Follower-side alert engine ("forced alerts"). Runs as a foreground service on
 * every phone following a journey, polling the backend and raising
 * high-priority notifications for the moments that matter: journey started,
 * SOS, arrival at the destination, completion and overnight stops. This
 * replaces FCM push entirely — no Google services, no server push
 * infrastructure, nothing to configure.
 *
 * Two things this service must never do, both learned the hard way:
 *
 *  - **Never conclude a journey ended.** A meta read returning null means the
 *    server could not answer, not that the traveller is home. Only an explicit
 *    completion signal from the traveller's own device ends a journey here.
 *  - **Never poll at a fixed fast rate.** Followers keep this running for a
 *    whole journey; the interval scales with what is actually happening, and
 *    with the follower's own refresh setting.
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
                        // We could not read this journey. That is a statement
                        // about the network or the capability — never about the
                        // traveller. Record it honestly and try again later.
                        graph.db.viewerDao().markUnreachable(f.accessKey, System.currentTimeMillis())
                        continue
                    }
                    anyLive = true
                    graph.db.viewerDao().markSeen(f.accessKey, System.currentTimeMillis())

                    val since = seen.getLong(f.accessKey, f.joinedAtMs)
                    val events = graph.cloud.fetchEventsSince(f.accessKey, since) ?: continue
                    var latest = since
                    var alerted = false
                    var endedAtMs: Long? = null
                    for (e in events) {
                        val t = (e["eventTime"] as? Number)?.toLong() ?: continue
                        if (t > latest) latest = t
                        if ((e["type"] as? String) == EventTypes.TRIP_COMPLETED) endedAtMs = t
                        if (alert(graph.notifier, e["type"] as? String, f.label)) alerted = true
                    }
                    if (latest != since) seen.edit().putLong(f.accessKey, latest).apply()
                    if (alerted) touchDigest(f.accessKey)

                    // Journey Health: notify once when a journey enters
                    // CONCERN, and once when it settles back to normal.
                    val state = checkHealth(graph, f.accessKey, f.label, meta)

                    // The ONLY path to "this journey has ended": the traveller
                    // said so, either in their live state or as a completion
                    // event on the journey's own log.
                    val endedInState = (state?.get("endedByOwner") as? Boolean == true) ||
                        (state?.get("status") as? String) == JourneyStatus.COMPLETED.name
                    if (endedAtMs != null || endedInState) {
                        graph.db.viewerDao().markEndedByOwner(
                            f.accessKey, endedAtMs ?: System.currentTimeMillis()
                        )
                    }
                }

                delay(pollIntervalMs(graph, anyLive))
            }
        }
        return START_STICKY
    }

    /**
     * How long to sleep before the next sweep.
     *
     * The follower's phone is not the one on the journey, and it may be
     * following for twelve hours. A minute between checks is well inside the
     * freshness window the viewer screen renders, and roughly a twentieth of
     * the wake-ups the old fixed 30-second sweep cost.
     */
    private fun pollIntervalMs(graph: com.trippulse.app.di.AppGraph, anyLive: Boolean): Long {
        if (!anyLive) return IDLE_POLL_MS
        return graph.settings.current.viewerRefresh.idleS * 1000L
    }

    /** Returns the live state that was read, so the caller can act on it. */
    private suspend fun checkHealth(
        graph: com.trippulse.app.di.AppGraph, ref: String, label: String, meta: Map<String, Any?>
    ): Map<String, Any?>? {
        val state = graph.cloud.fetchState(ref) ?: return null
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
                privateVehicle = com.trippulse.app.domain.TransportCatalog.isPrivate(mode),
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
        watchForDarkness(graph, ref, label, state, now, offlineExpected)
        return state
    }

    /**
     * Keeps telling the family, for as long as the device stays dark.
     *
     * This is separate from the health engine above on purpose. That one fires
     * on a *transition* -- it notices a journey becoming concerning and says so
     * once -- which is right for "they have not had a break in five hours" and
     * badly wrong for a phone that has gone silent. Silence does not get
     * better by being announced; it gets worse by being forgotten. So this
     * runs off elapsed time instead, widening but never stopping, and the step
     * it last announced is persisted so a poll every minute does not become a
     * notification every minute.
     *
     * It runs on the follower's phone, which is the entire reason it works: a
     * traveller's device that is off, stolen, broken or out of coverage cannot
     * silence it.
     */
    private fun watchForDarkness(
        graph: com.trippulse.app.di.AppGraph,
        ref: String,
        label: String,
        state: Map<String, Any?>,
        now: Long,
        offlineExpected: Boolean
    ) {
        fun ln(k: String): Long? = (state[k] as? Number)?.toLong()
        val journey = state["status"] as? String
        val closed = state["endedByOwner"] as? Boolean == true ||
            journey == JourneyStatus.COMPLETED.name

        val assessment = Darkness.assess(
            Darkness.Inputs(
                nowMs = now,
                lastUpdateMs = ln("lastLocationAt") ?: ln("updatedAt"),
                lastBatteryPct = ln("battery")?.toInt(),
                shutdownAtMs = ln("wentDarkAt"),
                shutdownBatteryPct = ln("battery")?.toInt(),
                shutdownWasRestart = false,
                simChangedAtMs = ln("simChangedAt"),
                deviationActive = state["deviationActive"] as? Boolean ?: false,
                offlineExpected = offlineExpected,
                journeyClosed = closed
            )
        )

        val prefs = getSharedPreferences(DARK_PREFS, Context.MODE_PRIVATE)
        val lastStep = prefs.getInt(ref, -1)

        if (!assessment.dark) {
            // Back from the dark. Announced immediately and unconditionally,
            // because "they're back" is the one message anyone waiting
            // actually wants, and it must not wait for a poll cycle boundary.
            if (lastStep >= 0) {
                graph.notifier.showJourneyBackToNormal(label)
                prefs.edit().remove(ref).apply()
            }
            return
        }

        val step = assessment.escalationStep
        if (step <= lastStep) return
        prefs.edit().putInt(ref, step).apply()
        graph.notifier.showJourneyAttention(
            Darkness.headline(assessment, label),
            Darkness.detail(assessment)
        )
        touchDigest(ref)
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
            // "Reached" is not "finished": the journey stays live on every
            // screen until the traveller ends it themselves.
            EventTypes.ARRIVAL_DETECTED -> notifier.showTripUpdate("Reached the destination", "They've reached the destination. ($label)")
            EventTypes.TRIP_COMPLETED -> notifier.showTripUpdate("Journey ended safely", "The traveller has ended this journey. ($label)")
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
        /** Last escalation step announced per followed journey. */
        private const val DARK_PREFS = "tp_follow_dark"
        const val STATUS_PREFS = "tp_follow_status"
        /** Nothing readable right now — back off hard rather than hammer. */
        private const val IDLE_POLL_MS = 180_000L

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
