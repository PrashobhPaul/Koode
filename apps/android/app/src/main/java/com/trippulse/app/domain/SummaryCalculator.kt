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
        var longestBreak = 0L

        // longest continuous driving leg: time between a STOP_ENDED/START and
        // the next STOP_STARTED (or trip end).
        var legStart = startedAtMs
        var longestLeg = 0L

        val days = sortedDays(sorted, startedAtMs, endedAtMs, zone)

        for (e in sorted) {
            when (e.type) {
                EventTypes.STOP_STARTED -> {
                    stops++
                    val leg = e.eventTimeMs - legStart
                    if (leg > longestLeg) longestLeg = leg
                }
                EventTypes.STOP_ENDED -> {
                    val durS = (e.payload["durationSeconds"] as? Number)?.toLong() ?: 0L
                    if (durS > longestBreak) longestBreak = durS
                    legStart = e.eventTimeMs
                }
                EventTypes.FOOD_REPORTED -> food++
                EventTypes.WATER_REPORTED -> water++
                EventTypes.TOILET_REPORTED -> toilet++
                EventTypes.REST_REPORTED -> rest++
                EventTypes.FUEL_STOP -> fuel++
            }
        }
        // final leg to trip end
        val lastLeg = endedAtMs - legStart
        if (lastLeg > longestLeg) longestLeg = lastLeg

        val totalS = ((endedAtMs - startedAtMs) / 1000).coerceAtLeast(0)
        // driving time = total minus the sum of confirmed stop durations
        val stoppedS = sorted.filter { it.type == EventTypes.STOP_ENDED }
            .sumOf { (it.payload["durationSeconds"] as? Number)?.toLong() ?: 0L }
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
            longestLegSeconds = (longestLeg / 1000).coerceAtLeast(0),
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
