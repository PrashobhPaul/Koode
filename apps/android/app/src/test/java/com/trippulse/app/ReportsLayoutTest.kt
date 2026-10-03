package com.trippulse.app

import com.trippulse.app.data.export.report.Face
import com.trippulse.app.data.export.report.Paginator
import com.trippulse.app.data.export.report.Report
import com.trippulse.app.data.export.report.Reports
import com.trippulse.app.data.export.report.Surface
import com.trippulse.app.data.export.report.TextStyle
import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Expenses
import com.trippulse.app.domain.Measures
import com.trippulse.app.domain.TripEvent
import com.trippulse.app.domain.report.JourneyStory
import com.trippulse.app.domain.report.PlaceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Lays the three reports out on a recording surface and checks what lands on the page. */
class ReportsLayoutTest {

    /** Measures text as an average glyph width and records everything drawn. */
    private class Recorder : Surface {
        val texts = ArrayList<Pair<String, TextStyle>>()
        val pictures = ArrayList<String>()
        override fun measure(text: String, style: TextStyle) = text.length * style.size * 0.52f
        override fun text(text: String, x: Float, baseline: Float, style: TextStyle) { texts += text to style.forText(text) }
        override fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float) {}
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {}
        override fun circle(cx: Float, cy: Float, r: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float) {}
        override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, dash: Float) {}
        override fun polyline(pts: FloatArray, color: Int, width: Float) {}
        override fun polygon(pts: FloatArray, color: Int) {}
        override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, color: Int, width: Float) {}
        override fun picture(name: String, l: Float, t: Float, w: Float, h: Float, mirrored: Boolean): Boolean { pictures += name; return true }
        override fun avatar(cx: Float, cy: Float, r: Float) = true
        override fun mark(l: Float, t: Float, size: Float, alpha: Float) = true
    }

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val t0 = 1_790_923_329_000L
    private var n = 0
    private fun ev(type: String, min: Int, lat: Double, lng: Double, p: Map<String, Any?> = emptyMap()) =
        TripEvent("e${n++}", "T", type, t0 + min * 60_000L, lat, lng, null, EventSource.SYSTEM_INFERRED, p)

    private val input = JourneyStory.Input(
        who = "Prashobh Paul", origin = "Home", destination = "Raheja Vistas, Nacharam",
        originLat = 10.56, originLng = 76.16, destLat = 17.43, destLng = 78.56,
        mode = "CAR", startedAtMs = t0, endedAtMs = null, nowMs = t0 + 8 * 3_600_000L,
        events = (0 until 14).flatMap { k ->
            val lat = 10.6 + k * 0.1; val lng = 76.2 + k * 0.12
            listOf(
                ev(EventTypes.STOP_STARTED, k * 30 + 20, lat, lng),
                ev(EventTypes.BREAK_CHECKPOINT, k * 30 + 22, lat, lng, mapOf("breakId" to "b$k", "water" to true, "food" to (k % 3 == 0), "meal" to "LUNCH".takeIf { k % 3 == 0 }, "place" to "Town $k")),
                ev(EventTypes.STOP_ENDED, k * 30 + 32, lat, lng),
                ev(EventTypes.TOLL_CROSSED, k * 30 + 40, lat + 0.05, lng + 0.05, mapOf("plaza" to "Plaza $k"))
            )
        },
        samples = (0..480).map { m -> JourneyStory.Sample(t0 + m * 60_000L, 10.56 + m * 0.003, 76.16 + m * 0.0036) },
        distanceM = 412_000.0, routeDistanceM = 990_000.0, zone = zone
    )

    private val book = PlaceBook().also { JourneyStory.seed(input, it) }
    private val story = JourneyStory.build(input, book)

    private fun render(r: Report): Recorder {
        val rec = Recorder()
        val pages = Paginator.paginate(rec, r)
        // Nothing runs into the footer.
        pages.forEach { page ->
            page.forEach { p ->
                val w = if (p.block.fullBleed) Paginator.PAGE_W else Paginator.PAGE_W - 2 * Paginator.MARGIN
                assertTrue("block overflows the page", p.y + p.block.height(rec, w) <= Paginator.PAGE_H - 50f + 0.5f || p.y <= 70f)
            }
        }
        pages.indices.forEach { Paginator.drawPage(rec, r, pages, it) }
        return rec
    }

    @Test fun the_journey_report_tells_the_story_with_pictures_and_no_coordinates() {
        val rec = render(Reports.journey(Reports.JourneyInput(story, input, book, null, "TP-7235 1576", Measures.INDIA, t0)))
        val text = rec.texts.joinToString(" ") { it.first }
        assertTrue(text.contains("The story")); assertTrue(text.contains("Stop by stop")); assertTrue(text.contains("Town 3"))
        assertFalse("no raw coordinates", Regex("""\d{1,3}\.\d{4,}""").containsMatchIn(text))
        assertFalse("never money", text.contains("₹"))
        assertTrue(rec.pictures.containsAll(listOf("car", "water", "restaurant")))
        assertTrue(text.contains("Page 1 of"))
    }

    @Test fun the_heading_face_never_draws_a_rupee_or_an_arrow() {
        val ex = listOf(Reports.Expense(Expenses.Category.FUEL, "Diesel", 4200.0, 42.0, "L", t0 + 3_600_000L, null))
        val rec = render(Reports.expenses(Reports.ExpenseInput(story, input, book, com.trippulse.app.domain.JourneyAnalytics.analyse(
            com.trippulse.app.domain.JourneyAnalytics.Inputs(input.events, input.distanceM, t0, input.nowMs)
        ), ex, emptyList(), 0, null, "TP-7235 1576", Measures.INDIA, t0)))
        assertTrue(rec.texts.any { it.first.contains("₹4,200") })
        assertTrue(rec.texts.none { (s, st) -> st.face == Face.HEAD && (s.contains("₹") || s.contains("→")) })
    }

    @Test fun the_emergency_document_gives_a_name_and_the_coordinates() {
        val rec = render(Reports.lastKnown(Reports.LastKnownInput(
            story, input, book, "TP-7235 1576", 11.951657, 77.720276, 7.0, 0.0, t0 + 7 * 3_600_000L,
            "No word from Prashobh Paul", "The phone has not been able to reach us.",
            t0 + 7 * 3_600_000L, 3_600_000L, 97, null, listOf("Phone" to "Samsung SM-A556E"), null, t0 + 8 * 3_600_000L
        )))
        val text = rec.texts.joinToString(" ") { it.first }
        assertTrue(text.contains("11.951657, 77.720276"))
        assertTrue("named, not just numbers", text.contains("Town"))
        assertTrue(text.contains("Last position"))
        assertEquals(0, rec.texts.count { it.first == "The traveller" })
    }
}
