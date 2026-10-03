package com.trippulse.app

import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Nourishment
import com.trippulse.app.domain.TripEvent
import com.trippulse.app.domain.report.JourneyStory
import com.trippulse.app.domain.report.JourneyStory.Entry
import com.trippulse.app.domain.report.JourneyStory.Item
import com.trippulse.app.domain.report.PlaceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class JourneyStoryTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    /** 12:00 PM IST, Friday 2 October 2026. */
    private val noon = 1_790_921_000_000L - (1_790_921_000_000L % 3_600_000L) + 30 * 60_000L
    private fun t(h: Int, m: Int = 0) = noon + ((h - 12) * 60 + m) * 60_000L
    private var n = 0
    private fun ev(type: String, at: Long, lat: Double?, lng: Double?, p: Map<String, Any?> = emptyMap()) =
        TripEvent("e${n++}", "T", type, at, lat, lng, null, EventSource.SYSTEM_INFERRED, p)

    // A road heading north-east: start, a fuel+restroom stop, a lunch stop, then a silence.
    private val start = 10.56 to 76.16
    private val pump = 11.82 to 78.06
    private val lunch = 11.20 to 77.41

    private fun samples(): List<JourneyStory.Sample> {
        val out = ArrayList<JourneyStory.Sample>()
        fun leg(from: Pair<Double, Double>, to: Pair<Double, Double>, a: Long, b: Long) {
            val steps = ((b - a) / 60_000L).toInt().coerceAtLeast(1)
            for (i in 0..steps) {
                val f = i.toDouble() / steps
                out += JourneyStory.Sample(a + (b - a) * i / steps, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f)
            }
        }
        leg(start, lunch, t(12), t(14))
        leg(lunch, lunch, t(14), t(15))
        leg(lunch, pump, t(15), t(17))
        leg(pump, pump, t(17), t(17, 20))
        return out
    }

    private fun events() = listOf(
        ev(EventTypes.TRIP_STARTED, t(12), start.first, start.second),
        // Lunch: logged two minutes before stop detection caught up.
        ev(EventTypes.BREAK_CHECKPOINT, t(13, 58), lunch.first, lunch.second, mapOf("breakId" to "L", "food" to true, "meal" to "LUNCH", "place" to "Tirupur", "open" to true)),
        ev(EventTypes.FOOD_REPORTED, t(13, 58), lunch.first, lunch.second, mapOf("breakId" to "L", "meal" to "LUNCH")),
        ev(EventTypes.STOP_STARTED, t(14), lunch.first, lunch.second),
        ev(EventTypes.STOP_ENDED, t(15), lunch.first, lunch.second),
        ev(EventTypes.BREAK_CHECKPOINT, t(15), lunch.first, lunch.second, mapOf("breakId" to "L", "food" to true, "meal" to "LUNCH", "place" to "Tirupur", "durationS" to 3600L)),
        // Toll on the way.
        ev(EventTypes.TOLL_CROSSED, t(16), 11.5, 77.9, mapOf("plaza" to "Vaikundam Toll Gate", "passCovered" to true)),
        // One stop, three entries: fuel, then the restroom, then water as they left.
        ev(EventTypes.STOP_STARTED, t(17), pump.first, pump.second),
        ev(EventTypes.BREAK_CHECKPOINT, t(17, 2), pump.first, pump.second, mapOf("breakId" to "F", "fuel" to true, "place" to "Pallapatti", "startMs" to t(17))),
        ev(EventTypes.BREAK_CHECKPOINT, t(17, 3), pump.first, pump.second, mapOf("breakId" to "R", "toilet" to true, "place" to "Pallapatti", "startMs" to t(17), "open" to true)),
        ev(EventTypes.STOP_ENDED, t(17, 20), pump.first, pump.second),
        ev(EventTypes.BREAK_CHECKPOINT, t(17, 20), pump.first, pump.second, mapOf("breakId" to "R", "toilet" to true, "place" to "Pallapatti", "startMs" to t(17), "durationS" to 1200L)),
        ev(EventTypes.BREAK_CHECKPOINT, t(17, 21), 11.836, 78.068, mapOf("breakId" to "W", "water" to true, "place" to "Darapuram", "startMs" to t(17), "durationS" to 1200L)),
        // Silence 17:25 → 23:30, then dinner at 21:30 logged at 23:35, and a room.
        ev(EventTypes.DEVICE_BACK_ONLINE, t(23, 30), 13.95, 77.68, mapOf("gapMs" to (6 * 60 + 5) * 60_000L)),
        ev(EventTypes.BREAK_CHECKPOINT, t(23, 35), 13.95, 77.68, mapOf("breakId" to "D", "food" to true, "meal" to "DINNER", "place" to "Palasamudram", "startMs" to t(21, 30), "durationS" to 2700L)),
        ev(EventTypes.FOOD_REPORTED, t(21, 30), 13.95, 77.68, mapOf("breakId" to "D", "meal" to "DINNER")),
        ev(EventTypes.HALT_CONFIRMED, t(23, 40), 13.95, 77.68, mapOf("haltType" to "ROOM", "overnight" to true, "place" to "Palasamudram"))
    )

    private fun input(evs: List<TripEvent> = events(), ended: Long? = null) = JourneyStory.Input(
        who = "Prashobh Paul", origin = "Home", destination = "Raheja Vistas, Nacharam",
        originLat = start.first, originLng = start.second, destLat = 17.43, destLng = 78.56,
        mode = "CAR", startedAtMs = t(12), endedAtMs = ended, nowMs = t(23, 50),
        events = evs, samples = samples() + JourneyStory.Sample(t(23, 30), 13.95, 77.68),
        distanceM = 300_000.0, routeDistanceM = 990_000.0, zone = zone
    )

    private fun story(i: JourneyStory.Input = input()): JourneyStory.Story {
        val book = PlaceBook()
        JourneyStory.seed(i, book)
        JourneyStory.namingTargets(i, book).forEach { (a, b) -> book.add(a, b, "Town ${"%.1f".format(a)}", PlaceBook.Rank.GEOCODED) }
        return JourneyStory.build(i, book)
    }

    @Test fun one_stop_with_three_entries_reads_as_one_stop() {
        val s = story()
        val pumpStops = s.stops.filter { it.place == "Pallapatti" }
        assertEquals(1, pumpStops.size)
        val stop = pumpStops.single()
        assertEquals(setOf(Item.FUEL, Item.TOILET, Item.WATER), stop.items)
        assertEquals("Refuelled at Pallapatti", stop.title)
        assertEquals(20 * 60L, stop.seconds)
        assertTrue(s.stops.none { it.place == "Darapuram" })
    }

    @Test fun a_meal_logged_just_before_the_stop_was_detected_belongs_to_it() {
        val lunchStop = story().stops.single { it.meal == Nourishment.LUNCH }
        assertEquals("Lunch at Tirupur", lunchStop.title)
        assertEquals(t(13, 58), lunchStop.atMs)
        assertEquals(t(15), lunchStop.endMs)
    }

    @Test fun a_meal_logged_hours_later_keeps_its_time_and_says_so() {
        val dinner = story().stops.single { it.meal == Nourishment.DINNER }
        assertEquals(t(21, 30), dinner.atMs)
        assertTrue(dinner.loggedLater)
        assertTrue(dinner.title.contains("logged later at Palasamudram"))
    }

    @Test fun a_silence_is_told_as_an_estimated_stretch_and_explained() {
        val s = story()
        val offline = s.drives.single { it.offlineMs > 0 }
        assertTrue("estimate covers the jump north", offline.distanceM > 200_000)
        assertEquals("Palasamudram", offline.toPlace)
        assertTrue(s.paragraphs.joinToString(" "), s.paragraphs.any { it.contains("6 h 5 min") })
        assertEquals((6 * 60 + 5) * 60_000L, s.offlineMs)
    }

    @Test fun the_story_names_places_and_never_prints_coordinates_or_money() {
        val s = story()
        val all = (s.paragraphs + s.highlights.map { it.title + " " + it.detail } + s.stops.map { it.title }).joinToString(" ")
        assertTrue(all.contains("Tirupur")); assertTrue(all.contains("Pallapatti")); assertTrue(all.contains("Palasamudram"))
        assertFalse(Regex("""\d+\.\d{3,}""").containsMatchIn(all))
        assertFalse(all.contains("₹"))
    }

    @Test fun meals_logged_twice_count_once() {
        assertEquals(1, JourneyStory.occasions(listOf(t(21, 30), t(23, 35)), 6 * 3_600_000L))
        assertEquals(2, JourneyStory.occasions(listOf(t(13), t(21)), 6 * 3_600_000L))
        assertEquals(1, JourneyStory.occasions(listOf(t(13)), 20 * 60_000L))
        assertEquals(0, JourneyStory.occasions(emptyList(), 20 * 60_000L))
    }

    @Test fun a_removed_break_and_its_items_are_gone() {
        val evs = events() + ev(EventTypes.BREAK_CHECKPOINT, t(23, 45), null, null, mapOf("breakId" to "D", "removed" to true))
        val s = story(input(evs))
        assertNull(s.stops.firstOrNull { it.meal == Nourishment.DINNER })
        assertNull(s.meals[Nourishment.DINNER])
    }

    @Test fun a_cancelled_halt_is_not_a_halt() {
        val evs = events() + ev(EventTypes.HALT_CANCELLED, t(23, 45), 13.95, 77.68)
        assertTrue(story(input(evs)).halts.isEmpty())
        assertEquals("Night in a room at Palasamudram", story().halts.single().title)
    }

    @Test fun days_split_at_midnight_and_the_status_follows_the_halt() {
        val evs = events() + ev(EventTypes.BREAK_CHECKPOINT, t(24, 30), 13.95, 77.68, mapOf("breakId" to "B", "tea" to true, "place" to "Palasamudram"))
        val s = story(input(evs).copy(nowMs = t(25)))
        assertEquals(2, s.days.size)
        assertTrue(s.days[1].entries.any { it is Entry.Stop && it.items == setOf(Item.TEA) })
        assertEquals("Halted for the night", s.status)
    }

    @Test fun the_book_describes_unnamed_points_by_distance_from_a_named_one() {
        val book = PlaceBook()
        book.add(11.20, 77.41, "Tirupur")
        assertEquals("Tirupur", book.describe(11.21, 77.41))
        assertEquals("about 11 km from Tirupur", book.describe(11.30, 77.41))
        assertNull(book.describe(12.5, 77.41))
        book.add(11.0, 77.0, "11.000, 77.000")
        assertEquals(1, book.size)
    }

    @Test fun a_break_with_no_length_ends_when_the_car_moves_on_not_at_the_end_of_the_journey() {
        // Water logged on the move at 3:30 PM, no length given, car never stopped.
        val evs = events() + ev(EventTypes.BREAK_CHECKPOINT, t(15, 30), 11.51, 77.73, mapOf("breakId" to "W2", "water" to true))
        val s = story(input(evs, ended = t(23, 50)))
        val w = s.stops.first { Item.WATER in it.items && it.atMs == t(15, 30) }
        assertNotNull(w.endMs)
        assertTrue("ends within minutes, not hours: ${(w.endMs!! - w.atMs) / 60_000} min", w.endMs!! - w.atMs <= 5 * 60_000L)
        // And the day strip never paints the rest of the day as stopped.
        val stoppedAfter = s.segments.filter { it.phase == JourneyStory.Phase.STOPPED && it.fromMs >= t(15, 30) && it.toMs - it.fromMs > 30 * 60_000L }
        assertTrue(stoppedAfter.toString(), stoppedAfter.isEmpty())
    }

    @Test fun a_break_logged_during_a_halt_belongs_to_the_halt() {
        // Water at 11:45 PM, in the room: the strip stays a halt, and no drive starts from it.
        val evs = events() + ev(EventTypes.BREAK_CHECKPOINT, t(23, 45), 13.95, 77.68, mapOf("breakId" to "W3", "water" to true))
        val s = story(input(evs))
        assertTrue(s.segments.filter { it.fromMs >= t(23, 40) }.all { it.phase == JourneyStory.Phase.HALT })
        assertTrue(s.drives.none { it.atMs >= t(23, 40) })
    }

    @Test fun a_silence_the_car_moved_through_counts_as_driving() {
        val s = story()
        // Pump 5:20 PM → room 11:40 PM with the phone silent from 5:25: driving, less the 45-minute dinner logged later.
        val silent = s.drives.first { it.offlineMs > 0 }
        assertTrue(silent.movingSeconds in (5 * 3600L)..(6 * 3600L))
        assertTrue(s.movingSeconds >= silent.movingSeconds + 3 * 3600)
        assertTrue(s.longestDrive === silent)
        // Its distance is a road, not a straight line.
        assertTrue(silent.distanceM > 240_000.0)
        // The hours of the silence carry estimated distance; recorded hours carry none.
        assertTrue(s.kmByHour.any { it.estimatedM > 0 })
        assertTrue(s.kmByHour.filter { it.hourStartMs < t(17) }.all { it.estimatedM == 0.0 && it.metres > 0 })
        // The stop time is the stops and the halt, not the road.
        assertTrue(s.stoppedSeconds >= 3600 + 20 * 60)
    }

    @Test fun a_halt_nobody_resumed_ends_when_the_car_leaves() {
        // Room at 11:40 PM, no resume ever tapped; the car drives off north at 7 AM.
        val away = JourneyStory.Sample(t(31), 14.20, 77.60)
        val extra = listOf(JourneyStory.Sample(t(30, 55), 13.95, 77.68), away, JourneyStory.Sample(t(31, 30), 14.40, 77.55))
        val i = input(ended = t(32)).let { it.copy(samples = it.samples + extra, nowMs = t(32)) }
        val s = story(i)
        val halt = s.halts.single()
        assertNotNull(halt.endMs)
        assertTrue("halt should end around 7 AM, not at the journey's end", halt.endMs!! in t(30, 50)..t(31, 5))
        assertTrue(s.drives.any { it.atMs >= t(30, 50) })
    }

    @Test fun a_completed_journey_arrives() {
        val s = story(input(ended = t(23, 59)))
        assertEquals("Completed", s.status)
        assertTrue(s.days.flatMap { it.entries }.last() is Entry.Arrive)
        assertNotNull(s.headline)
        assertTrue(s.headline.contains("travelled"))
    }
}
