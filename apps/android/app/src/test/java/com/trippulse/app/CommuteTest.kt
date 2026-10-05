package com.trippulse.app

import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.ModeSense
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TripConfig
import com.trippulse.app.domain.TripEvent
import com.trippulse.app.domain.forMode
import com.trippulse.app.domain.report.JourneyStory
import com.trippulse.app.domain.report.PlaceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Office to home by cab, on foot, by metro and on foot again: no stage planned ahead. */
class CommuteTest {

    private val t0 = 1_790_946_000_000L
    private fun min(m: Int) = t0 + m * 60_000L

    // ---- walking is a mode, with walking thresholds ----

    @Test fun walking_is_in_the_catalog_and_moves_at_walking_pace() {
        val walk = TransportCatalog.profile("WALK")
        assertEquals("WALK", walk.key)
        assertTrue(TransportCatalog.ALL.contains(walk))
        assertTrue(!walk.isPrivateVehicle && !walk.isRoadMode)
        val cfg = TripConfig()
        assertEquals(2.5, cfg.forMode("WALK").restartSpeedKmh, 0.0)
        assertEquals(cfg.restartSpeedKmh, cfg.forMode("CAB").restartSpeedKmh, 0.0)
        // Every quick change of the commute needs no details at all.
        TransportCatalog.COMMUTE.forEach {
            assertTrue(it.key, com.trippulse.app.domain.TravelDetails.isComplete(it.key, emptyMap()))
        }
    }

    // ---- the phone notices the change ----

    private fun track(fromMin: Int, toMin: Int, kmh: Double, stepS: Int = 20): List<ModeSense.Fix> {
        val out = ArrayList<ModeSense.Fix>()
        var t = min(fromMin); var lat = 17.40
        val dLatPerS = kmh / 3.6 / 111_320.0
        while (t <= min(toMin)) { out += ModeSense.Fix(t, lat, 78.50, kmh / 3.6); t += stepS * 1000L; lat += dLatPerS * stepS }
        return out
    }

    @Test fun out_of_the_cab_and_walking_is_noticed() {
        assertEquals(ModeSense.Hint.ON_FOOT, ModeSense.hint("CAB", track(0, 5, 4.8), min(5)))
    }

    @Test fun a_cab_on_the_move_is_left_alone() {
        assertNull(ModeSense.hint("CAB", track(0, 5, 32.0), min(5)))
    }

    @Test fun a_cab_crawling_in_traffic_with_bursts_is_not_taken_for_walking() {
        val crawl = track(0, 2, 4.0) + track(2, 3, 22.0).map { it.copy(tMs = it.tMs + 1) } + track(3, 5, 4.0).map { it.copy(tMs = it.tMs + 2) }
        assertNull(ModeSense.hint("CAB", crawl.sortedBy { it.tMs }, min(5)))
    }

    @Test fun on_something_fast_while_walking_is_noticed() {
        assertEquals(ModeSense.Hint.ON_SOMETHING_FASTER, ModeSense.hint("WALK", track(0, 4, 34.0), min(4)))
        assertNull(ModeSense.hint("WALK", track(0, 5, 5.0), min(5)))
    }

    @Test fun your_own_car_is_parked_not_left_and_too_little_record_says_nothing() {
        assertNull(ModeSense.hint("CAR", track(0, 5, 4.8), min(5)))
        assertNull(ModeSense.hint("CAB", track(0, 1, 4.8), min(1)))
    }

    // ---- the story tells it in stages ----

    private var n = 0
    private fun leg(at: Long, mode: String) =
        TripEvent("e${n++}", "T", EventTypes.LEG_STARTED, at, 17.40, 78.50, null, EventSource.DRIVER_MANUAL, mapOf("mode" to mode))

    @Test fun a_commute_is_told_in_stages_and_each_stretch_by_how_it_was_made() {
        val events = listOf(
            TripEvent("s", "T", EventTypes.TRIP_STARTED, min(0), 17.44, 78.38, null, EventSource.DRIVER_MANUAL, emptyMap()),
            leg(min(0), "CAB"), leg(min(14), "WALK"), leg(min(22), "METRO"), leg(min(48), "WALK")
        )
        val samples = ArrayList<JourneyStory.Sample>()
        var lat = 17.44
        for (m in 0..60) { samples += JourneyStory.Sample(min(m), lat, 78.38 + m * 0.002); lat += 0.0005 }
        val input = JourneyStory.Input(
            who = "Prashobh Paul", origin = "Qualizeal Office", destination = "Raheja Vistas, Nacharam",
            originLat = 17.44, originLng = 78.38, destLat = 17.47, destLng = 78.50,
            mode = "WALK", startedAtMs = min(0), endedAtMs = min(60), nowMs = min(61),
            events = events, samples = samples, distanceM = 14_000.0, zone = ZoneId.of("Asia/Kolkata")
        )
        val book = PlaceBook(); JourneyStory.seed(input, book)
        val s = JourneyStory.build(input, book)
        assertEquals(listOf("CAB", "WALK", "METRO", "WALK"), s.stages.map { it.mode })
        // The journey is not "a walk" because it ended on foot: it is the metro it spent longest on.
        assertEquals("METRO", JourneyStory.primaryMode(s, input.mode))
        assertTrue(s.paragraphs.joinToString(" ").let { it.contains("by cab") && it.contains("by metro") && it.contains("on foot") })
        assertEquals("Walked 600 m", JourneyStory.stretch("WALK", 600.0))
        assertEquals("By metro 9.4 km", JourneyStory.stretch("METRO", 9_400.0))
        assertEquals("Drove 12 km", JourneyStory.stretch("CAR", 12_000.0))
    }
}
