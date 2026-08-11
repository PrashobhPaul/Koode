package com.trippulse.app.domain

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Realistic-journey ETA engine (docs/spec/07, 83-91).
 *
 * A navigation ETA answers "how long is the remaining road under current
 * conditions". TripPulse must answer "when will this driver realistically
 * arrive, including the breaks this journey will require". So:
 *
 *   realistic ETA = travel time + future break budget + uncertainty buffer
 *
 * Output is a range, never a fake exact minute, and an overnight halt with no
 * declared restart yields null times (mode = OVERNIGHT_PENDING) rather than an
 * invented arrival.
 */
object EtaEngine {

    data class Inputs(
        val nowMs: Long,
        val remainingTravelSeconds: Long,     // from routing provider (or fallback)
        val remainingDistanceM: Double,
        val journey: JourneyStatus,
        val wellbeing: WellbeingTimes,
        val drivingSinceMs: Long?,            // start of current continuous driving leg
        val overnightPending: Boolean = false,
        val distanceCoveredM: Double = 0.0,
        val confidenceProvider: String = "fallback" // non-"fallback" (routed) -> higher confidence
    )

    fun forecast(cfg: TripConfig, i: Inputs): EtaForecast {
        if (i.journey == JourneyStatus.ARRIVED || i.journey == JourneyStatus.COMPLETED) {
            return EtaForecast(i.nowMs, i.nowMs, i.nowMs, EtaMode.ARRIVED, null, "HIGH")
        }
        if (i.overnightPending || i.journey == JourneyStatus.OVERNIGHT) {
            return EtaForecast(null, null, null, EtaMode.OVERNIGHT_PENDING, null, "LOW")
        }

        val travelS = i.remainingTravelSeconds.coerceAtLeast(0)

        // ---- future break budget ----
        val remainingDriveMin = travelS / 60
        val components = ArrayList<EtaComponent>()
        var breakBudgetS = 0L

        // Meals: if a meal-window's worth of driving remains and it's been a
        // while since food, budget a meal.
        val sinceFoodMin = minutesSince(i.nowMs, i.wellbeing.foodAtMs)
        if (remainingDriveMin >= 90 &&
            (sinceFoodMin == null || sinceFoodMin + remainingDriveMin >= cfg.foodIntervalMin)
        ) {
            val meal = cfg.mealMin * 60
            breakBudgetS += meal
            components.add(EtaComponent("Expected food", meal))
        }

        // Bio / water breaks: roughly one per toilet-interval of remaining drive.
        val bioCount = (remainingDriveMin / max(60, cfg.toiletIntervalMin)).toInt()
        if (bioCount > 0) {
            val bio = bioCount * cfg.toiletWaterMin * 60
            breakBudgetS += bio
            components.add(EtaComponent("Expected bio breaks", bio))
        }

        // Rest: if the driver has already been driving a long continuous leg, or
        // a rest interval's worth of driving remains, budget a rest.
        val contLegMin = i.drivingSinceMs?.let { (i.nowMs - it) / 60000 } ?: 0
        if (remainingDriveMin >= cfg.restIntervalMin || contLegMin >= cfg.restIntervalMin) {
            val rest = cfg.restMin * 60
            breakBudgetS += rest
            components.add(EtaComponent("Expected rest", rest))
        }

        // Fuel: budget a fuel stop per fuel-interval of remaining distance.
        val fuelStops = (i.remainingDistanceM / (cfg.fuelIntervalKm * 1000)).toInt()
        if (fuelStops > 0) {
            val fuel = fuelStops * cfg.fuelMin * 60
            breakBudgetS += fuel
            components.add(EtaComponent("Expected fuel", fuel))
        }

        // Minimum realism buffer for long trips: never present a suspiciously
        // optimistic uninterrupted-driving ETA.
        if (remainingDriveMin >= cfg.longTripThresholdDriveMin) {
            val floorS = cfg.minimumLongTripBreakBudgetMin * 60
            if (breakBudgetS < floorS) {
                val add = floorS - breakBudgetS
                breakBudgetS = floorS
                components.add(EtaComponent("Minimum break buffer", add))
            }
        }

        // ---- uncertainty ----
        val uncertaintyS = max(
            cfg.uncertaintyMinFloorMin * 60,
            (travelS * cfg.uncertaintyFraction).roundToLong()
        )
        components.add(0, EtaComponent("Road travel", travelS))

        val remainingS = travelS + breakBudgetS + uncertaintyS
        val mostLikely = i.nowMs + remainingS * 1000
        val low = i.nowMs + (travelS + breakBudgetS) * 1000
        val high = i.nowMs + (remainingS + uncertaintyS) * 1000

        val breakdown = EtaBreakdown(
            travelSeconds = travelS,
            breakBudgetSeconds = breakBudgetS,
            uncertaintySeconds = uncertaintyS,
            components = components
        )

        val confidence = when {
            i.confidenceProvider != "fallback" && remainingDriveMin < 120 -> "HIGH"
            i.confidenceProvider != "fallback" -> "MEDIUM"
            else -> "LOW"
        }

        return EtaForecast(low, high, mostLikely, EtaMode.NORMAL, breakdown, confidence)
    }

    private fun minutesSince(nowMs: Long, thenMs: Long?): Long? =
        thenMs?.let { (nowMs - it) / 60000 }
}
