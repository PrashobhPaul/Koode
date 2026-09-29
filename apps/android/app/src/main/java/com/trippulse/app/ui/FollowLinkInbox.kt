package com.trippulse.app.ui

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hand-off point for a "follow this journey" link tapped from a message or a QR.
 *
 * The credential rides in the URL fragment — the part a browser never sends to
 * a server — in one of two shapes:
 *   `#<8-digit code>-<6-digit passcode>`  or  `#j=<code>&p=<passcode>`
 * MainActivity parses an inbound VIEW intent here; the Follow screen then reads
 * the stashed values and fills its fields, so tapping the link is all it takes.
 *
 * A link is only ever a *prefill*: the follower still confirms on the Follow
 * screen. The passcode may be absent (a number-only link), in which case only
 * the journey number is filled and the normal approval flow applies.
 */
object FollowLinkInbox {

    data class Prefill(val code: String, val passcode: String)

    private val _pending = MutableStateFlow<Prefill?>(null)
    val pending: StateFlow<Prefill?> = _pending

    /** Stash a follow link. Returns true if the URI actually carried a code. */
    fun offer(uri: Uri?): Boolean {
        val p = parseFragment(uri?.fragment) ?: return false
        _pending.value = p
        return true
    }

    /** Takes the pending prefill (once). */
    fun take(): Prefill? {
        val p = _pending.value
        _pending.value = null
        return p
    }

    /**
     * Pure fragment parser (no Android types), so it can be unit-tested.
     * Accepts `<code>-<passcode>` and `j=<code>&p=<passcode>`; digits only.
     */
    fun parseFragment(fragment: String?): Prefill? {
        val frag = fragment?.trim().orEmpty().removePrefix("#")
        if (frag.isEmpty()) return null

        // j=<code>&p=<passcode>
        if (frag.contains('=')) {
            val params = frag.split('&').mapNotNull { kv ->
                kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            val jc = params["j"]?.filter { it.isDigit() }
            if (!jc.isNullOrBlank()) return Prefill(jc, params["p"]?.filter { it.isDigit() }.orEmpty())
        }

        // <code>-<passcode>
        val dash = frag.split('-')
        if (dash.size == 2) {
            val c = dash[0].filter { it.isDigit() }
            if (c.isNotBlank()) return Prefill(c, dash[1].filter { it.isDigit() })
        }
        return null
    }
}
