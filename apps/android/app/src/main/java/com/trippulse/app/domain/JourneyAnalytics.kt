package com.trippulse.app.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Turns a finished journey's raw log into the things a person actually wants
 * to know.
 *
 * Collecting events is the easy half. The half that matters is answering
 * "how did that go?" without the reader doing arithmetic: how much of the
 * elapsed time was actually spent moving, how often breaks came, what the
 * journey cost per kilometre, whether the car did better or worse than usual.
 * Every screen and every export renders the same [JourneyReport], so the
 * dashboard and the PDF can never disagree.
 *
 * Pure and unit-tested. No Android, no formatting — [Measures] turns these
 * numbers into the traveller's own units and currency at the edge.
 */
object JourneyAnalytics {

    /** One line of a cost breakdown. */
    data class CostLine(val type: String, val label: String, val amount: Double, val share: Double)

    /** An expense as the analyser needs it, decoupled from the Room entity. */
    data class ExpenseInput(
        val type: String,
        val item: String,
        val amount: Double,
        val quantity: Double?,
        val unit: String?,
        val atMs: Long
    )

    /** One stage of a hybrid journey, as far as the analyser cares. */
    data class LegInput(
        val index: Int,
        val mode: String,
        val fromName: String,
        val toName: String,
        val startedAtMs: Long?,
        val completedAtMs: Long?
    )

    data class Inputs(
        val events: List<TripEvent>,
        val distanceCoveredM: Double,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val expenses: List<ExpenseInput> = emptyList(),
        val legs: List<LegInput> = emptyList(),
        val transportMode: String = "CAR",
        /** Top speed seen during the journey, km/h, when the log kept one. */
        val topSpeedKmh: Double? = null,
        /** Day boundaries follow the traveller's own clock, not UTC. */
        val zone: ZoneId = ZoneId.systemDefault()
    )

    /** Everything derived, in one object. */
    data class JourneyReport(
        // ---- time ----
        val totalSeconds: Long,
        val movingSeconds: Long,
        val stoppedSeconds: Long,
        /** Share of the elapsed journey actually spent moving, 0..1. */
        val movingShare: Double,
        val longestLegSeconds: Long,
        val longestBreakSeconds: Long,
        val days: Int,

        // ---- distance & pace ----
        val distanceM: Double,
        /** Averaged over moving time only — the honest "how fast were we". */
        val averageMovingSpeedKmh: Double,
        /** Averaged over the whole journey, breaks included. */
        val overallSpeedKmh: Double,
        val topSpeedKmh: Double?,

        // ---- breaks & wellbeing ----
        val stops: Int,
        val breakCount: Int,
        val averageGapBetweenBreaksSeconds: Long?,
        val meals: Map<Nourishment, Int>,
        val waterCount: Int,
        val toiletCount: Int,
        val restCount: Int,
        val fuelStops: Int,

        // ---- money ----
        val totalCost: Double,
        val costLines: List<CostLine>,
        val costPerMetre: Double?,
        val costPerHour: Double?,
        val fuelCost: Double,
        val litres: Double,
        val kwh: Double,

        // ---- shape ----
        val legs: List<LegReport>,

        /**
         * Short factual sentences derived from the numbers above. Never a
         * judgement about the traveller, always something the data says.
         */
        val insights: List<String>
    ) {
        val hasCosts: Boolean get() = totalCost > 0.0
    }

    data class LegReport(
        val index: Int,
        val mode: String,
        val fromName: String,
        val toName: String,
        val seconds: Long?
    )

