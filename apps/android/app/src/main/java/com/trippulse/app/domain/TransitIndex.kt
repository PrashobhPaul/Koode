package com.trippulse.app.domain

import org.json.JSONObject
import kotlin.math.floor

/**
 * Which countries' transit files cover a place, from the index published
 * with them (web/data/transit/index.json, see tools/transit/collect.py): for
 * each country, the 1-degree squares its stations fall in.
 *
 * The country the phone's network reports is not enough on its own. Near a
 * border, on a ferry to the next country, on a train that crosses one (Paris
 * to Brussels, Geneva's French suburbs, Detroit to Windsor) the stations
 * that matter are in the country next door. So the files loaded are those
 * of every country with stations in the square the phone is in or the eight
 * around it, about 100 km each way.
 */
class TransitIndex(private val cells: Map<String, Set<String>>) {

    val countries: Set<String> get() = cells.keys

    /** The countries with stations within a square of [lat], [lng], nearest square first. */
    fun near(lat: Double, lng: Double): List<String> {
        val here = cellOf(lat, lng)
        val (y, x) = here
        val ring = buildList {
            add(here)
            for (dy in -1..1) for (dx in -1..1) if (dy != 0 || dx != 0) add(y + dy to wrap(x + dx))
        }.map { "${it.first},${it.second}" }
        return ring.flatMap { key -> cells.filterValues { key in it }.keys.sorted() }.distinct()
    }

    companion object {
        val EMPTY = TransitIndex(emptyMap())

        /** The square a place is in: its latitude and longitude rounded down to whole degrees. */
        fun cellOf(lat: Double, lng: Double): Pair<Int, Int> = floor(lat).toInt() to wrap(floor(lng).toInt())

        private fun wrap(x: Int): Int = ((x + 180) % 360 + 360) % 360 - 180

        /** Reads index.json; an unreadable one is an empty index, never an error. */
        fun parse(text: String?): TransitIndex = runCatching {
            val countries = JSONObject(text ?: return EMPTY).getJSONObject("countries")
            val out = HashMap<String, Set<String>>()
            for (cc in countries.keys()) {
                val list = countries.getJSONObject(cc).optJSONArray("cells") ?: continue
                out[cc.uppercase()] = (0 until list.length()).map { list.getString(it) }.toSet()
            }
            TransitIndex(out)
        }.getOrDefault(EMPTY)
    }
}
