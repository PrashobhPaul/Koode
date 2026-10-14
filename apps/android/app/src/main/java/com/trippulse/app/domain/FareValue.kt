package com.trippulse.app.domain

import kotlin.math.roundToLong

/**
 * What each way of travelling gave for what it cost. Spend by mode is a
 * record; value is the comparison: the bike taxi carried you 15% of the
 * way for 54% of the fare, at seven times the metro's price per kilometre,
 * and all the way by bike taxi would have cost three times as much.
 *
 * Pure: the screens pass their own formatters for money and distance.
 */
object FareValue {

    /** Below these, a stage is too short to price per kilometre or per minute. */
    const val MIN_METRES = 300.0
    const val MIN_RIDE_MS = 60_000L

    data class ModeValue(
        val mode: String,
        /** The expense type its fares are recorded under (Expenses.Category name). */
        val type: String,
        val metres: Double?,
        /** Time actually riding: the changes either side are not the ride's. */
        val rideMs: Long?,
        val cost: Double,
        val distanceShare: Double?,
        val costShare: Double
    ) {
        val perMetre: Double? get() = metres?.takeIf { it >= MIN_METRES && cost > 0 }?.let { cost / it }
        val perMinute: Double? get() = rideMs?.takeIf { it >= MIN_RIDE_MS && cost > 0 }?.let { cost / (it / 60_000.0) }
        /** The pace it kept, when there is enough of it to trust. */
        val kmh: Double? get() {
            val m = metres ?: return null
            val ms = rideMs?.takeIf { it >= 2 * MIN_RIDE_MS } ?: return null
            return (m / ms * 3_600.0).takeIf { it in 3.0..90.0 }
        }
    }

    /**
     * One [ModeValue] per way of travelling that was paid for: its stages'
     * distance and riding time, and the fares recorded under its category.
     * [rides] is (mode, metres or null, riding ms); [fares] is spend by
     * expense type.
     */
    fun values(rides: List<Triple<String, Double?, Long>>, fares: Map<String, Double>): List<ModeValue> {
        val knownM = rides.sumOf { it.second ?: 0.0 }
        val fareTotal = fares.values.sum()
        return rides.groupBy { TransportCatalog.profile(it.first).key }.mapNotNull { (key, list) ->
            val type = Expenses.Category.fareFor(key)?.name ?: return@mapNotNull null
            val cost = fares[type] ?: return@mapNotNull null
            if (cost <= 0) return@mapNotNull null
            val metres = list.mapNotNull { it.second }.takeIf { it.size == list.size }?.sum()
            ModeValue(
                mode = key, type = type, metres = metres, rideMs = list.sumOf { it.third },
                cost = cost,
                distanceShare = metres?.takeIf { knownM > 0 }?.let { it / knownM },
                costShare = if (fareTotal > 0) cost / fareTotal else 0.0
            )
        }.sortedByDescending { it.cost }
    }

    private fun the(mode: String) = "the ${TransportCatalog.label(mode).lowercase()}"
    private fun by(mode: String) = "by ${TransportCatalog.label(mode).lowercase()}"
    private fun pct(x: Double) = (x * 100).roundToLong()

    /** The dearest and the cheapest per kilometre, when two paid ways can be compared. */
    fun extremes(values: List<ModeValue>): Pair<ModeValue, ModeValue>? {
        val priced = values.filter { it.perMetre != null && it.distanceShare != null }
        if (priced.size < 2) return null
        val dear = priced.maxBy { it.perMetre!! }
        val cheap = priced.minBy { it.perMetre!! }
        return (dear to cheap).takeIf { dear !== cheap }
    }

    /** One sentence, no units, for the insights and the report: "The bike taxi was 54% of the fare for 15% of the distance." */
    fun insight(values: List<ModeValue>): String? {
        val (dear, _) = extremes(values) ?: return null
        val d = dear.distanceShare ?: return null
        if (dear.costShare - d < 0.1) return null
        return "${the(dear.mode).replaceFirstChar { it.uppercase() }} was ${pct(dear.costShare)}% of the fare for ${pct(d)}% of the distance."
    }

    /**
     * The value in full, for the money card: the comparison, what the
     * dearest way would have cost all the way (and taken, at its pace),
     * and the price of a minute on each.
     */
    fun lines(
        values: List<ModeValue>,
        journeyMetres: Double,
        journeyMs: Long,
        journeyCost: Double,
        money: (Double) -> String,
        perDistance: (cost: Double, metres: Double) -> String?
    ): List<String> = buildList {
        val pair = extremes(values)
        if (pair != null) {
            val (dear, cheap) = pair
            val dearRate = perDistance(dear.cost, dear.metres!!)
            val cheapRate = perDistance(cheap.cost, cheap.metres!!)
            add(
                "${the(dear.mode).replaceFirstChar { it.uppercase() }} took ${pct(dear.costShare)}% of the fare for ${pct(dear.distanceShare!!)}% of the distance" +
                    (if (dearRate != null && cheapRate != null) ": $dearRate, against $cheapRate ${by(cheap.mode)}." else ".")
            )
            val allTheWay = dear.perMetre!! * journeyMetres
            if (journeyMetres > 0 && allTheWay > journeyCost * 1.1) {
                val time = dear.kmh?.let { kmh ->
                    val min = (journeyMetres / 1000.0 / kmh * 60).roundToLong()
                    val actual = journeyMs / 60_000
                    if (actual > 0) " and about $min min at its pace, against $actual min door to door" else null
                } ?: ""
                add("All the way ${by(dear.mode)} at that rate: about ${money(allTheWay)}$time. This journey cost ${money(journeyCost)}, ${money(allTheWay - journeyCost)} less.")
            }
        } else values.singleOrNull()?.let { v ->
            val rate = v.metres?.takeIf { v.perMetre != null }?.let { perDistance(v.cost, it) }
            if (rate != null) add("${by(v.mode).replaceFirstChar { it.uppercase() }}: $rate.")
        }
        val perMinute = values.filter { it.perMinute != null }
        if (perMinute.size >= 2) {
            add("A minute riding cost " + perMinute.sortedByDescending { it.perMinute }.joinToString(", ") { "${money(it.perMinute!!)} ${by(it.mode)}" } + ".")
        } else perMinute.singleOrNull()?.let { add("A minute riding cost ${money(it.perMinute!!)} ${by(it.mode)}.") }
    }
}
