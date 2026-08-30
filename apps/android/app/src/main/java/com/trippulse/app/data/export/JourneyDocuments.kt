package com.trippulse.app.data.export

import com.trippulse.app.core.TimeFmt
import com.trippulse.app.data.EventCodec
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.EventEntity
import com.trippulse.app.data.local.ExpenseEntity
import com.trippulse.app.domain.EventNarrator
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.JourneyAnalytics
import com.trippulse.app.domain.Measures
import com.trippulse.app.domain.TransportCatalog

/**
 * The two documents a finished journey produces.
 *
 * Built here rather than on a screen because two callers need them: the
 * summary screen's export buttons, and the automatic WhatsApp delivery that
 * fires when a traveller closes a journey. A second copy of this logic would
 * eventually produce two different PDFs from the same journey.
 *
 * The split between them is a privacy boundary, not a formatting choice:
 *  - [timeline] is shareable. It carries the journey's story and its analysed
 *    dashboard, and **never** any money.
 *  - [money] is the traveller's own. It never leaves the device except by an
 *    explicit share the traveller performs themselves.
 */
object JourneyDocuments {

    /** The headline figures every export opens with. */
    fun figures(
        report: JourneyAnalytics.JourneyReport,
        measures: Measures,
        includeMoney: Boolean
    ): List<JourneyPdf.Figure> = buildList {
        add(JourneyPdf.Figure("Distance", measures.distance(report.distanceM)))
        add(JourneyPdf.Figure("Moving time", TimeFmt.durationShort(report.movingSeconds)))
        add(JourneyPdf.Figure("Total time", TimeFmt.durationShort(report.totalSeconds)))
        add(JourneyPdf.Figure("Stopped", TimeFmt.durationShort(report.stoppedSeconds)))
        add(JourneyPdf.Figure("Average moving", measures.speed(report.averageMovingSpeedKmh)))
        add(JourneyPdf.Figure("Breaks", report.breakCount.toString()))
        if (includeMoney && report.hasCosts) {
            add(JourneyPdf.Figure("Total cost", measures.money(report.totalCost)))
            measures.costPerDistance(report.totalCost, report.distanceM)?.let {
                add(JourneyPdf.Figure("Per ${measures.distanceUnit}", it))
            }
            measures.efficiency(report.distanceM, report.litres)?.let {
                add(JourneyPdf.Figure("Efficiency", it))
            }
        }
    }

    /**
     * What happened, when — with the analysed dashboard on top so the reader
     * gets the answer before the detail. Safe to send to followers.
     */
    fun timeline(
        trip: ActiveTripEntity,
        events: List<EventEntity>,
        report: JourneyAnalytics.JourneyReport,
        measures: Measures
    ): JourneyPdf.Document {
        val rows = events
            .filter { it.type in EventTypes.TIMELINE_TYPES }
            .sortedBy { it.eventTimeMs }
            .map { e ->
                val payload = EventCodec.payloadFromJson(e.payloadJson)
                val (_, label) = EventNarrator.line(e.type, payload)
                JourneyPdf.Row(
                    left = TimeFmt.clock(e.eventTimeMs),
                    middle = label,
                    right = TimeFmt.date(e.eventTimeMs)
                )
            }

        val wellbeing = JourneyPdf.Section(
            title = "Breaks and wellbeing",
            header = null,
            rows = buildList {
                add(JourneyPdf.Row("", "Stops", report.stops.toString()))
                add(JourneyPdf.Row("", "Breaks logged", report.breakCount.toString()))
                report.averageGapBetweenBreaksSeconds?.let {
                    add(JourneyPdf.Row("", "A break about every", TimeFmt.durationShort(it)))
                }
                add(JourneyPdf.Row("", "Longest break", TimeFmt.durationShort(report.longestBreakSeconds)))
                add(
                    JourneyPdf.Row(
                        "", "Longest stretch without stopping",
                        TimeFmt.durationShort(report.longestLegSeconds)
                    )
                )
                report.meals.forEach { (kind, count) ->
                    if (count > 0) add(JourneyPdf.Row("", kind.label, count.toString()))
                }
                if (report.waterCount > 0) add(JourneyPdf.Row("", "Water", report.waterCount.toString()))
                if (report.toiletCount > 0) add(JourneyPdf.Row("", "Toilet", report.toiletCount.toString()))
            }
        )

        val stages = if (report.legs.size > 1) {
            JourneyPdf.Section(
                title = "Stages",
                header = JourneyPdf.Row("", "ROUTE", "TIME"),
                rows = report.legs.map { leg ->
                    JourneyPdf.Row(
                        left = TransportCatalog.label(leg.mode),
                        middle = "${leg.fromName} → ${leg.toName}",
                        right = leg.seconds?.let { TimeFmt.durationShort(it) } ?: "—"
                    )
                }
            )
        } else null

        return JourneyPdf.Document(
            title = "Journey timeline",
            subtitle = "${trip.originName} → ${trip.destName}",
            meta = listOfNotNull(
                "Journey number: ${trip.tripId}",
                trip.startedAtMs?.let { "Started: ${TimeFmt.dateTime(it)}" },
                trip.completedAtMs?.let { "Ended: ${TimeFmt.dateTime(it)}" },
                "Mode: ${TransportCatalog.label(trip.transportMode)}"
            ),
            figures = figures(report, measures, includeMoney = false),
            insights = report.insights,
            sections = listOfNotNull(
                wellbeing,
                stages,
                JourneyPdf.Section(
                    title = "Everything that happened",
                    header = JourneyPdf.Row("TIME", "EVENT", "DATE"),
                    rows = rows,
                    note = if (rows.isEmpty()) "No events were recorded for this journey." else null
                )
            ),
            fileLabel = "Koode-timeline-${trip.tripId}"
        )
    }

