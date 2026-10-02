package com.trippulse.app.service

import android.content.Context
import com.trippulse.app.TripPulseApp

/**
 * Brings tracking back the moment the traveller looks at the app.
 *
 * Every live update a family sees -- position, heartbeat, the breaks and halts
 * the traveller logs -- is pushed from [TripTrackingService]'s tick. If that
 * service has been killed (a force stop, an aggressive battery manager, a
 * crash the sticky restart never recovered from) the journey stays open on the
 * phone and silently stops updating anyone else. Until this existed, nothing
 * restarted it when the app came back to the front: only the fifteen-minute
 * keeper looked, and from the background it is often not allowed to start a
 * location service at all, so it could only post a notification.
 *
 * The foreground is exactly where Android does allow the start, so the app
 * itself does it: on every resume and on the journey screen, with no question
 * asked -- the traveller already said "start the trip", and a trip that is
 * open is a trip that should be tracking.
 */
object TrackingResume {

    /**
     * Starts the tracking service if a journey is open and the service is not
     * running. Returns true when a restart was issued.
     */
    suspend fun ensureRunning(context: Context): Boolean {
        val app = context.applicationContext as? TripPulseApp ?: return false
        val trip = runCatching { app.graph.db.tripDao().activeTrip() }.getOrNull() ?: return false
        if (JourneyKeeperWorker.isTrackingAlive(context)) return false
        val started = runCatching { TripTrackingService.start(context) }.isSuccess
        if (!started) app.graph.notifier.showResumeHint(trip.originName, trip.destName)
        return started
    }

    /** Whether the service is running; cheap enough to poll from a screen. */
    fun isAlive(context: Context): Boolean = JourneyKeeperWorker.isTrackingAlive(context)
}
