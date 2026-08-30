package com.trippulse.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How often the traveller's phone takes a location fix.
 *
 * Location is the single biggest battery cost in the app, and the right answer
 * genuinely differs per person: someone driving alone at night wants precision,
 * someone on a twelve-hour train wants their battery to survive the journey.
 * So this is a setting, not a constant — with the per-mode defaults in
 * [com.trippulse.app.domain.TransportCatalog] applied on top.
 */
enum class LocationCadence(
    val key: String,
    val label: String,
    val summary: String,
    /** Seconds between fixes while actually moving. */
    val movingS: Long,
    /** Seconds between fixes while stationary. */
    val stationaryS: Long
) {
    SAVER("SAVER", "Battery saver", "A fix every minute. Best for long train, bus and flight days.", 60, 300),
    BALANCED("BALANCED", "Balanced", "A fix every 20 seconds while moving. The right default for most journeys.", 20, 180),
    PRECISE("PRECISE", "High precision", "A fix every 8 seconds. Smoothest map, heaviest on battery.", 8, 120);

    companion object {
        val DEFAULT = BALANCED
        fun fromKey(key: String?): LocationCadence = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * How often a follower's phone asks the server for news.
 *
 * Same trade-off from the other side: a viewer watching a live drive wants
 * near-live, a viewer following a relative's overnight train does not need the
 * phone waking every five seconds.
 */
enum class ViewerRefresh(
    val key: String,
    val label: String,
    val summary: String,
    /** Seconds between live-state reads while the journey is moving. */
    val activeS: Long,
    /** Seconds between reads while the journey is stopped or resting. */
    val idleS: Long
) {
    SAVER("SAVER", "Battery saver", "Checks about once a minute.", 60, 180),
    BALANCED("BALANCED", "Balanced", "Checks every 15 seconds while they're moving.", 15, 60),
    LIVE("LIVE", "Near-live", "Checks every 5 seconds. Use it for the last stretch home.", 5, 30);

    companion object {
        val DEFAULT = BALANCED
        fun fromKey(key: String?): ViewerRefresh = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Everything the user can tune, as one immutable snapshot. */
data class KoodeSettings(
    val locationCadence: LocationCadence = LocationCadence.DEFAULT,
    val viewerRefresh: ViewerRefresh = ViewerRefresh.DEFAULT,
    /** Below this battery level the app automatically drops to the saver cadence. */
    val batterySaverBelowPct: Int = 20,
    val keepScreenOnDuringJourney: Boolean = false,
    val hapticFeedback: Boolean = true,
    val checkForUpdates: Boolean = true,
    val themeMode: String = THEME_SYSTEM
) {
    companion object {
        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_DARK = "DARK"
        const val THEME_LIGHT = "LIGHT"
    }
}

/**
 * Persistent, observable app settings.
 *
 * Deliberately a tiny wrapper over SharedPreferences rather than DataStore:
 * every consumer here needs a synchronous read (the foreground service asks for
 * the cadence on a background thread while deciding the next fix interval), and
 * the whole surface is a handful of scalars.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<KoodeSettings> = _state.asStateFlow()

    /** Current snapshot, safe to call from any thread. */
    val current: KoodeSettings get() = _state.value

    fun update(transform: (KoodeSettings) -> KoodeSettings) {
        val next = transform(_state.value)
        write(next)
        _state.value = next
    }

    private fun read(): KoodeSettings = KoodeSettings(
        locationCadence = LocationCadence.fromKey(prefs.getString(KEY_LOCATION, null)),
        viewerRefresh = ViewerRefresh.fromKey(prefs.getString(KEY_VIEWER, null)),
        batterySaverBelowPct = prefs.getInt(KEY_BATTERY_PCT, 20),
        keepScreenOnDuringJourney = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
        hapticFeedback = prefs.getBoolean(KEY_HAPTICS, true),
        checkForUpdates = prefs.getBoolean(KEY_UPDATES, true),
        themeMode = prefs.getString(KEY_THEME, KoodeSettings.THEME_SYSTEM) ?: KoodeSettings.THEME_SYSTEM
    )

    private fun write(s: KoodeSettings) {
        prefs.edit()
            .putString(KEY_LOCATION, s.locationCadence.key)
            .putString(KEY_VIEWER, s.viewerRefresh.key)
            .putInt(KEY_BATTERY_PCT, s.batterySaverBelowPct)
            .putBoolean(KEY_KEEP_SCREEN_ON, s.keepScreenOnDuringJourney)
            .putBoolean(KEY_HAPTICS, s.hapticFeedback)
            .putBoolean(KEY_UPDATES, s.checkForUpdates)
            .putString(KEY_THEME, s.themeMode)
            .apply()
    }

    private companion object {
        const val PREFS = "koode_settings"
        const val KEY_LOCATION = "location_cadence"
        const val KEY_VIEWER = "viewer_refresh"
        const val KEY_BATTERY_PCT = "battery_saver_below"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_HAPTICS = "haptics"
        const val KEY_UPDATES = "check_updates"
        const val KEY_THEME = "theme_mode"
    }
}