    /** Itemised, totalled and analysed. Never shared automatically. */
    fun money(
        trip: ActiveTripEntity,
        expenses: List<ExpenseEntity>,
        report: JourneyAnalytics.JourneyReport,
        measures: Measures
    ): JourneyPdf.Document {
        val rows = expenses.sortedBy { it.tMs }.map { e ->
            JourneyPdf.Row(
                left = TimeFmt.date(e.tMs),
                middle = buildString {
                    append(e.item.ifBlank { e.type.lowercase().replaceFirstChar { c -> c.uppercase() } })
                    if (e.quantity != null && e.unit != null) append("  (${e.quantity} ${e.unit})")
                },
                right = measures.money(e.amount)
            )
        }

        val totals = buildList {
            report.costLines.forEach { add(JourneyPdf.Row("", it.label, measures.money(it.amount))) }
            add(JourneyPdf.Row("", "TOTAL", measures.money(report.totalCost)))
            measures.costPerDistance(report.totalCost, report.distanceM)?.let {
                add(JourneyPdf.Row("", "Cost per ${measures.distanceUnit}", it))
            }
            report.costPerHour?.let { add(JourneyPdf.Row("", "Cost per hour", measures.money(it))) }
            measures.efficiency(report.distanceM, report.litres)?.let {
                add(JourneyPdf.Row("", "Fuel efficiency", it))
            }
            measures.electricEfficiency(report.distanceM, report.kwh)?.let {
                add(JourneyPdf.Row("", "EV efficiency", it))
            }
        }

        return JourneyPdf.Document(
            title = "Journey costs",
            subtitle = "${trip.originName} → ${trip.destName}",
            meta = listOfNotNull(
                "Journey number: ${trip.tripId}",
                trip.completedAtMs?.let { "Ended: ${TimeFmt.dateTime(it)}" },
                "Distance: ${measures.distance(report.distanceM)}",
                "Currency: ${measures.currency.code}"
            ),
            figures = figures(report, measures, includeMoney = true),
            insights = report.insights.filter { it.contains("cost", ignoreCase = true) },
            sections = listOf(
                JourneyPdf.Section(
                    title = "Items",
                    header = JourneyPdf.Row("DATE", "ITEM", "AMOUNT"),
                    rows = rows,
                    note = if (rows.isEmpty()) "No expenses were recorded for this journey." else null
                ),
                JourneyPdf.Section(title = "Totals", header = null, rows = totals)
            ),
            fileLabel = "Koode-costs-${trip.tripId}"
        )
    }
}
