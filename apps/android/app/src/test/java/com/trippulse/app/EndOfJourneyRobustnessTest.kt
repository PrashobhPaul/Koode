package com.trippulse.app

import com.trippulse.app.data.EventCodec
import com.trippulse.app.data.export.JourneyDocuments
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.domain.*
import org.junit.Test
import java.util.UUID

class EndFlowReproTmpTest {
    private val t0 = 1_758_000_000_000L
    private fun at(h: Double) = t0 + (h * 3_600_000).toLong()
    private fun ev(type: String, h: Double, payload: Map<String, Any?> = emptyMap(), src: EventSource = EventSource.DRIVER_CONFIRMATION) =
        TripEvent(UUID.randomUUID().toString(), "T1", type, at(h), 15.0, 78.0, null, src, payload)

    private fun journey(): List<TripEvent> = buildList {
        add(ev(EventTypes.TRIP_STARTED, 0.0))
        add(ev(EventTypes.ROUTE_DEVIATION, 0.2, mapOf("distanceM" to 820.0)))
        add(ev(EventTypes.ROUTE_REJOINED, 0.3))
        add(ev(EventTypes.WATER_REPORTED, 0.9)); add(ev(EventTypes.BREAK_CHECKPOINT, 0.9, mapOf("water" to true)))
        add(ev(EventTypes.SNACK_REPORTED, 0.9, mapOf("kind" to "SNACK"))); add(ev(EventTypes.BREAK_CHECKPOINT, 0.9))
        add(ev(EventTypes.TOILET_REPORTED, 0.9)); add(ev(EventTypes.BREAK_CHECKPOINT, 0.9, mapOf("toilet" to true)))
        add(ev(EventTypes.STOP_STARTED, 0.92, src = EventSource.SYSTEM_INFERRED))
        add(ev(EventTypes.BREAK_CHECKPOINT_SKIPPED, 1.0))
        add(ev(EventTypes.STOP_ENDED, 1.05, mapOf("durationSeconds" to 180), EventSource.SYSTEM_INFERRED))
        for (k in 0 until 10) { add(ev(EventTypes.ROUTE_DEVIATION, 1.2 + k * 0.3, mapOf("distanceM" to 700))); add(ev(EventTypes.ROUTE_REJOINED, 1.3 + k * 0.3)) }
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
        val trip = ActiveTripEntity("T1","s","k","Hyderabad",17.38,78.48,"Bengaluru",12.97,77.59,null,null,t0,null,t0,at(9.8),null,"COMPLETED",true,true,570_000.0,null)
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
            report?.let { r -> step("pdfDoc") { JourneyDocuments.timeline(trip, events.map { EventCodec.toEntity(it, it.eventTimeMs, false) }, r, measures) } }
        }
        println("REPRO RESULT: " + if (failures.isEmpty()) "no exceptions" else failures.joinToString("\n"))
    }
}
