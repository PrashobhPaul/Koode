package com.trippulse.app.domain

/**
 * All tunable thresholds live here as product defaults (docs/spec/19, 07, 24).
 * These are explicitly NOT medical recommendations — they are planning
 * defaults, and every one of them is overridable so behaviour can be tuned
 * from real-world testing without touching engine logic.
 */
data class TripConfig(
    // ---- stop detection ----
    val possibleStopSpeedKmh: Double = 3.0,
    val possibleStopAfterS: Long = 180,          // 3 min stationary -> POSSIBLE_STOP
    val stopConfirmAfterS: Long = 300,           // 5 min -> STOPPED (traffic protection)
    val stopDisplacementM: Double = 60.0,        // max drift still considered stationary
    val restartSpeedKmh: Double = 8.0,           // resume threshold
    val restartDisplacementM: Double = 150.0,    // or moved this far from stop point
    val longStopAfterS: Long = 7200,             // 2 h -> LONG_STOP / overnight candidate

    // ---- arrival ----
    val arrivalRadiusM: Double = 300.0,
    val arrivalConfirmS: Long = 120,
    val autoCompleteGraceS: Long = 1200,         // auto-complete 20 min after arrival

    // ---- location sampling (seconds between fixes) ----
    val samplingDrivingS: Long = 15,
    val samplingStationaryS: Long = 120,
    val samplingOvernightS: Long = 600,
    val samplingSosS: Long = 5,
    val samplingPausedS: Long = 300,
    val lowBatteryPct: Int = 20,
    val lowBatterySamplingS: Long = 45,          // back off when battery low

    // ---- ETA / break budget ----
    val minimumLongTripBreakBudgetMin: Long = 60,
    val longTripThresholdDriveMin: Long = 180,
    val shortRefreshmentMin: Long = 15,
    val toiletWaterMin: Long = 15,
    val mealMin: Long = 30,
    val restMin: Long = 30,
    val fuelMin: Long = 15,
    val uncertaintyMinFloorMin: Long = 20,
    val uncertaintyFraction: Double = 0.08,      // 8% of remaining travel time

    // ---- wellbeing prompt intervals ----
    val hydrationIntervalMin: Long = 120,
    val foodIntervalMin: Long = 300,
    val restIntervalMin: Long = 150,
    val toiletIntervalMin: Long = 240,
    val fuelIntervalKm: Double = 450.0,

    // ---- route deviation ----
    val deviationThresholdM: Double = 1200.0,
    val deviationPersistS: Long = 300,

    // ---- freshness (viewer) ----
    val freshnessLiveS: Long = 60,
    val freshnessRecentS: Long = 300,
    val freshnessStaleS: Long = 900,

    // ---- sync ----
    val currentStateMinIntervalS: Long = 10,     // throttle live-state pushes
    val locationUploadBatch: Int = 60,
    val locationCompactionThreshold: Int = 800,  // compact old samples beyond this
    val heartbeatIntervalS: Long = 30,

    // ---- credentials / retention ----
    /**
     * How long a journey stays readable after its traveller ends it.
     *
     * An hour, because the arrival is exactly when people open the app: a
     * follower who was asleep, in a meeting or on a plane should still be able
     * to see that the person got there safely, rather than a dead link.
     */
    val expiryGraceMin: Long = 60,

    // ---- routing fallback ----
    val fallbackAvgSpeedKmh: Double = 52.0,
    val roadDistanceFactor: Double = 1.27,       // haversine -> road distance
    val routeRefreshMin: Long = 10
) {
    val minStopDurationForBreakS: Long get() = stopConfirmAfterS

    companion object {
        val DEFAULT = TripConfig()
    }
}
