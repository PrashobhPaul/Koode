package com.trippulse.app.ui

import android.content.Context
import com.trippulse.app.data.routing.GoogleMapsLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hand-off point for places coming back from Google Maps.
 *
 * The traveller taps "Choose in Google Maps" on a field, finds the place in
 * the Maps app, then either taps Share → Koode or copies the link and comes
 * back. Both paths end here: a shared text arrives via MainActivity; a copied
 * link is picked up from the clipboard when Koode regains focus. The screen
 * that asked then fills in the waiting field.
 *
 * The waiting state — which field, since when, and what the clipboard held
 * before leaving — is persisted on the phone, because Android often closes
 * Koode in the background while the (heavy) Maps app is open. Without that,
 * a share would land in the wrong field and a copied link would be ignored.
 *
 * Only text carrying a Maps link or coordinates is accepted, a hand-off
 * expires after [AWAIT_WINDOW_MS], and the same link is never applied twice.
 */
object SharedPlaceInbox {

    data class Target(val legIndex: Int, val asStart: Boolean)

    /** Where the waiting state lives: SharedPreferences on a phone, memory in tests. */
    interface Store {
        fun getString(key: String): String?
        fun putString(key: String, value: String?)
    }

    private class MemoryStore : Store {
        private val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    private const val AWAIT_WINDOW_MS = 20 * 60_000L
    private const val K_TARGET = "target"
    private const val K_SINCE = "awaitingSince"
    private const val K_CLIP_BEFORE = "clipBefore"
    private const val K_LAST = "lastConsumed"

    @Volatile private var store: Store = MemoryStore()
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** Called once from the Application so the waiting state survives process death. */
    fun attach(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("koode_place_handoff", Context.MODE_PRIVATE)
        store = object : Store {
            override fun getString(key: String) = prefs.getString(key, null)
            override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
        }
    }

    internal fun useStoreForTest(s: Store) { store = s; _pending.value = null; pickerOpen = false }

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    /** True while a picker is on screen; it then takes shares for its own field. */
    @Volatile
    var pickerOpen: Boolean = false

    /** The field waiting for a place (persisted). Null means "destination of stage 1". */
    var target: Target?
        get() = store.getString(K_TARGET)?.split(":")?.takeIf { it.size == 2 }
            ?.let { (i, s) -> i.toIntOrNull()?.let { Target(it, s == "1") } }
        set(value) = store.putString(K_TARGET, value?.let { "${it.legIndex}:${if (it.asStart) 1 else 0}" })

    var lastConsumed: String?
        get() = store.getString(K_LAST)
        set(value) = store.putString(K_LAST, value)

    fun looksLikePlace(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        return t.isNotEmpty() && (GoogleMapsLink.extractUrl(t) != null || GoogleMapsLink.coordinates(t) != null)
    }

    /** The traveller is leaving for Google Maps; remember what the clipboard held. */
    fun beginGoogleHandoff(clipboardBefore: String?) {
        store.putString(K_SINCE, clock().toString())
        store.putString(K_CLIP_BEFORE, clipboardBefore?.trim())
    }

    fun isAwaiting(): Boolean {
        val since = store.getString(K_SINCE)?.toLongOrNull() ?: return false
        return clock() - since in 0..AWAIT_WINDOW_MS
    }

    /**
     * Koode regained focus after a Google hand-off: if the clipboard now holds
     * a place that wasn't there before leaving, treat it exactly like a share.
     */
    fun acceptReturnedClip(clip: String?): Boolean {
        val c = clip?.trim().orEmpty()
        if (!isAwaiting() || !looksLikePlace(c)) return false
        if (c == store.getString(K_CLIP_BEFORE) || c == lastConsumed) return false
        return offer(c)
    }

    fun offer(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (!looksLikePlace(t) || t == _pending.value) return false
        _pending.value = t
        return true
    }

    /** Takes the pending place (once) together with where it should go. */
    fun take(): Pair<String, Target>? {
        val text = _pending.value ?: return null
        _pending.value = null
        lastConsumed = text
        val where = target ?: Target(0, asStart = false)
        target = null
        store.putString(K_SINCE, null)
        store.putString(K_CLIP_BEFORE, null)
        return text to where
    }
}