    fun analyse(i: Inputs): JourneyReport {
        val sorted = i.events.sortedBy { it.eventTimeMs }
        val totalSeconds = ((i.endedAtMs - i.startedAtMs) / 1000).coerceAtLeast(0)

        // ---- stops, breaks and the time they consumed ----
        var stops = 0
        var stoppedSeconds = 0L
        var longestBreak = 0L
        var longestLeg = 0L
        var legStart = i.startedAtMs
        val breakTimes = ArrayList<Long>()

        val meals = HashMap<Nourishment, Int>()
        var water = 0
        var toilet = 0
        var rest = 0
        var fuelStops = 0
        var breakCheckpoints = 0

        for (e in sorted) {
            when (e.type) {
                EventTypes.STOP_STARTED -> {
                    stops++
                    val leg = e.eventTimeMs - legStart
                    if (leg > longestLeg) longestLeg = leg
                }
                EventTypes.STOP_ENDED -> {
                    val durS = (e.payload["durationSeconds"] as? Number)?.toLong() ?: 0L
                    stoppedSeconds += durS
                    if (durS > longestBreak) longestBreak = durS
                    legStart = e.eventTimeMs
                }
                EventTypes.BREAK_CHECKPOINT -> {
                    breakCheckpoints++
                    breakTimes.add(e.eventTimeMs)
                }
                EventTypes.FOOD_REPORTED -> {
                    val kind = Nourishment.fromKey(e.payload["meal"] as? String) ?: Nourishment.SNACK
                    meals[kind] = (meals[kind] ?: 0) + 1
                }
                EventTypes.SNACK_REPORTED ->
                    meals[Nourishment.SNACK] = (meals[Nourishment.SNACK] ?: 0) + 1
                EventTypes.TEA_COFFEE_REPORTED ->
                    meals[Nourishment.TEA_COFFEE] = (meals[Nourishment.TEA_COFFEE] ?: 0) + 1
                EventTypes.WATER_REPORTED -> water++
                EventTypes.TOILET_REPORTED -> toilet++
                EventTypes.REST_REPORTED -> rest++
                EventTypes.FUEL_STOP, EventTypes.CHARGE_STOP -> fuelStops++
            }
        }
        val lastLeg = i.endedAtMs - legStart
        if (lastLeg > longestLeg) longestLeg = lastLeg

        val movingSeconds = (totalSeconds - stoppedSeconds).coerceAtLeast(0)
        val movingShare = if (totalSeconds > 0) movingSeconds.toDouble() / totalSeconds else 0.0

        // How regularly breaks came. Measured between consecutive breaks, so a
        // journey with one break has no cadence to report rather than a wrong one.
        val averageGap = if (breakTimes.size >= 2) {
            val gaps = breakTimes.zipWithNext { a, b -> (b - a) / 1000 }
            gaps.sum() / gaps.size
        } else null

        // ---- pace ----
        val km = i.distanceCoveredM / 1000.0
        val movingHours = movingSeconds / 3600.0
        val totalHours = totalSeconds / 3600.0
        val averageMoving = if (movingHours > 0) km / movingHours else 0.0
        val overall = if (totalHours > 0) km / totalHours else 0.0

        // ---- money ----
        val totalCost = i.expenses.sumOf { it.amount }
        val byType = i.expenses.groupBy { it.type }
        val costLines = byType
            .map { (type, list) ->
                val amount = list.sumOf { it.amount }
                CostLine(
                    type = type,
                    label = costLabel(type),
                    amount = amount,
                    share = if (totalCost > 0) amount / totalCost else 0.0
                )
            }
            .sortedByDescending { it.amount }

        val fuelCost = byType["FUEL"]?.sumOf { it.amount } ?: 0.0
        val litres = i.expenses.filter { it.type == "FUEL" && it.unit == "L" }.sumOf { it.quantity ?: 0.0 }
        val kwh = i.expenses.filter { it.type == "FUEL" && it.unit == "kWh" }.sumOf { it.quantity ?: 0.0 }

        val legReports = i.legs.map { leg ->
            LegReport(
                index = leg.index, mode = leg.mode,
                fromName = leg.fromName, toName = leg.toName,
                seconds = if (leg.startedAtMs != null && leg.completedAtMs != null)
                    ((leg.completedAtMs - leg.startedAtMs) / 1000).coerceAtLeast(0) else null
            )
        }

        val report = JourneyReport(
            totalSeconds = totalSeconds,
            movingSeconds = movingSeconds,
            stoppedSeconds = stoppedSeconds,
            movingShare = movingShare,
            longestLegSeconds = (longestLeg / 1000).coerceAtLeast(0),
            longestBreakSeconds = longestBreak,
            days = distinctDays(sorted, i.startedAtMs, i.endedAtMs, i.zone),
            distanceM = i.distanceCoveredM,
            averageMovingSpeedKmh = averageMoving,
            overallSpeedKmh = overall,
            topSpeedKmh = i.topSpeedKmh,
            stops = stops,
            breakCount = breakCheckpoints,
            averageGapBetweenBreaksSeconds = averageGap,
            meals = meals,
            waterCount = water,
            toiletCount = toilet,
            restCount = rest,
            fuelStops = fuelStops,
            totalCost = totalCost,
            costLines = costLines,
            costPerMetre = if (i.distanceCoveredM > 0 && totalCost > 0) totalCost / i.distanceCoveredM else null,
            costPerHour = if (totalHours > 0 && totalCost > 0) totalCost / totalHours else null,
            fuelCost = fuelCost,
            litres = litres,
            kwh = kwh,
            legs = legReports,
            insights = emptyList()
        )
        return report.copy(insights = insightsFor(report, i))
    }

