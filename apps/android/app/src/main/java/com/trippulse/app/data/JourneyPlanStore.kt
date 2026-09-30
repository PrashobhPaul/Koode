package com.trippulse.app.data

import android.content.Context
import com.trippulse.app.domain.JourneyPlan
import com.trippulse.app.domain.JourneyPlans
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every revision of each journey's plan, kept on the phone so the history
 * survives restarts, process death and having no signal — the traveller's
 * coach reads the current plan offline, and a revision is never overwritten.
 */
class JourneyPlanStore(context: Context) {

    private val prefs = context.getSharedPreferences("tp_journey_plan", Context.MODE_PRIVATE)
    private val _current = MutableStateFlow<JourneyPlan?>(null)

    /** The latest plan of the journey last read or revised, for screens. */
    val current: StateFlow<JourneyPlan?> = _current.asStateFlow()

    fun history(tripId: String): List<JourneyPlan> = JourneyPlans.decode(prefs.getString(tripId, null))

    fun latest(tripId: String): JourneyPlan? = history(tripId).lastOrNull().also { _current.value = it }

    fun append(tripId: String, plan: JourneyPlan) {
        val all = history(tripId).filter { it.version != plan.version } + plan
        prefs.edit().putString(tripId, JourneyPlans.encode(all)).apply()
        _current.value = plan
    }
}
