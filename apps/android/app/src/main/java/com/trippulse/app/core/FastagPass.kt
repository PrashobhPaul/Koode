package com.trippulse.app.core

import android.content.Context
import com.trippulse.app.domain.fastag.PassLedger
import com.trippulse.app.domain.fastag.TollPassType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The traveller's FASTag annual-pass balance — optional, user-owned live state.
 *
 * It is never a system constant: the user sets a starting balance (200, 147,
 * whatever their pass is), qualifying crossings decrement it, and the user can
 * correct it at any time, which simply becomes the new baseline. Until the user
 * configures one, there is no balance and nothing is shown — Koode never
 * fabricates a remaining number. Global here (there is no per-vehicle registry
 * yet); a per-plate balance can follow when vehicles become first-class.
 */
class FastagPass(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    /**
     * @param configured false until the user sets a balance — the signal to
     *   show no balance at all rather than a made-up zero.
     */
    data class State(
        val configured: Boolean,
        val balance: Int,
        val lastManualMs: Long?,
        val lastAutoMs: Long?
    )

    /** User sets or corrects the balance — establishes a new baseline. */
    fun setBalance(value: Int) = write(
        _state.value.copy(
            configured = true,
            balance = value.coerceAtLeast(0),
            lastManualMs = System.currentTimeMillis()
        )
    )

    /** User clears the pass (e.g. no longer on an annual pass). */
    fun clear() = write(State(configured = false, balance = 0, lastManualMs = null, lastAutoMs = _state.value.lastAutoMs))

    /**
     * Apply a recorded crossing. Returns the new balance, or null when no pass
     * is configured (so callers show a toll count only, not a balance). A
     * non-qualifying crossing leaves the balance untouched.
     */
    fun applyCrossing(passType: TollPassType): Int? {
        val s = _state.value
        if (!s.configured) return null
        if (!PassLedger.qualifies(passType)) return s.balance
        val next = PassLedger.next(s.balance, passType)
        write(s.copy(balance = next, lastAutoMs = System.currentTimeMillis()))
        return next
    }

    private fun read(): State = State(
        configured = prefs.getBoolean(KEY_CONFIGURED, false),
        balance = prefs.getInt(KEY_BALANCE, 0),
        lastManualMs = prefs.getLong(KEY_LAST_MANUAL, 0L).takeIf { it > 0L },
        lastAutoMs = prefs.getLong(KEY_LAST_AUTO, 0L).takeIf { it > 0L }
    )

    private fun write(s: State) {
        prefs.edit()
            .putBoolean(KEY_CONFIGURED, s.configured)
            .putInt(KEY_BALANCE, s.balance)
            .putLong(KEY_LAST_MANUAL, s.lastManualMs ?: 0L)
            .putLong(KEY_LAST_AUTO, s.lastAutoMs ?: 0L)
            .apply()
        _state.value = s
    }

    private companion object {
        const val PREFS = "koode_fastag"
        const val KEY_CONFIGURED = "configured"
        const val KEY_BALANCE = "balance"
        const val KEY_LAST_MANUAL = "last_manual_ms"
        const val KEY_LAST_AUTO = "last_auto_ms"
    }
}
