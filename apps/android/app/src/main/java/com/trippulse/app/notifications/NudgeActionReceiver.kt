package com.trippulse.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trippulse.app.TripPulseApp
import com.trippulse.app.domain.WellbeingCoach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the buttons on a wellbeing suggestion: "Had water" / "Ate
 * something" log it exactly as the in-app buttons would (so followers see it
 * and the coach's cycle restarts), "Taking a break" tells the coach a stop is
 * coming, and "Remind me later" snoozes it. Also "Just a break" on the halt
 * question.
 */
class NudgeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? TripPulseApp ?: return
        val graph = app.graph
        val need = WellbeingCoach.Need.fromKey(intent.getStringExtra(EXTRA_NEED))
        if (intent.action == ACTION_HALT_DECLINE) graph.notifier.cancelHaltQuestion()
        else if (need == null) return
        else graph.notifier.cancelWellbeingNudge(need.key)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (graph.tripManager.currentTripIdOrNull() == null) graph.tripManager.loadActive()
                when (intent.action) {
                    ACTION_DONE -> need?.let { graph.tripManager.logNeedMet(it) }
                    ACTION_SNOOZE -> need?.let { graph.tripManager.snoozeNudge(it) }
                    ACTION_HALT_DECLINE -> graph.tripManager.declineHalt()
                }
            } catch (_: Exception) {
                // A lost tap must never disturb the journey itself.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "app.koode.NUDGE_DONE"
        const val ACTION_SNOOZE = "app.koode.NUDGE_SNOOZE"
        /** "Just a break" on the halt question. */
        const val ACTION_HALT_DECLINE = "app.koode.HALT_DECLINE"
        const val EXTRA_NEED = "need"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
