package com.trippulse.app.data.export

import com.trippulse.app.core.TimeFmt
import com.trippulse.app.data.EventCodec
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.EventEntity
import com.trippulse.app.data.local.ExpenseEntity
import com.trippulse.app.core.TripCredentials
import com.trippulse.app.domain.DarkAssessment
import com.trippulse.app.domain.Darkness
import com.trippulse.app.core.DeviceDossier
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
    /**
     * Everything the last-known-position document needs, as plain values.
     *
     * Deliberately not the Room entities. The people most likely to need this
     * document are the ones following the journey, and their phone has never
     * held an ActiveTripEntity -- only what arrived from the server. Taking
     * primitives lets the traveller's side and the follower's side produce a
     * byte-identical document from the two different shapes they each have.
     */
    data class LastKnown(
        val tripId: String,
        val originName: String,
        val destName: String,
        val startedAtMs: Long,
        val travellerName: String?,
        val lat: Double?,
        val lng: Double?,
        val accuracyM: Double?,
        val speedKmh: Double?,
        val fixAtMs: Long?,
        val simChangedAtMs: Long?,
        val assessment: DarkAssessment,
        /** The forensic device dossier — see core/DeviceDossier. */
        val device: Map<String, Any?> = emptyMap(),
        /**
         * The run-up, already turned into sentences by the caller.
         *
         * Narrated rather than raw for the same reason the rest of this type
         * is primitives: the traveller's side holds EventEntity rows and the
         * follower's side holds decoded maps, and the document must not know
         * or care which it is looking at.
         */
        val events: List<Moment>
    )

    /** One line of the run-up: when, and what happened. */
    data class Moment(val timeMs: Long, val label: String)

    /**
     * The document a family takes to a police station.
     *
     * Written for a reader who is not using this app and may be reading it
     * under fluorescent light while frightened: what the phone last knew,
     * exactly when, how accurate it was, and how it stopped reporting -- in
     * that order, on the first page, with no dashboard in front of it.
     *
     * Two rules govern the wording. Coordinates are given to six decimal
     * places and paired with their accuracy radius, because "within 12 metres"
     * and "within 2 kilometres" are different pieces of evidence and a bare
     * lat/long hides which one you have. And the document never speculates: it
     * records that a phone was switched off with 74% battery, which is a fact,
     * and says nothing whatsoever about what that might mean, which is not.
     */
    fun lastKnownPosition(input: LastKnown): JourneyPdf.Document {
        val who = input.travellerName?.takeIf { it.isNotBlank() } ?: "The traveller"
        val assessment = input.assessment
        val lat = input.lat
        val lng = input.lng
        val fixAt = input.fixAtMs

        val position = JourneyPdf.Section(
            title = "Last known position",
            header = null,
            rows = buildList {
                if (lat != null && lng != null) {
                    add(JourneyPdf.Row("", "Latitude", "%.6f".format(lat)))
                    add(JourneyPdf.Row("", "Longitude", "%.6f".format(lng)))
                    // Written so it can be typed straight into any map.
                    add(JourneyPdf.Row("", "Coordinates", "%.6f, %.6f".format(lat, lng)))
                    input.accuracyM?.let {
                        add(JourneyPdf.Row("", "Accurate to within", "${it.toInt()} m"))
                    }
                } else {
                    add(JourneyPdf.Row("", "Position", "No location was recorded"))
                }
                fixAt?.let {
                    add(JourneyPdf.Row("", "Recorded at", TimeFmt.dateTime(it)))
                }
                input.speedKmh?.let {
                    add(JourneyPdf.Row("", "Moving at", "${it.toInt()} km/h"))
                }
            },
            note = if (lat != null && lng != null) null
            else "The phone never obtained a location fix during this journey."
        )

        val circumstances = JourneyPdf.Section(
            title = "How reporting stopped",
            header = null,
            rows = buildList {
                add(JourneyPdf.Row("", "Assessment", Darkness.headline(assessment, who)))
                assessment.lastBatteryPct?.let {
                    add(JourneyPdf.Row("", "Battery at last report", "$it%"))
                }
                assessment.sinceMs?.let {
                    add(JourneyPdf.Row("", "Last contact", TimeFmt.dateTime(it)))
                }
                if (assessment.dark) {
                    add(
                        JourneyPdf.Row(
                            "", "Silent for",
                            TimeFmt.durationShort(assessment.elapsedMs / 1000)
                        )
                    )
                }
                input.simChangedAtMs?.let {
                    add(JourneyPdf.Row("", "SIM changed at", TimeFmt.dateTime(it)))
                }
            },
            note = Darkness.detail(assessment)
        )

        // The run-up. Everything, not just the milestones: on this document a
        // routine stop half an hour before the silence may be the most useful
        // line on the page.
        val leadUp = JourneyPdf.Section(
            title = "The hours before",
            header = JourneyPdf.Row("TIME", "WHAT HAPPENED", "DATE"),
            rows = input.events
                .sortedByDescending { it.timeMs }
                .take(LEAD_UP_ROWS)
                .reversed()
                .map { JourneyPdf.Row(TimeFmt.clock(it.timeMs), it.label, TimeFmt.date(it.timeMs)) }
        )

        val journey = JourneyPdf.Section(
            title = "The journey",
            header = null,
            rows = listOf(
                JourneyPdf.Row("", "Travelling from", input.originName),
                JourneyPdf.Row("", "Heading to", input.destName),
                JourneyPdf.Row("", "Journey number", TripCredentials.pretty(input.tripId)),
                JourneyPdf.Row("", "Started", TimeFmt.dateTime(input.startedAtMs))
            )
        )

        val device = deviceSection(input.device)

        return JourneyPdf.Document(
            title = "Last known position",
            subtitle = "$who — ${input.originName} to ${input.destName}",
            meta = listOf(
                "Prepared " + TimeFmt.dateTime(System.currentTimeMillis()),
                "Every time in this document is the phone's own local time.",
                "Positions come from the device's satellite and network fixes."
            ),
            sections = listOfNotNull(position, circumstances, device, journey, leadUp),
            fileLabel = "koode-last-known-position"
        )
    }

    /** Narrates stored rows into [Moment]s — the traveller's side. */
    fun momentsFrom(events: List<EventEntity>): List<Moment> = events
        .filter { it.type in EventTypes.TIMELINE_TYPES }
        .map {
            val (_, label) = EventNarrator.line(it.type, EventCodec.payloadFromJson(it.payloadJson))
            Moment(it.eventTimeMs, label)
        }

    /** Narrates the decoded maps a follower receives — the same words, either way. */
    fun momentsFromCloud(events: List<Map<String, Any?>>): List<Moment> = events.mapNotNull { e ->
        val type = e["type"] as? String ?: return@mapNotNull null
        if (type !in EventTypes.TIMELINE_TYPES) return@mapNotNull null
        val at = (e["t"] as? Number)?.toLong() ?: (e["eventTime"] as? Number)?.toLong()
            ?: return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val payload = (e["payload"] as? Map<String, Any?>).orEmpty()
        val (_, label) = EventNarrator.line(type, payload)
        Moment(at, label)
    }

    /**
     * The device section: everything a police or cyber report can use to
     * identify the phone, and an explicit line for each thing Android will not
     * let an ordinary app read.
     *
     * The absences are printed rather than omitted, because a report that
     * silently lacks an IMEI looks like an oversight; one that says "IMEI: not
     * available — Android 10+ blocks it for non-system apps" tells the reader
     * it was sought and why it is missing. Returns null only when there is no
     * dossier at all.
     */
    private fun deviceSection(device: Map<String, Any?>): JourneyPdf.Section? {
        if (device.isEmpty()) return null
        fun str(k: String) = (device[k] as? String)?.takeIf { it.isNotBlank() }
        fun num(k: String) = (device[k] as? Number)?.toString()

        return JourneyPdf.Section(
            title = "The device",
            header = null,
            rows = buildList {
                add(JourneyPdf.Row("", "Phone", DeviceDossier.describe(device)))
                str("model")?.let { add(JourneyPdf.Row("", "Model number", it)) }
                num("androidSdk")?.let {
                    val rel = str("androidRelease")
                    add(JourneyPdf.Row("", "Android", (rel?.let { r -> "$r " } ?: "") + "(API $it)"))
                }
                str("securityPatch")?.let { add(JourneyPdf.Row("", "Security patch", it)) }
                str("publicIp")?.let { add(JourneyPdf.Row("", "Public IP at last contact", it)) }
                str("localIp")?.let { add(JourneyPdf.Row("", "Local IP", it)) }
                str("androidId")?.let { add(JourneyPdf.Row("", "Android ID", it)) }
                str("installId")?.let { add(JourneyPdf.Row("", "Koode install ID", it)) }
                // The honest absences, always shown.
                add(JourneyPdf.Row("", "IMEI", str("imeiNote") ?: "Not available on this device"))
                add(JourneyPdf.Row("", "Hardware MAC", str("macNote") ?: "Not available on this device"))
            },
            note = "Identifiers Android permits an ordinary app to read. IMEI and " +
                "the hardware MAC are withheld by the operating system itself, not " +
                "by Koode; a subpoena to the carrier or manufacturer, using the " +
                "public IP and the times above, is how those are recovered."
        )
    }

    /** How much of the run-up to print. Enough to see a pattern, few enough to read. */
    private const val LEAD_UP_ROWS = 40

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
