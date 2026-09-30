package com.trippulse.app.domain

import com.trippulse.app.core.Geo

/**
 * The single source of truth for turning a journey coordinate into the label a
 * person reads. One rule, everywhere — start, stop, break, end; live UI,
 * timeline, shared message, PDF and history:
 *
 *   1. A place the user saved and named (Home, Office, Mom's house) within
 *      [SAVED_PLACE_RADIUS_M] of the coordinate.
 *   2. Otherwise a human-readable reverse-geocoded name.
 *   3. Otherwise the neutral [FALLBACK_LABEL].
 *
 * Never a raw coordinate, and never a placeholder like "Current location".
 * The user's own label always beats the geocoder's: when someone has named a
 * place, Koode speaks their language, not the map's.
 *
 * This object is pure and JVM-testable. The two side-effecting steps — reading
 * saved places (Room) and reverse-geocoding (Android Geocoder) — are performed
 * by the caller and their results passed in, so the precedence rules can be
 * tested without a device. See [LocationFix.resolveLabel] for the Android glue.
 */
object PlaceResolver {

    /** How close a coordinate must be to a saved place to take its label. */
    const val SAVED_PLACE_RADIUS_M = 500.0

    /** The neutral, human-readable label used when nothing better is known. */
    const val FALLBACK_LABEL = "Location recorded"

    /** A user-saved place, decoupled from the Room entity for testability. */
    data class SavedPlace(val label: String, val lat: Double, val lng: Double)

    /** Labels that must never reach a person as a place name. */
    private val FORBIDDEN = setOf(
        "current location", "pinned location", "pinned destination",
        "pinned start", "start point", "destination", "en route",
        "unknown location", "location unknown"
    )

    private val COORDINATE = Regex("""^-?\d{1,3}\.\d+\s*,\s*-?\d{1,3}\.\d+$""")

    /**
     * True when [name] must not be shown as a place: blank, a known placeholder,
     * or a bare "lat, lng" coordinate pair.
     */
    fun isPlaceholder(name: String?): Boolean {
        val n = name?.trim()?.lowercase() ?: return true
        if (n.isBlank()) return true
        if (n in FORBIDDEN) return true
        return COORDINATE.matches(n)
    }

    /** The nearest saved place within [radiusM] of the coordinate, or null. Pure. */
    fun nearestSavedLabel(
        places: List<SavedPlace>,
        lat: Double,
        lng: Double,
        radiusM: Double = SAVED_PLACE_RADIUS_M
    ): String? {
        val here = GeoPoint(lat, lng)
        return places
            .asSequence()
            .map { it to Geo.haversineM(here, GeoPoint(it.lat, it.lng)) }
            .filter { it.second <= radiusM && it.first.label.isNotBlank() }
            .minByOrNull { it.second }
            ?.first?.label
    }

    /**
     * The final label from an already-resolved saved match and an
     * already-fetched geocoded name: saved wins, then a real geocoded name,
     * then the neutral fallback — never a placeholder or a coordinate.
     */
    fun label(savedLabel: String?, geocoded: String?): String {
        savedLabel?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        geocoded?.trim()?.takeIf { it.isNotBlank() && !isPlaceholder(it) }?.let { return it }
        return FALLBACK_LABEL
    }

    /**
     * The label to SHOW for a name already stored on a journey. A meaningful
     * stored label is kept as-is (never corrupted); only a placeholder or a
     * bare coordinate is replaced — first by a saved-place match, then by the
     * neutral fallback. Pure and offline, so an old journey opens without a
     * network call and its meaningful labels survive untouched.
     */
    fun display(storedName: String?, savedLabel: String?): String {
        if (!isPlaceholder(storedName)) return storedName!!.trim()
        savedLabel?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return FALLBACK_LABEL
    }
}
