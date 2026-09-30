package com.trippulse.app.notifications

import android.content.Context
import com.trippulse.app.domain.EventNarrator
import com.trippulse.app.domain.FollowerAlerts

/**
 * The single place a followed journey's event becomes a notification on a
 * Circle member's phone — used by both delivery paths:
 *
 *  - **server push** ([com.trippulse.app.service.KoodeMessagingService]),
 *    which wakes Koode even when it is closed, and
 *  - **the in-app follow service**, which polls while Koode is running.
 *
 * Either can deliver a given event first, both usually deliver it, and the
 * server re-sends anything it could not confirm. A small on-device ledger of
 * events already shown makes that safe: each event appears once. The wording
 * is the timeline's own ([EventNarrator]), so what the Circle is told matches
 * what they see when they open the journey.
 */
object FollowerNotifications {

    private const val PREFS = "tp_follow_notified"
    private const val KEY = "keys"
    /** Far more than any journey produces in a day; oldest entries fall off. */
    private const val MAX_KEYS = 400

    private val lock = Any()

    /** Show [type] for the journey [ref] (labelled [label]); true if shown now. */
    fun notify(
        context: Context,
        notifier: Notifier,
        ref: String,
        label: String,
        type: String,
        eventTimeMs: Long,
        payload: Map<String, Any?>
    ): Boolean {
        if (!FollowerAlerts.shouldNotify(type, payload)) return false
        if (!claim(context, FollowerAlerts.dedupKey(ref, type, eventTimeMs))) return false
        val (emoji, sentence) = EventNarrator.line(type, payload)
        notifier.showJourneyEvent(
            id = FollowerAlerts.notificationId(ref, type, eventTimeMs, payload),
            title = "$emoji $sentence",
            body = label,
            urgent = FollowerAlerts.isUrgent(type)
        )
        return true
    }

    /** Record [key] as shown; false if it already was. */
    private fun claim(context: Context, key: String): Boolean = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keys = prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        if (key in keys) return false
        prefs.edit().putString(KEY, (keys + key).takeLast(MAX_KEYS).joinToString("\n")).apply()
        true
    }
}
