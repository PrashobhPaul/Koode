package com.trippulse.app.domain

import java.time.Instant
import java.time.ZoneId

/**
 * Computes a [TripSummary] from the immutable event stream plus the covered
 * distance (docs/spec/43). Pure and testable. Not framed as a medical
 * assessment — it is a behavioural journey record.
 */
object SummaryCalculator {

    fun compute(
        events: List<TripEvent>,
        distanceCoveredM: Double,
        startedAtMs: Long,
        endedAtMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): TripSummary {
        val sorted = events.sortedBy { it.eventTimeMs }

        var stops = 0
        var food = 0
        var water = 0
        var toilet = 0
        var rest = 0
        var fuel = 0
        var teaCoffee = 0
        var snacks = 0

        // Stops are paired from STOP_STARTED / STOP_ENDED event timestamps —
        // the same source JourneyAnalytics and the timeline use — so this
        // summary (embedded in TRIP_COMPLETED) never disagrees with them.
        val rawStops = ArrayList<Pair<Long, Long>>()
        var openStopStartMs: Long? = null

        val days = sortedDays(sorted, startedAtMs, endedAtMs, zone)

        for (e in sorted) {
            when (e.type) {
                EventTypes.STOP_STARTED -> {
                    stops++
                    if (openStopStartMs == null) openStopStartMs = e.eventTimeMs
                }
                EventTypes.STOP_ENDED -> {
                    openStopStartMs?.let { rawStops.add(it to e.eventTimeMs) }
                    openStopStartMs = null
                }
                EventTypes.FOOD_REPORTED -> food++
                EventTypes.WATER_REPORTED -> water++
                EventTypes.TOILET_REPORTED -> toilet++
                EventTypes.REST_REPORTED -> rest++
                EventTypes.FUEL_STOP -> fuel++
                EventTypes.TEA_COFFEE_REPORTED -> teaCoffee++
                EventTypes.SNACK_REPORTED -> snacks++
            }
        }
        openStopStartMs?.let { rawStops.add(it to endedAtMs) }

        val totalS = ((endedAtMs - startedAtMs) / 1000).coerceAtLeast(0)

        val periods = rawStops
            .map { it.first.coerceIn(startedAtMs, endedAtMs) to it.second.coerceIn(startedAtMs, endedAtMs) }
            .filter { it.second > it.first }
            .sortedBy { it.first }

        val stoppedS = periods.sumOf { (it.second - it.first) / 1000 }.coerceIn(0, totalS)
        val longestBreak = periods.maxOfOrNull { (it.second - it.first) / 1000 } ?: 0L

        // Longest continuous moving stretch = the largest gap outside any stop.
        var cursor = startedAtMs
        var longestLeg = 0L
        for ((ps, pe) in periods) {
            val stretch = ((ps - cursor) / 1000).coerceAtLeast(0)
            if (stretch > longestLeg) longestLeg = stretch
            if (pe > cursor) cursor = pe
        }
        val tail = ((endedAtMs - cursor) / 1000).coerceAtLeast(0)
        if (tail > longestLeg) longestLeg = tail

        val drivingS = (totalS - stoppedS).coerceAtLeast(0)

        return TripSummary(
            distanceKm = distanceCoveredM / 1000.0,
            drivingSeconds = drivingS,
            totalSeconds = totalS,
            stops = stops,
            foodBreaks = food,
            waterConfirmations = water,
            toiletBreaks = toilet,
            restBreaks = rest,
            fuelStops = fuel,
            teaCoffee = teaCoffee,
            snacks = snacks,
            longestLegSeconds = longestLeg.coerceAtLeast(0),
            longestBreakSeconds = longestBreak,
            days = days
        )
    }

    private fun sortedDays(
        events: List<TripEvent>,
        startedAtMs: Long,
        endedAtMs: Long,
        zone: ZoneId
    ): Int {
        val dates = HashSet<String>()
        fun add(ms: Long) {
            dates.add(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString())
        }
        add(startedAtMs); add(endedAtMs)
        events.forEach { add(it.eventTimeMs) }
        return dates.size.coerceAtLeast(1)
    }
}
