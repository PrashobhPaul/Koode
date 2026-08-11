package com.trippulse.app.domain

import com.trippulse.app.core.Geo

/**
 * Movement / stop detector (docs/spec/12, 24). Combines GPS speed with
 * displacement and dwell time so that traffic lights and jams are not reported
 * as breaks, while genuine multi-minute stops are. Activity Recognition can be
 * fed in as a corroborating hint but is never required.
 *
 * This class is a pure state object driven by [onFix] and [onTick]; it holds no
 * Android references and is fully unit-testable.
 */
class StopDetector(private val cfg: TripConfig = TripConfig.DEFAULT) {

    sealed interface Movement {
        object None : Movement
        object DrivingStarted : Movement
        object StopStarted : Movement
        data class StopEnded(val durationS: Long) : Movement
        object LongStop : Movement
    }

    enum class Phase { UNKNOWN, MOVING, CANDIDATE, STOPPED, LONG }

    var phase: Phase = Phase.UNKNOWN
        private set

    private var lastFix: Fix? = null
    private var stationarySinceMs: Long? = null
    private var stopAnchor: GeoPoint? = null
    private var stopConfirmedAtMs: Long? = null
    private var longStopEmitted = false
    private var inVehicleHint: Boolean? = null   // from Activity Recognition

    /** Optional corroborating signal. true = likely in vehicle. */
    fun onActivityHint(inVehicle: Boolean) { inVehicleHint = inVehicle }

    private fun speedKmh(fix: Fix): Double {
        val s = fix.speedMps
        if (s != null && s >= 0f) return s * 3.6
        // Derive from displacement when the provider gives no speed.
        val prev = lastFix ?: return 0.0
        val dtS = (fix.timeMs - prev.timeMs) / 1000.0
        if (dtS <= 0) return 0.0
        val d = Geo.haversineM(prev.point, fix.point)
        return (d / dtS) * 3.6
    }

    /** Feed a new location fix. Returns a [Movement] transition if one occurred. */
    fun onFix(fix: Fix): Movement? {
        val prev = lastFix
        val kmh = speedKmh(fix)
        val moving = kmh >= cfg.restartSpeedKmh ||
            (prev != null && Geo.haversineM(prev.point, fix.point) > cfg.restartDisplacementM &&
                phase != Phase.MOVING)

        var out: Movement? = null

        when (phase) {
            Phase.UNKNOWN -> {
                if (kmh >= cfg.restartSpeedKmh) {
                    phase = Phase.MOVING
                    out = Movement.DrivingStarted
                }
            }

            Phase.MOVING -> {
                if (kmh < cfg.possibleStopSpeedKmh) {
                    // begin measuring a possible stop
                    if (stationarySinceMs == null) {
                        stationarySinceMs = fix.timeMs
                        stopAnchor = fix.point
                    }
                    phase = Phase.CANDIDATE
                }
            }

            Phase.CANDIDATE -> {
                val anchor = stopAnchor
                val drift = if (anchor != null) Geo.haversineM(anchor, fix.point) else 0.0
                if (kmh >= cfg.restartSpeedKmh || drift > cfg.stopDisplacementM) {
                    // false alarm (rolled forward) -> back to moving
                    resetStationary()
                    phase = Phase.MOVING
                } else {
                    val since = stationarySinceMs ?: fix.timeMs
                    val dwellS = (fix.timeMs - since) / 1000
                    if (dwellS >= cfg.stopConfirmAfterS) {
                        phase = Phase.STOPPED
                        stopConfirmedAtMs = since       // stop began when we went stationary
                        out = Movement.StopStarted
                    }
                }
            }

            Phase.STOPPED, Phase.LONG -> {
                val anchor = stopAnchor
                val drift = if (anchor != null) Geo.haversineM(anchor, fix.point) else 0.0
                if (kmh >= cfg.restartSpeedKmh || drift > cfg.restartDisplacementM) {
                    val began = stopConfirmedAtMs ?: fix.timeMs
                    val durationS = (fix.timeMs - began) / 1000
                    resetStationary()
                    stopConfirmedAtMs = null
                    longStopEmitted = false
                    phase = Phase.MOVING
                    out = Movement.StopEnded(durationS)
                }
            }
        }

        lastFix = fix
        return out
    }

    /**
     * Time-based tick (no new fix). Emits [Movement.StopStarted] when a
     * candidate matures purely on dwell time, and [Movement.LongStop] once a
     * confirmed stop crosses the long-stop threshold.
     */
    fun onTick(nowMs: Long): Movement? {
        when (phase) {
            Phase.CANDIDATE -> {
                val since = stationarySinceMs ?: return null
                val dwellS = (nowMs - since) / 1000
                if (dwellS >= cfg.stopConfirmAfterS) {
                    phase = Phase.STOPPED
                    stopConfirmedAtMs = since
                    return Movement.StopStarted
                }
            }
            Phase.STOPPED -> {
                val began = stopConfirmedAtMs ?: return null
                val durS = (nowMs - began) / 1000
                if (!longStopEmitted && durS >= cfg.longStopAfterS) {
                    longStopEmitted = true
                    phase = Phase.LONG
                    return Movement.LongStop
                }
            }
            else -> {}
        }
        return null
    }

    /** Whether we currently believe the vehicle is stationary (any stop phase). */
    fun isStationary(): Boolean =
        phase == Phase.STOPPED || phase == Phase.LONG || phase == Phase.CANDIDATE

    fun stopStartedAtMs(): Long? = stopConfirmedAtMs

    private fun resetStationary() {
        stationarySinceMs = null
        stopAnchor = null
    }
}
