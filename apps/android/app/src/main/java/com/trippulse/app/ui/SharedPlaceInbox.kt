package com.trippulse.app.ui

import com.trippulse.app.data.routing.GoogleMapsLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hand-off point for places shared into Koode from Google Maps.
 *
 * The flow is: the traveller taps "Choose in Google Maps" on a From/To field
 * (which records that field as the [target]), finds the place in the Maps app
 * they already know, taps Share → Koode. Android delivers the text to
 * MainActivity, which drops it here; the planning screen picks it up and fills
 * in the waiting field. No Google API, key or billing is involved — it is the
 * ordinary share sheet.
 *
 * Shared text that carries neither a Maps link nor coordinates is ignored, so
 * sharing an unrelated note to Koode does nothing surprising.
 */
object SharedPlaceInbox {

    data class Target(val legIndex: Int, val asStart: Boolean)

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    /** The field waiting for a place; destination of the first stage if none. */
    @Volatile
    var target: Target? = null

    fun offer(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return false
        if (GoogleMapsLink.extractUrl(t) == null && GoogleMapsLink.coordinates(t) == null) return false
        _pending.value = t
        return true
    }

    /** Takes the pending share (once) together with where it should go. */
    fun take(): Pair<String, Target>? {
        val text = _pending.value ?: return null
        _pending.value = null
        val where = target ?: Target(0, asStart = false)
        target = null
        return text to where
    }
}
