package com.trippulse.app

import com.trippulse.app.data.export.report.Paginator
import com.trippulse.app.data.export.report.Reports
import com.trippulse.app.data.export.report.Surface
import com.trippulse.app.data.export.report.TextStyle
import com.trippulse.app.domain.*
import com.trippulse.app.domain.report.JourneyStory
import com.trippulse.app.domain.report.PlaceBook
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class EndFlowReproTmpTest {
    private val t0 = 1_758_000_000_000L
    private fun at(h: Double) = t0 + (h * 3_600_000).toLong()
    private fun ev(type: String, h: Double, payload: Map<String, Any?> = emptyMap(), src: EventSource = EventSource.DRIVER_CONFIRMATION) =
        TripEvent(UUID.randomUUID().toString(), "T1", type, at(h), 15.0, 78.0, null, src, payload)

    private fun journey(): List<TripEvent> = buildList {
        add(ev(EventTypes.TRIP_STARTED, 0.0))
        add(ev(EventTypes.WATER_REPORTED, 0.9)); add(ev(EventTypes.BREAK_CHECKPOINT, 0.9, mapOf("water" to true)))
        add(ev(EventTypes.SNACK_REPORTED, 0.9, mapOf("kind" to "SNACK"))); add(ev(EventTypes.BREAK_CHECKPOINT, 0.9))
        add(ev(EventTypes.TOILET_REPORTED, 0.9)); add(ev(EventTypes.BREAK_CHECKPOINT, 0.9, mapOf("toilet" to true)))
        add(ev(EventTypes.STOP_STARTED, 0.92, src = EventSource.SYSTEM_INFERRED))
        add(ev(EventTypes.BREAK_CHECKPOINT_SKIPPED, 1.0))
        add(ev(EventTypes.STOP_ENDED, 1.05, mapOf("durationSeconds" to 180), EventSource.SYSTEM_INFERRED))
        add(ev(EventTypes.FUEL_STOP, 5.0, mapOf("amount" to 2500.0, "litres" to 25.0)))
        add(ev(EventTypes.FOOD_REPORTED, 5.0, mapOf("kind" to "BREAKFAST"))); add(ev(EventTypes.BREAK_CHECKPOINT, 5.0))
        add(ev(EventTypes.STOP_STARTED, 5.05)); add(ev(EventTypes.BREAK_CHECKPOINT_SKIPPED, 5.05))
        add(ev(EventTypes.DRIVING_STARTED, 5.4))
        add(ev(EventTypes.ARRIVAL_DETECTED, 9.4, src = EventSource.SYSTEM_INFERRED))
        add(ev(EventTypes.STOP_STARTED, 9.4)); add(ev(EventTypes.BREAK_CHECKPOINT_SKIPPED, 9.8))
        add(ev(EventTypes.TRIP_COMPLETED, 9.8, mapOf("distanceKm" to 850.0)))
    }

    private fun weird(): List<TripEvent> {
        val types = listOf("TRIP_CREATED","TRIP_STARTED","TRIP_PAUSED","TRIP_RESUMED","TRIP_COMPLETED","TRIP_EXPIRED","DESTINATION_CHANGED","LOCATION_UPDATE","DRIVING_STARTED","STOP_STARTED","STOP_ENDED","LONG_STOP","ROUTE_DEVIATION","ROUTE_REJOINED","ARRIVAL_DETECTED","BREAK_CHECKPOINT","BREAK_CHECKPOINT_SKIPPED","WATER_REPORTED","FOOD_REPORTED","TOILET_REPORTED","REST_REPORTED","FUEL_STOP","CHARGE_STOP","TEA_COFFEE_REPORTED","SNACK_REPORTED","BOARDED","TRANSIT_HALTED","TRANSIT_RESUMED","DEBOARDED","LEG_STARTED","LEG_COMPLETED","OVERNIGHT_CANDIDATE","OVERNIGHT_CONFIRMED","MORNING_RESUME","QUICK_NOTE","PASSENGER_JOINED","PASSENGER_LEFT","MEDICINE","VEHICLE_ISSUE","INCIDENT","POSSIBLE_INCIDENT","SOS_ACTIVATED","SOS_RESOLVED","SOS_DELIVERED","NETWORK_ONLINE","NETWORK_OFFLINE","BATTERY_LOW","ETA_UPDATED","DEVICE_SHUTDOWN","DEVICE_BACK_ONLINE","SIM_CHANGED")
        val payloads = listOf<Map<String, Any?>>(emptyMap(), mapOf("durationSeconds" to "x", "kind" to "NOPE", "gapMs" to null, "amount" to "12", "distanceM" to "far", "legIndex" to 99, "mode" to "SHIP", "text" to ""))
        return types.flatMapIndexed { i, ty -> payloads.map { p -> ev(ty, i * 0.1, p) } }
    }

    @Test fun reproduce() {
        val measures = Measures(UnitSystem.METRIC, MoneyFormat.RUPEE)
        val failures = mutableListOf<String>()
        for ((name, events, end) in listOf(Triple("realistic", journey(), at(9.8)), Triple("weird", weird(), at(9.8)), Triple("empty", emptyList(), t0), Triple("endBeforeStart", journey(), t0 - 1000))) {
            fun step(label: String, block: () -> Unit) = try { block() } catch (e: Throwable) {
                failures += "$name/$label: ${e::class.simpleName}: ${e.message}\n    at " + e.stackTrace.take(4).joinToString("\n    at ")
            }
            var report: JourneyAnalytics.JourneyReport? = null
            step("analyse") { report = JourneyAnalytics.analyse(JourneyAnalytics.Inputs(events, 850_000.0, t0, end,
                legs = listOf(JourneyAnalytics.LegInput(0, "CAR", "Hyderabad", "Bengaluru", t0, null)), topSpeedKmh = null)) }
            step("summary") { SummaryCalculator.compute(events, 850_000.0, t0, end) }
            step("narrate") { events.forEach { EventNarrator.line(it.type, it.payload) } }
            report?.let { r ->
                // Every report, laid out in full, from the same odd logs.
                val input = JourneyStory.Input("Asha", "Hyderabad", "Bengaluru", 17.38, 78.48, 12.97, 77.59, "CAR",
                    t0, end.takeIf { it > t0 }, at(10.0), events,
                    listOf(JourneyStory.Sample(t0, 17.38, 78.48), JourneyStory.Sample(at(5.0), 15.0, 78.0), JourneyStory.Sample(at(9.8), 12.97, 77.59)),
                    850_000.0)
                val book = PlaceBook().also { JourneyStory.seed(input, it) }
                var story: JourneyStory.Story? = null
                step("story") { story = JourneyStory.build(input, book) }
                story?.let { st ->
                    step("journeyReport") { layout(Reports.journey(Reports.JourneyInput(st, input, book, r, "TP-1", measures, t0))) }
                    step("expenseReport") { layout(Reports.expenses(Reports.ExpenseInput(st, input, book, r,
                        listOf(Reports.Expense(Expenses.Category.FUEL, "", 2500.0, 25.0, "L", at(5.0), null)), emptyList(), 2, null, "TP-1", measures, t0))) }
                    step("lastKnown") { layout(Reports.lastKnown(Reports.LastKnownInput(st, input, book, "TP-1", 15.0, 78.0, 9.0, null, at(5.0),
                        "No word from Asha", "", at(5.0), 3_600_000L, 40, null, emptyList(), null, t0))) }
                }
            }
        }
        println("REPRO RESULT: " + if (failures.isEmpty()) "no exceptions" else failures.joinToString("\n"))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private object Measure : Surface {
        override fun measure(text: String, style: TextStyle) = text.length * style.size * 0.5f
        override fun text(text: String, x: Float, baseline: Float, style: TextStyle) {}
        override fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float) {}
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {}
        override fun circle(cx: Float, cy: Float, r: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float) {}
        override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, dash: Float) {}
        override fun polyline(pts: FloatArray, color: Int, width: Float) {}
        override fun polygon(pts: FloatArray, color: Int) {}
        override fun picture(name: String, l: Float, t: Float, w: Float, h: Float, mirrored: Boolean) = true
        override fun avatar(cx: Float, cy: Float, r: Float) = true
        override fun mark(l: Float, t: Float, size: Float, alpha: Float) = true
    }

    private fun layout(r: com.trippulse.app.data.export.report.Report) {
        val pages = Paginator.paginate(Measure, r)
        pages.indices.forEach { Paginator.drawPage(Measure, r, pages, it) }
    }
}
