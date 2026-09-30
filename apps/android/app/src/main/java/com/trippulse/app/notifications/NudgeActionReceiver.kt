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
 * Handles the buttons on a wellbeing nudge: "Had water" / "Ate something"
 * log it exactly as the in-app buttons would (so the circle sees it and the
 * coach's ladder resets), and "Remind me later" snoozes the current step.
 */
class NudgeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? TripPulseApp ?: return
        val need = WellbeingCoach.Need.fromKey(intent.getStringExtra(EXTRA_NEED)) ?: return
        val graph = app.graph
        graph.notifier.cancelWellbeingNudge(need.key)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (graph.tripManager.currentTripIdOrNull() == null) graph.tripManager.loadActive()
                when (intent.action) {
                    ACTION_DONE -> graph.tripManager.logNeedMet(need)
                    ACTION_SNOOZE -> graph.tripManager.snoozeNudge(need)
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
        const val EXTRA_NEED = "need"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
