package com.trippulse.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.trippulse.app.R
import com.trippulse.app.domain.ClockPreference
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.UnitPreference

/**
 * The names the screens use for things the catalogue keys: ways of
 * travelling, the settings choices. The catalogue keeps its English label
 * (reports and tests read it); the screen reads the translation. A new
 * language fills `values-<lang>/strings.xml` and nothing here changes.
 */
object Names {

    private val MODE = mapOf(
        "CAR" to R.string.mode_car, "BIKE" to R.string.mode_bike, "CYCLE" to R.string.mode_cycle,
        "CAB" to R.string.mode_cab, "AUTO" to R.string.mode_auto, "BUS" to R.string.mode_bus,
        "METRO" to R.string.mode_metro, "TRAIN" to R.string.mode_train, "FLIGHT" to R.string.mode_flight,
        "FERRY" to R.string.mode_ferry, "SHIP" to R.string.mode_ship, "WALK" to R.string.mode_walk
    )

    /** "Car", "Motorbike", "Ferry / boat": the way of travelling, in the reader's language. */
    @Composable
    fun mode(key: String?): String {
        val k = TransportCatalog.profile(key).key
        val res = MODE[k] ?: return TransportCatalog.label(key)
        return stringResource(res)
    }

    @Composable
    fun unit(p: UnitPreference): String = stringResource(
        when (p) {
            UnitPreference.AUTO -> R.string.pref_match_region
            UnitPreference.METRIC -> R.string.pref_units_metric
            UnitPreference.IMPERIAL -> R.string.pref_units_imperial
        }
    )

    @Composable
    fun clock(p: ClockPreference): String = stringResource(
        when (p) {
            ClockPreference.AUTO -> R.string.pref_match_region
            ClockPreference.TWELVE_HOUR -> R.string.pref_clock_12
            ClockPreference.TWENTY_FOUR_HOUR -> R.string.pref_clock_24
        }
    )
}