    /**
     * The sentences that turn numbers into meaning.
     *
     * Rules for anything added here: it must be derivable from the data, it
     * must be factual, and it must be worth reading. No praise, no scolding —
     * a journey record, not a report card.
     */
    private fun insightsFor(r: JourneyReport, i: Inputs): List<String> = buildList {
        if (r.totalSeconds > 0 && r.stoppedSeconds > 0) {
            val pct = (r.movingShare * 100).roundToLong()
            add("$pct% of the journey was spent moving; the rest was stops.")
        }
        r.averageGapBetweenBreaksSeconds?.let { gap ->
            if (gap > 0) add("Breaks came about every ${humanDuration(gap)}.")
        }
        if (r.breakCount == 0 && r.movingSeconds > 4 * 3600 &&
            TransportCatalog.isPrivate(i.transportMode)
        ) {
            add("No breaks were logged on a drive of over ${humanDuration(r.movingSeconds)}.")
        }
        if (r.averageMovingSpeedKmh > 0 && r.overallSpeedKmh > 0) {
            val diff = r.averageMovingSpeedKmh - r.overallSpeedKmh
            if (diff > 8) {
                add(
                    "Stops cost about ${diff.roundToLong()} km/h off the door-to-door average."
                )
            }
        }
        if (r.hasCosts) {
            r.costLines.firstOrNull()?.let { biggest ->
                if (biggest.share >= 0.4) {
                    add("${biggest.label} was ${(biggest.share * 100).roundToLong()}% of what the journey cost.")
                }
            }
        }
        if (r.days > 1) add("The journey ran across ${r.days} calendar days.")
        val mealCount = r.meals.filterKeys { it.isMainMeal }.values.sum()
        if (mealCount > 0 && r.totalSeconds > 6 * 3600) {
            add("$mealCount meal${if (mealCount == 1) "" else "s"} logged over ${humanDuration(r.totalSeconds)}.")
        }
        if (r.legs.size > 1) {
            add("${r.legs.size} stages, in ${r.legs.map { it.mode }.distinct().size} mode(s) of transport.")
        }
    }

    private fun costLabel(type: String): String = when (type) {
        "FUEL" -> "Fuel"
        "TICKET" -> "Tickets"
        "FOOD" -> "Food"
        "STAY" -> "Accommodation"
        else -> "Other"
    }

    private fun humanDuration(seconds: Long): String = when {
        seconds < 3600 -> "${seconds / 60} min"
        seconds % 3600 == 0L -> "${seconds / 3600}h"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    private fun distinctDays(
        events: List<TripEvent>, startedAtMs: Long, endedAtMs: Long, zone: ZoneId
    ): Int {
        val days = HashSet<String>()
        fun add(ms: Long) {
            days.add(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString())
        }
        add(startedAtMs); add(endedAtMs)
        events.forEach { add(it.eventTimeMs) }
        return days.size.coerceAtLeast(1)
    }
}
