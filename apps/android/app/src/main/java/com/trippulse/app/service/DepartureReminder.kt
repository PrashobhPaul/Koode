package com.trippulse.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.trippulse.app.TripPulseApp
import java.util.concurrent.TimeUnit

/**
 * Reminder for trips scheduled ahead of time: fires a notification ~30 minutes
 * before the planned departure so the driver remembers to start tracking.
 * WorkManager persists across reboots, so scheduling once at creation is
 * enough.
 */
class DepartureReminder(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TripPulseApp ?: return Result.success()
        val tripId = inputData.getString("tripId") ?: return Result.success()
        // only remind if the trip is still waiting to be started
        val trip = app.graph.db.tripDao().byId(tripId) ?: return Result.success()
        if (trip.status != "CREATED") return Result.success()
        app.graph.notifier.showTripUpdate(
            "Trip starts soon",
            "Your scheduled trip to ${trip.destName} departs soon. Open TripPulse and tap Start when you leave."
        )
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, tripId: String, destName: String, departureMs: Long) {
            val delayMs = (departureMs - 30 * 60_000L - System.currentTimeMillis()).coerceAtLeast(0)
            val req = OneTimeWorkRequestBuilder<DepartureReminder>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putString("tripId", tripId).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("trip-reminder-$tripId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
