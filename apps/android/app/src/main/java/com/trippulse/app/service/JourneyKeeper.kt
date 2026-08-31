package com.trippulse.app.service

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.trippulse.app.TripPulseApp
import com.trippulse.app.data.local.TripPulseDb
import com.trippulse.app.domain.Darkness
import java.util.concurrent.TimeUnit

/**
 * The journey's caretaker: a periodic check that runs whether or not anything
 * else in the app is alive.
 *
 * Two jobs, both of which exist because of things that actually went wrong.
 *
 * **Tracking that died and stayed dead.** [TripTrackingService] watches itself
 * while it is running, but a service that has been killed cannot notice that it
 * was killed. On a lot of Android phones — particularly the aggressive battery
 * managers common on the devices most of our travellers use — a foreground
 * service left alone overnight is simply removed, and START_STICKY does not
 * always bring it back. Something outside the process has to look, so this
 * does, every fifteen minutes.
 *
 * **Journeys nobody ever closed.** Only the traveller may end a journey, which
 * is right, but it means a forgotten journey stays open forever and its rows
 * pile up. After three days a journey is not a journey any more — it is a
 * record that has stopped being true — so it is removed entirely.
 *
 * No network constraint: both jobs matter most precisely when connectivity is
 * the thing that has gone wrong.
 */
class JourneyKeeperWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TripPulseApp ?: return Result.success()
        val graph = app.graph

        // Order matters: mark first, sweep second. A journey that went dark
        // must be flagged before anything is allowed to consider deleting it.
        markDarkJourneys(graph.db)
        purgeAbandoned(graph.db)

        val trip = graph.db.tripDao().activeTrip() ?: return Result.success()
        if (!isTrackingAlive(applicationContext)) {
            resumeTracking(applicationContext, trip.originName, trip.destName)
        }
        return Result.success()
    }

    /**
     * Records that a journey's device stopped reporting, when nothing said so.
     *
     * An orderly power-off announces itself and ShutdownReceiver writes the
     * marker. A phone that was stolen, smashed, drowned or simply held down on
     * the power button announces nothing at all -- and that is precisely the
     * case where the record matters most.
     *
     * So the gap itself is the trigger. This runs on the traveller's own
     * device, which means it can only notice in retrospect, once the phone is
     * back: fifteen minutes after a recovered phone boots, the journey it was
     * on is marked with when it fell silent and why. That is late for an
     * alert -- the family were told hours ago by their own phones, which is
     * the point of putting the watch there -- but exactly on time for the one
     * thing this marker is for: making sure the sweep below never touches it.
     */
    private suspend fun markDarkJourneys(db: TripPulseDb) {
        val trip = db.tripDao().activeTrip() ?: return
        if (trip.wentDarkAtMs != null) return
        val state = runCatching { db.stateDao().byId(trip.tripId) }.getOrNull() ?: return

        val assessment = Darkness.assess(
            Darkness.Inputs(
                nowMs = System.currentTimeMillis(),
                lastUpdateMs = state.lastLocationAtMs ?: state.updatedAtMs,
                lastBatteryPct = state.batteryPct,
                simChangedAtMs = trip.simChangedAtMs,
                deviationActive = state.deviationActive
            )
        )
        if (!assessment.concerning) return

        runCatching {
            db.tripDao().update(
                trip.copy(
                    wentDarkAtMs = assessment.sinceMs,
                    darkReason = assessment.reason.name
                )
            )
        }
    }

    /**
     * Deletes journeys still open [ABANDONED_AFTER_MS] after they began, and
     * everything attached to them.
     *
     * Deliberately local-only and deliberately total. The cloud copy is left to
     * its own expiry rather than deleted from here: a capability that has
     * already lapsed is unreachable anyway, and a delete that half-succeeds
     * over a flaky connection is worse than one that never started.
     */
    private suspend fun purgeAbandoned(db: TripPulseDb) {
        val cutoff = System.currentTimeMillis() - ABANDONED_AFTER_MS
        val stale = runCatching { db.tripDao().openSince(cutoff) }.getOrNull().orEmpty()
        for (t in stale) {
            runCatching {
                db.eventDao().deleteForTrip(t.tripId)
                db.locationDao().deleteForTrip(t.tripId)
                db.stateDao().delete(t.tripId)
                db.breakDao().deleteForTrip(t.tripId)
                db.expenseDao().deleteForTrip(t.tripId)
                db.legDao().deleteForTrip(t.tripId)
                db.tripDao().delete(t.tripId)
            }
        }
    }

    companion object {
        /**
         * How long a journey may stay open before it is treated as abandoned.
         * Long enough for a genuinely long haul — a three-day drive is a real
         * thing — and short enough that a forgotten one does not linger.
         */
        const val ABANDONED_AFTER_MS = 72L * 60 * 60 * 1000

        private const val UNIQUE_NAME = "koode-journey-keeper"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<JourneyKeeperWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /**
         * Whether the tracking service is currently running in this process.
         *
         * `getRunningServices` is deprecated and, since Android O, only reports
         * the caller's own services — which is exactly and only what is being
         * asked here, so the deprecation does not bite.
         */
        @Suppress("DEPRECATION")
        fun isTrackingAlive(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return true // can't tell: assume fine rather than restart blindly
            return runCatching {
                am.getRunningServices(Int.MAX_VALUE).any {
                    it.service.className == TripTrackingService::class.java.name
                }
            }.getOrDefault(true)
        }

        /**
         * Brings tracking back, or asks the traveller to.
         *
         * From Android 12 an app in the background is usually forbidden from
         * starting a foreground service, and that restriction is a good one —
         * so when it applies we do not fight it. A notification the traveller
         * can tap is honest about what happened and puts the decision where it
         * belongs, which beats silently recording nothing.
         */
        fun resumeTracking(context: Context, origin: String, destination: String) {
            val app = context.applicationContext as? TripPulseApp ?: return
            val backgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

            if (!backgroundLocation) {
                app.graph.notifier.showResumeHint(origin, destination)
                return
            }
            val started = runCatching { TripTrackingService.start(context) }.isSuccess
            if (!started) app.graph.notifier.showResumeHint(origin, destination)
        }
    }
}
