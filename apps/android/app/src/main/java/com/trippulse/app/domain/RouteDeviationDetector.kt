package com.trippulse.app.domain

import com.trippulse.app.core.Geo

/**
 * Route deviation detector (docs/spec/21). Only meaningful when a real route
 * polyline exists; with the haversine fallback there is no corridor to deviate
 * from, so [hasRoute] must be false and detection is disabled.
 *
 * A deviation is only reported after the driver has been off-corridor for a
 * sustained period, since a fuel/food/toilet detour is a normal reason to leave
 * the line briefly. Recovery uses half the threshold to avoid flapping.
 */
class RouteDeviationDetector(private val cfg: TripConfig = TripConfig.DEFAULT) {

    sealed interface Signal {
        data class Deviated(val distanceM: Double) : Signal
        object Rejoined : Signal
    }

    var active: Boolean = false
        private set

    private var offSinceMs: Long? = null

    fun onFix(point: GeoPoint, route: List<GeoPoint>, nowMs: Long): Signal? {
        if (route.size < 2) return null
        val dist = Geo.minDistanceToPathM(point, route)

        if (!active) {
            if (dist > cfg.deviationThresholdM) {
                if (offSinceMs == null) offSinceMs = nowMs
                val persistedS = (nowMs - (offSinceMs ?: nowMs)) / 1000
                if (persistedS >= cfg.deviationPersistS) {
                    active = true
                    offSinceMs = null
                    return Signal.Deviated(dist)
                }
            } else {
                offSinceMs = null
            }
        } else {
            // recover once comfortably back inside the corridor
            if (dist <= cfg.deviationThresholdM / 2) {
                active = false
                offSinceMs = null
                return Signal.Rejoined
            }
        }
        return null
    }
}
