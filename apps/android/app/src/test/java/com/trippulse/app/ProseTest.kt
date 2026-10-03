package com.trippulse.app

import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.TripEvent
import com.trippulse.app.domain.report.JourneyStory
import com.trippulse.app.domain.report.PlaceBook
import com.trippulse.app.domain.report.Prose
import com.trippulse.app.domain.report.StoryCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ProseTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val noon = 1_790_921_000_000L - (1_790_921_000_000L % 3_600_000L) + 30 * 60_000L
    private fun t(h: Int, m: Int = 0) = noon + ((h - 12) * 60 + m) * 60_000L
    private var n = 0
    private fun ev(type: String, at: Long, lat: Double, lng: Double, p: Map<String, Any?> = emptyMap()) =
        TripEvent("e${n++}", "T", type, at, lat, lng, null, EventSource.SYSTEM_INFERRED, p)

    private fun input(mode: String = "CAR", seed: String = "A", start: Long = t(12), fuel: String? = null) = JourneyStory.Input(
        who = "Asha Menon", origin = "Home", destination = "Kochi", originLat = 10.0, originLng = 76.3, destLat = 9.97, destLng = 76.28,
        mode = mode, startedAtMs = start, endedAtMs = start + 5 * 3_600_000L, nowMs = start + 5 * 3_600_000L,
        events = listOf(
            ev(EventTypes.STOP_STARTED, start + 2 * 3_600_000L, 10.3, 76.5),
            ev(EventTypes.BREAK_CHECKPOINT, start + 2 * 3_600_000L + 60_000, 10.3, 76.5, mapOf("breakId" to "b", "food" to true, "meal" to "LUNCH", "fuel" to true, "place" to "Thrissur", "startMs" to start + 2 * 3_600_000L, "durationS" to 1800L)),
            ev(EventTypes.FOOD_REPORTED, start + 2 * 3_600_000L + 60_000, 10.3, 76.5, mapOf("breakId" to "b", "meal" to "LUNCH")),
            ev(EventTypes.STOP_ENDED, start + 2 * 3_600_000L + 1_800_000, 10.3, 76.5),
            ev(EventTypes.TOLL_CROSSED, start + 3 * 3_600_000L, 10.5, 76.6, mapOf("plaza" to "Paliyekkara Toll Plaza"))
        ),
        samples = (0..300).map { m -> JourneyStory.Sample(start + m * 60_000L, 10.0 + m * 0.003, 76.3 + m * 0.002) },
        distanceM = 150_000.0, zone = zone, fuelType = fuel, seedKey = seed
    )

    private fun story(i: JourneyStory.Input) = PlaceBook().let { b -> JourneyStory.seed(i, b); JourneyStory.build(i, b) }

    @Test fun the_same_journey_always_reads_the_same_and_two_journeys_differ() {
        val a1 = story(input(seed = "A")).paragraphs
        val a2 = story(input(seed = "A")).paragraphs
        val b = story(input(seed = "B")).paragraphs
        val c = story(input(seed = "C")).paragraphs
        assertEquals(a1, a2)
        assertTrue("different seeds should not all read alike", a1 != b || a1 != c || b != c)
    }

    @Test fun the_words_follow_the_way_of_travelling() {
        val car = story(input(mode = "CAR")).paragraphs.joinToString(" ")
        val train = story(input(mode = "TRAIN")).paragraphs.joinToString(" ")
        assertTrue(car.contains("car") && car.contains("refuelled") && car.contains("toll"))
        assertFalse(train.contains("refuelled") || train.contains("toll plaza"))
        assertTrue(train.contains("train"))
        val ev = story(input(mode = "CAR", fuel = "ELECTRIC")).paragraphs.joinToString(" ")
        assertTrue(ev.contains("charged up"))
    }

    @Test fun the_time_of_day_is_named() {
        val dawn = story(input(start = t(5, 30))).paragraphs.first()
        val night = story(input(start = t(22, 15))).paragraphs.first()
        assertTrue(dawn, dawn.contains("dawn") || dawn.contains("first light"))
        assertTrue(night, night.contains("night") || night.contains("after dark"))
    }

    @Test fun nothing_leaks_from_the_machine() {
        val all = story(input()).paragraphs.joinToString(" ")
        for (bad in listOf("null", "NaN", "event", "sample", "detected", "BREAK_CHECKPOINT", "  ", " .", ",.")) {
            assertFalse("'$bad' in: $all", all.contains(bad))
        }
        assertFalse(Regex("""\d+\.\d{3,}""").containsMatchIn(all))
    }

    @Test fun the_toll_note_only_when_the_phone_was_out_of_contact() {
        val quiet = story(input())
        assertFalse(quiet.tollsMayBeMissing)
        val i = input()
        val gap = i.copy(events = i.events + ev(EventTypes.DEVICE_BACK_ONLINE, i.startedAtMs + 4 * 3_600_000L, 10.8, 76.8, mapOf("gapMs" to 40 * 60_000L)))
        val s = story(gap)
        assertTrue(s.tollsMayBeMissing)
        assertTrue(s.paragraphs.any { it.contains("40 min") })
    }

    @Test fun the_story_survives_the_trip_to_a_follower() {
        val i = input()
        val s = story(i)
        val live = StoryCodec.decode(StoryCodec.encode(s, i, 1L))!!
        assertEquals(s.headline, live.headline)
        assertEquals(s.paragraphs, live.paragraphs)
        assertEquals(s.segments.map { Triple(it.fromMs, it.toMs, it.phase) }, live.segments.map { Triple(it.fromMs, it.toMs, it.phase) })
        assertEquals(s.highlights.map { it.title }, live.highlights.map { it.title })
        assertEquals(null, StoryCodec.decode(null))
        assertEquals(null, StoryCodec.decode(mapOf("v" to 1)))
    }

    @Test fun segments_cover_the_whole_span_in_order_without_overlap() {
        val s = story(input())
        val segs = s.segments
        assertTrue(segs.isNotEmpty())
        for (k in 1 until segs.size) assertEquals(segs[k - 1].toMs, segs[k].fromMs)
        assertTrue(segs.any { it.phase == JourneyStory.Phase.STOPPED })
        assertNotEquals(0, s.kmByHour.size)
    }
}
