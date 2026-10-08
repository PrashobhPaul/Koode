package com.trippulse.app

import com.trippulse.app.domain.TransitIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which countries' stations a place needs: its own, and the next one's near a border. */
class TransitIndexTest {

    private val index = TransitIndex.parse(
        """{"countries": {
            "FR": {"cells": ["48,2", "45,4", "46,6"]},
            "CH": {"cells": ["46,6", "47,8"]},
            "DE": {"cells": ["47,7", "52,13"]},
            "GB": {"cells": ["51,0", "51,-1"]},
            "US": {"cells": ["40,-74"]}
        }, "source": "x"}"""
    )

    @Test fun geneva_has_the_french_and_swiss_stations() {
        val near = index.near(46.20, 6.14)
        assertEquals(listOf("CH", "FR"), near.take(2))
    }

    @Test fun basel_reaches_three_countries() {
        assertEquals(setOf("CH", "DE", "FR"), index.near(47.56, 7.59).toSet())
    }

    @Test fun the_channel_crossing_has_both_sides() {
        // Calais (50.9, 1.8) is one square from Dover's.
        assertTrue("GB" in index.near(50.95, 1.85))
    }

    @Test fun far_from_everything_is_nothing_and_a_bad_index_is_empty() {
        assertTrue(index.near(-33.9, 151.2).isEmpty())
        assertTrue(TransitIndex.parse("not json").countries.isEmpty())
        assertTrue(TransitIndex.parse(null).near(46.2, 6.1).isEmpty())
        assertEquals(40 to -74, TransitIndex.cellOf(40.71, -73.99))
        assertEquals(-34 to 151, TransitIndex.cellOf(-33.9, 151.2))
    }
}
