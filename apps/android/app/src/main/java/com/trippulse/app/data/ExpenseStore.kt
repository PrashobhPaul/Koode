package com.trippulse.app.data

import android.content.Context
import com.trippulse.app.domain.Expenses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Each journey's expense opportunities, on the phone only. Amounts, once
 * recorded, live as expense rows; this keeps what was noticed and how the
 * traveller answered — so a skipped prompt is never lost or mistaken for ₹0.
 */
class ExpenseStore(context: Context) {

    private val prefs = context.getSharedPreferences("tp_expense_opportunities", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0L)

    /** Bumps on every change, so screens can re-read. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun all(tripId: String): List<Expenses.Opportunity> = Expenses.decode(prefs.getString(tripId, null))

    fun save(tripId: String, list: List<Expenses.Opportunity>) {
        prefs.edit().putString(tripId, Expenses.encode(list)).apply()
        _version.value = _version.value + 1
    }

    fun update(tripId: String, id: String, change: (Expenses.Opportunity) -> Expenses.Opportunity): Expenses.Opportunity? {
        var changed: Expenses.Opportunity? = null
        save(tripId, all(tripId).map { if (it.id == id) change(it).also { c -> changed = c } else it })
        return changed
    }
}
