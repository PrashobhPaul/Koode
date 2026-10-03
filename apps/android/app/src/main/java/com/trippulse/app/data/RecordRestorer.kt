package com.trippulse.app.data

import android.content.Context
import com.trippulse.app.data.export.ApprovedJourneyPublisher
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.TripPulseDb
import com.trippulse.app.data.sync.SyncEngine
import com.trippulse.app.domain.JourneyClosure

/**
 * Makes sure a journey's record on the phone is whole.
 *
 * Builds before 6.23.2 trimmed the local path to its last few hundred samples
 * once they were synced, which left the reports with a stub of the journey
 * and the distance ledger with nothing to credit. The cloud kept everything.
 * This fetches it back once per journey, corrects the distance the journey is
 * known by, and -- for a journey already approved -- sends the followers the
 * corrected analytics and report.
 */
class RecordRestorer(
    private val context: Context,
    private val db: TripPulseDb,
    private val sync: SyncEngine,
    private val tripManager: TripManager,
    private val publisher: ApprovedJourneyPublisher
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the record changed and the caller should read it again. */
    suspend fun restore(trip: ActiveTripEntity): Boolean {
        if (!trip.cloudEnabled || prefs.getBoolean(trip.tripId, false)) return false
        val before = db.stateDao().byId(trip.tripId)?.distanceCoveredM ?: 0.0
        val added = runCatching { sync.restoreLocations(trip) }.getOrNull() ?: return false
        prefs.edit().putBoolean(trip.tripId, true).apply()
        if (added == 0) return false
        val after = tripManager.reconcileDistance(trip.tripId)
        val closure = tripManager.closureRecord(trip.tripId)
        if (closure?.stage == JourneyClosure.Lifecycle.FINALIZED && closure.approvedAtMs != null &&
            after > before * 1.02 + 500) {
            publisher.enqueue(ApprovedJourneyPublisher.Approval(
                trip.tripId, closure.approvedAtMs, closure.approvedBy, closure.safeConfirmed, closure.auto
            ))
        }
        return true
    }

    private companion object { const val PREFS = "tp_record_restored" }
}
