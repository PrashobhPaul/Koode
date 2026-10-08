package com.trippulse.app.data.export

import android.content.Context
import android.util.Log
import com.trippulse.app.data.EventCodec
import com.trippulse.app.data.local.TripPulseDb
import com.trippulse.app.data.remote.TripCloud
import com.trippulse.app.domain.ApprovedAnalytics
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.JourneyAnalytics
import com.trippulse.app.domain.Measures
import com.trippulse.app.domain.PlaceResolver
import com.trippulse.app.domain.SummaryCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Once the traveller approves a journey, tells the people following it — in
 * this order, and only then:
 *
 *  1. the approved, non-financial analytics (the server holds back the
 *     "journey completed" notification until these arrive);
 *  2. the verified journey report — the timeline PDF, built here without any
 *     expense input at all — uploaded to private storage, after which the
 *     server announces it to followers.
 *
 * The traveller's expense report is never uploaded; it is saved on the phone.
 *
 * Offline-safe: the work is remembered until it succeeds, retried with
 * back-off while the app runs, and resumed on the next start ([resume]).
 */
class ApprovedJourneyPublisher(
    private val context: Context,
    private val db: TripPulseDb,
    private val cloud: TripCloud,
    private val measures: () -> Measures,
    private val scope: CoroutineScope
) {
    data class Approval(
        val tripId: String,
        val approvedAtMs: Long,
        val approvedBy: String?,
        val safeConfirmed: Boolean,
        val autoClosed: Boolean
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** The traveller approved [a]'s journey: publish it (now, or when back online). */
    fun enqueue(a: Approval) {
        save(a.tripId, Pending(a, analyticsDone = false, reportDone = false, attempts = 0))
        kick()
    }

    /** Picks up anything left from a previous run. Call on app start. */
    fun resume() {
        if (prefs.all.isNotEmpty()) kick()
    }

    private fun kick() {
        scope.launch {
            for (wait in BACKOFF_MS) {
                if (!mutex.withLock { runPending() }) return@launch
                delay(wait)
            }
        }
    }

    /** One pass over everything pending; true while anything is still left. */
    private suspend fun runPending(): Boolean {
        for (tripId in prefs.all.keys.toList()) {
            val p = load(tripId)
            if (p == null) { drop(tripId); continue }
            try {
                step(p)
            } catch (e: Exception) {
                Log.w(TAG, "Publishing $tripId failed; will retry", e)
                save(tripId, p.copy(attempts = p.attempts + 1))
            }
        }
        return prefs.all.isNotEmpty()
    }

    private suspend fun step(p: Pending) {
        val a = p.approval
        val t = db.tripDao().byId(a.tripId)
        if (t == null || !t.cloudEnabled || !cloud.isAvailable() || p.attempts >= MAX_ATTEMPTS) {
            drop(a.tripId); return
        }
        val entities = db.eventDao().allForTrip(a.tripId)
        val events = entities.map { EventCodec.toDomain(it) }
        val started = t.startedAtMs ?: t.createdAtMs
        val ended = t.completedAtMs ?: a.approvedAtMs
        val samples = db.locationDao().allForTrip(a.tripId)
        val distanceM = com.trippulse.app.data.coveredDistanceM(db.stateDao().byId(a.tripId)?.distanceCoveredM ?: 0.0, samples, legs = db.legDao().forTrip(a.tripId))
        var state = p

        if (!state.analyticsDone) {
            var summary = SummaryCalculator.compute(events, distanceM, started, ended)
            // The story's timing where it can be told: a silence the car moved
            // through is driving, a halt is not. Nothing here is money.
            runCatching {
                val story = ReportFactory.storyFor(context, t, entities.filterNot { it.sensitive }, samples, null, t.originName, t.destName, measures())
                summary = summary.copy(
                    drivingSeconds = story.movingSeconds,
                    stops = story.stops.size,
                    longestLegSeconds = story.longestDrive?.movingSeconds ?: summary.longestLegSeconds,
                    longestBreakSeconds = story.longestStop?.seconds ?: summary.longestBreakSeconds
                )
            }
            val analytics = ApprovedAnalytics.build(
                summary, t.originName, t.destName, started, ended, t.transportMode,
                tollsCrossed = events.count { it.type == EventTypes.TOLL_CROSSED }
            )
            check(!ApprovedAnalytics.isFinancial(analytics)) { "approved analytics must carry no money" }
            when (cloud.publishAnalytics(t.accessKey, analytics, a.approvedAtMs, a.approvedBy, a.safeConfirmed, a.autoClosed)) {
                "OK" -> state = state.copy(analyticsDone = true).also { save(a.tripId, it) }
                null -> { save(a.tripId, state.copy(attempts = state.attempts + 1)); return }  // offline
                else -> { Log.w(TAG, "Analytics refused for ${a.tripId}"); drop(a.tripId); return }
            }
        }

        if (!state.reportDone) {
            val legs = db.legDao().forTrip(a.tripId).map {
                JourneyAnalytics.LegInput(it.legIndex, it.mode, it.fromName, it.toName, it.startedAtMs, it.completedAtMs, it.seat,
                    distanceM = com.trippulse.app.data.legDistanceM(samples, it))
            }
            val report = JourneyAnalytics.analyse(
                JourneyAnalytics.Inputs(
                    events = events.filterIndexed { i, _ -> !entities[i].sensitive },
                    distanceCoveredM = distanceM,
                    startedAtMs = started,
                    endedAtMs = ended,
                    // The followers' report: no expense input, so no money can reach it.
                    expenses = emptyList(),
                    legs = legs,
                    transportMode = t.transportMode,
                    topSpeedKmh = samples.mapNotNull { it.speedMps }.maxOrNull()?.times(3.6)
                )
            )
            val saved = runCatching { db.savedPlaceDao().all() }.getOrNull().orEmpty()
                .map { PlaceResolver.SavedPlace(it.name, it.lat, it.lng) }
            // Only what followers could already see: private entries (medicine,
            // the expense confirmation) stay out of their copy.
            val shared = entities.filterNot { it.sensitive }
            val doc = ReportFactory.journey(
                context, t, shared, samples, report, measures(),
                originLabel = PlaceResolver.display(t.originName, PlaceResolver.nearestSavedLabel(saved, t.originLat, t.originLng)),
                destLabel = PlaceResolver.display(t.destName, PlaceResolver.nearestSavedLabel(saved, t.destLat, t.destLng)),
                // No FASTag balance either: it is money.
                fastagSummary = null
            )
            val file = JourneyPdf.write(context, doc)
            val ok = try { cloud.uploadReport(t.accessKey, file.readBytes()) } finally { file.delete() }
            if (ok) state = state.copy(reportDone = true)
            else { save(a.tripId, state.copy(attempts = state.attempts + 1)); return }
        }

        drop(a.tripId)
    }

    // ---- persistence: one JSON record per journey ----

    private data class Pending(val approval: Approval, val analyticsDone: Boolean, val reportDone: Boolean, val attempts: Int)

    private fun save(tripId: String, p: Pending) {
        val o = JSONObject()
            .put("approvedAtMs", p.approval.approvedAtMs)
            .put("approvedBy", p.approval.approvedBy ?: JSONObject.NULL)
            .put("safeConfirmed", p.approval.safeConfirmed)
            .put("autoClosed", p.approval.autoClosed)
            .put("analyticsDone", p.analyticsDone)
            .put("reportDone", p.reportDone)
            .put("attempts", p.attempts)
        prefs.edit().putString(tripId, o.toString()).apply()
    }

    private fun load(tripId: String): Pending? = runCatching {
        val o = JSONObject(prefs.getString(tripId, null) ?: return null)
        Pending(
            Approval(
                tripId, o.getLong("approvedAtMs"),
                o.optString("approvedBy").takeUnless { o.isNull("approvedBy") || it.isBlank() },
                o.optBoolean("safeConfirmed"), o.optBoolean("autoClosed")
            ),
            o.optBoolean("analyticsDone"), o.optBoolean("reportDone"), o.optInt("attempts")
        )
    }.getOrNull()

    private fun drop(tripId: String) = prefs.edit().remove(tripId).apply()

    private companion object {
        const val TAG = "JourneyPublisher"
        const val PREFS = "tp_approved_publish"
        /** Beyond this the journey's followers have long lost access anyway. */
        const val MAX_ATTEMPTS = 40
        val BACKOFF_MS = longArrayOf(20_000, 60_000, 120_000, 300_000, 600_000, 1_200_000)
    }
}
