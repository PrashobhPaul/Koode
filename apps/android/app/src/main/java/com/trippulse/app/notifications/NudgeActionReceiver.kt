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
        val expenseTrip = intent.getStringExtra(EXTRA_TRIP)
        val opportunity = intent.getStringExtra(EXTRA_OPPORTUNITY)
        if (intent.action in EXPENSE_ACTIONS) {
            if (expenseTrip == null || opportunity == null) return
            val amount = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(KEY_AMOUNT)?.toString()
                ?.replace(",", "")?.trim()?.toDoubleOrNull()
            val pendingExpense = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val tm = graph.tripManager
                    when (intent.action) {
                        ACTION_EXPENSE_AMOUNT ->
                            if (amount != null) tm.recordExpenseAmount(expenseTrip, opportunity, amount)
                            else tm.deferExpense(expenseTrip, opportunity)
                        ACTION_EXPENSE_NONE -> tm.markNoExpense(expenseTrip, opportunity)
                        ACTION_EXPENSE_SKIP -> tm.deferExpense(expenseTrip, opportunity)
                    }
                } catch (_: Exception) {
                    // A lost tap leaves the expense open for the review.
                } finally {
                    pendingExpense.finish()
                }
            }
            return
        }
        if (intent.action == ACTION_HALT_DECLINE) graph.notifier.cancelHaltQuestion()
        else if (intent.action == ACTION_STILL_TRAVELLING) graph.notifier.cancelClosePrompt()
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
                    ACTION_STILL_TRAVELLING -> graph.tripManager.dismissArrivalPrompt()
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
        // Expense prompts: an amount typed into the notification, "No
        // expense", or "Skip for now" (kept open for the review).
        const val ACTION_EXPENSE_AMOUNT = "app.koode.EXPENSE_AMOUNT"
        const val ACTION_EXPENSE_NONE = "app.koode.EXPENSE_NONE"
        const val ACTION_EXPENSE_SKIP = "app.koode.EXPENSE_SKIP"
        private val EXPENSE_ACTIONS = setOf(ACTION_EXPENSE_AMOUNT, ACTION_EXPENSE_NONE, ACTION_EXPENSE_SKIP)
        const val EXTRA_TRIP = "trip"
        const val EXTRA_OPPORTUNITY = "opportunity"
        const val KEY_AMOUNT = "amount"
        /** "I'm still travelling" on the close prompt: the journey stays open. */
        const val ACTION_STILL_TRAVELLING = "app.koode.STILL_TRAVELLING"
        const val EXTRA_NEED = "need"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
