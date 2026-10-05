package com.trippulse.app

import com.trippulse.app.domain.MapStages
import com.trippulse.app.domain.Pictures
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TripConfig
import com.trippulse.app.domain.forMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStagesTest {

    // Cab from the office, a walk to the station, the metro home.
    private val stages = MapStages.of(listOf(1_000L to "CAB", 5_000L to "WALK", 8_000L to "METRO", null to "CAB"))

    @Test fun stages_are_the_legs_that_started() {
        assertEquals(listOf("CAB", "WALK", "METRO"), stages.map { it.mode })
    }

    @Test fun the_mode_at_a_moment_is_the_stage_begun_by_then() {
        assertEquals("CAB", MapStages.modeAt(stages, 500L, "CAR")) // the record began a moment before the leg
        assertEquals("CAB", MapStages.modeAt(stages, 4_999L, "CAR"))
        assertEquals("WALK", MapStages.modeAt(stages, 5_000L, "CAR"))
        assertEquals("METRO", MapStages.modeAt(stages, 99_000L, "CAR"))
        assertEquals("CAR", MapStages.modeAt(emptyList(), 99_000L, "CAR"))
    }

    @Test fun the_trail_splits_where_the_mode_changed_and_stays_joined() {
        val times = listOf(1_000L, 2_000L, 4_000L, 5_500L, 6_000L, 8_500L, 9_000L)
        val runs = MapStages.runs(times, stages, "CAB")
        assertEquals(listOf("CAB", "WALK", "METRO"), runs.map { it.mode })
        assertEquals(MapStages.Run(0, 3, "CAB"), runs[0])
        assertEquals(MapStages.Run(3, 5, "WALK"), runs[1])
        assertEquals(MapStages.Run(5, 6, "METRO"), runs[2])
        assertTrue(MapStages.onFoot(runs[1].mode))
        assertFalse(MapStages.onFoot(runs[2].mode))
    }

    @Test fun one_stage_or_no_times_is_one_line() {
        assertEquals(listOf(MapStages.Run(0, 2, "CAR")), MapStages.runs(listOf(1L, 2L, 3L), emptyList(), "CAR"))
        assertEquals(listOf(MapStages.Run(0, 3, "CAB")), MapStages.runs(emptyList(), stages, "CAB", size = 4))
        assertTrue(MapStages.runs(listOf(1L), stages, "CAB").isEmpty())
    }

    @Test fun points_past_the_record_belong_to_the_latest_stage() {
        val runs = MapStages.runs(listOf(6_000L, 7_000L, Long.MAX_VALUE), stages, "CAB")
        // The walk runs up to the live point, where the metro takes over.
        assertEquals(listOf(MapStages.Run(0, 2, "WALK")), runs)
        assertEquals("METRO", MapStages.modeAt(stages, Long.MAX_VALUE, "CAB"))
    }

    @Test fun a_cycle_is_its_own_mode_with_its_own_picture_and_pace() {
        val cycle = TransportCatalog.profile("CYCLE")
        assertEquals("CYCLE", cycle.key)
        assertFalse(cycle.asksAboutFuel)
        assertEquals("cycle", Pictures.mode("CYCLE"))
        assertTrue(TransportCatalog.COMMUTE.any { it.key == "CYCLE" })
        assertEquals(5.0, TripConfig().forMode("CYCLE").restartSpeedKmh, 0.0)
    }
}
