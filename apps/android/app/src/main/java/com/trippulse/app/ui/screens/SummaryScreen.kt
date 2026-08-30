package com.trippulse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.data.EventCodec
import com.trippulse.app.data.export.JourneyPdf
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.EventEntity
import com.trippulse.app.data.local.ExpenseEntity
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.SummaryVm
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.DetailRow
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.LocalWindowClass
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.SecondaryButton
import com.trippulse.app.ui.components.SectionHeader
import com.trippulse.app.ui.components.StatTile
import com.trippulse.app.ui.map.JourneyMap
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The journey, after the fact: what happened, how far, how long, what it cost —
 * and the two PDFs the traveller can keep.
 *
 * The map here is the same [JourneyMap] used live, so the ▶ playback of the
 * whole journey is right where you'd look for it rather than behind a separate
 * "Replay" screen.
 */
@Composable
fun SummaryScreen(nav: NavHostController, tripId: String) {
    val vm: SummaryVm = viewModel(factory = SummaryVm.factory(tripId))
    val colors = KoodeTheme.colors
    val windowClass = LocalWindowClass.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val trip by vm.trip.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val samples by vm.samples.collectAsStateWithLifecycle()
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()

    val summary = remember(events) {
        events.firstOrNull { it.type == EventTypes.TRIP_COMPLETED }
            ?.let { EventCodec.payloadFromJson(it.payloadJson) }
    }

    fun export(kind: PdfKind) {
        val t = trip ?: return
        vm.exporting.value = true
        scope.launch {
            val doc = when (kind) {
                PdfKind.TIMELINE -> timelineDocument(t, events)
                PdfKind.MONEY -> moneyDocument(t, expenses, summary)
            }
            val file = JourneyPdf.write(context, doc)
            vm.lastExport.value = file
            vm.exporting.value = false
            context.startActivity(JourneyPdf.shareIntent(context, file, doc.title))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(Spacing.lg))
        AdaptiveContainer {
            Text("Journey summary", color = colors.textHigh, style = MaterialTheme.typography.displaySmall)
            Text(
                "${trip?.originName ?: "Start"} → ${trip?.destName ?: "Destination"}",
                color = colors.textMid, style = MaterialTheme.typography.bodyLarge
            )
            trip?.completedAtMs?.let {
                Text(TimeFmt.dateTime(it), color = colors.textLow, style = MaterialTheme.typography.bodySmall)
            }

            // ---- the route, replayable ----
            if (samples.size >= 2) {
                JourneyMap(
                    origin = samples.firstOrNull()?.let { GeoPoint(it.lat, it.lng) },
                    destination = trip?.let { GeoPoint(it.destLat, it.destLng) },
                    current = samples.lastOrNull()?.let { GeoPoint(it.lat, it.lng) },
                    breadcrumb = remember(samples) { samples.map { GeoPoint(it.lat, it.lng) } },
                    breadcrumbTimesMs = remember(samples) { samples.map { it.tMs } },
                    live = false,
                    height = windowClass.mapHeight,
                    showPlayControl = true
                )
                Text(
                    "Press ▶ to watch the journey play back — tap the speed to go faster.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }

            if (summary == null) {
                KoodeCard {
                    Text(
                        "The summary appears once the journey is ended.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                fun d(k: String) = (summary[k] as? Number)?.toDouble() ?: 0.0
                fun l(k: String) = (summary[k] as? Number)?.toLong() ?: 0L
                fun i(k: String) = (summary[k] as? Number)?.toInt() ?: 0

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    StatTile("Distance", "%.0f km".format(d("distanceKm")), Modifier.weight(1f), colors.accent)
                    StatTile("Travelling", TimeFmt.durationShort(l("drivingSeconds")), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    StatTile("Total time", TimeFmt.durationShort(l("totalSeconds")), Modifier.weight(1f))
                    StatTile("Days", i("days").toString(), Modifier.weight(1f))
                }

                KoodeCard(title = "What was logged") {
                    DetailRow("Stops", i("stops").toString(), leading = "🅿")
                    DetailRow("Meals", i("foodBreaks").toString(), leading = "🍛")
                    DetailRow("Tea / coffee", i("teaCoffee").toString(), leading = "☕")
                    DetailRow("Snacks", i("snacks").toString(), leading = "🍪")
                    DetailRow("Water", i("waterConfirmations").toString(), leading = "💧")
                    DetailRow("Toilet", i("toiletBreaks").toString(), leading = "🚻")
                    DetailRow("Rest", i("restBreaks").toString(), leading = "😴")
                    if (TransportCatalog.isPrivate(trip?.transportMode)) {
                        DetailRow("Refuelling", i("fuelStops").toString(), leading = "⛽")
                    }
                }

                // ---- money tracker ----
                if (expenses.isNotEmpty()) {
                    val distKm = d("distanceKm")
                    MoneyCard(expenses, distKm, TransportCatalog.isPrivate(trip?.transportMode))
                }
            }

            // ---- exports ----
            SectionHeader("Keep a copy")
            KoodeCard {
                Text(
                    "Both documents carry the Koode watermark and are flat, non-editable PDFs — " +
                        "generated on this phone, shared only when you choose to.",
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.weight(1f)) {
                        SecondaryButton(
                            if (exporting) "Preparing…" else "Timeline PDF",
                            { export(PdfKind.TIMELINE) },
                            enabled = !exporting && trip != null,
                            leading = "🧾", height = 46.dp
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SecondaryButton(
                            if (exporting) "Preparing…" else "Money PDF",
                            { export(PdfKind.MONEY) },
                            enabled = !exporting && trip != null,
                            leading = "₹", accent = colors.traveller, height = 46.dp
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            PrimaryButton("Done", { nav.popBackStack(Routes.HOME, inclusive = false) })
            Spacer(Modifier.height(Spacing.scrollBottom))
        }
    }
}

@Composable
private fun MoneyCard(expenses: List<ExpenseEntity>, distanceKm: Double, privateVehicle: Boolean) {
    val colors = KoodeTheme.colors
    val fuelCost = expenses.filter { it.type == "FUEL" }.sumOf { it.amount }
    val litres = expenses.filter { it.type == "FUEL" && it.unit == "L" }.sumOf { it.quantity ?: 0.0 }
    val kwh = expenses.filter { it.type == "FUEL" && it.unit == "kWh" }.sumOf { it.quantity ?: 0.0 }
    val ticket = expenses.filter { it.type == "TICKET" }.sumOf { it.amount }
    val food = expenses.filter { it.type == "FOOD" }.sumOf { it.amount }
    val stay = expenses.filter { it.type == "STAY" }.sumOf { it.amount }
    val other = expenses.filter { it.type == "OTHER" }.sumOf { it.amount }
    val total = fuelCost + ticket + food + stay + other

    KoodeCard(title = "Money tracker · only you can see this") {
        if (fuelCost > 0) DetailRow("Fuel", JourneyPdf.money(fuelCost), leading = "⛽")
        if (ticket > 0) DetailRow("Tickets", JourneyPdf.money(ticket), leading = "🎫")
        if (food > 0) DetailRow("Food", JourneyPdf.money(food), leading = "🍛")
        if (stay > 0) DetailRow("Stay", JourneyPdf.money(stay), leading = "🏨")
        if (other > 0) DetailRow("Other", JourneyPdf.money(other), leading = "🧾")
        Spacer(Modifier.height(Spacing.sm))
        DetailRow("Total", JourneyPdf.money(total), emphasis = true, valueColor = colors.accent)
        if (distanceKm > 0 && total > 0) {
            Text(
                "%.2f per km overall".format(total / distanceKm),
                color = colors.textLow, style = MaterialTheme.typography.bodySmall
            )
        }
        if (privateVehicle && litres > 0 && distanceKm > 0) {
            Text(
                "Fuel efficiency: %.1f km/L (%.1f L used)".format(distanceKm / litres, litres),
                color = colors.accent, style = MaterialTheme.typography.titleSmall
            )
        }
        if (privateVehicle && kwh > 0 && distanceKm > 0) {
            Text(
                "EV efficiency: %.1f km/kWh (%.1f kWh used)".format(distanceKm / kwh, kwh),
                color = colors.accent, style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

// ---------------------------------------------------------------------------
// PDF documents
// ---------------------------------------------------------------------------

private enum class PdfKind { TIMELINE, MONEY }

/** Every timeline entry, in order, as a printable statement. */
private fun timelineDocument(trip: ActiveTripEntity, events: List<EventEntity>): JourneyPdf.Document {
    val rows = events
        .filter { it.type in EventTypes.TIMELINE_TYPES }
        .sortedBy { it.eventTimeMs }
        .map { e ->
            val payload = EventCodec.payloadFromJson(e.payloadJson)
            val (_, label) = eventLine(e.type, payload)
            JourneyPdf.Row(
                left = TimeFmt.clock(e.eventTimeMs),
                middle = label,
                right = TimeFmt.date(e.eventTimeMs)
            )
        }
    return JourneyPdf.Document(
        title = "Journey timeline",
        subtitle = "${trip.originName} → ${trip.destName}",
        meta = listOfNotNull(
            "Journey number: ${trip.tripId}",
            trip.startedAtMs?.let { "Started: ${TimeFmt.dateTime(it)}" },
            trip.completedAtMs?.let { "Ended: ${TimeFmt.dateTime(it)}" },
            "Mode: ${TransportCatalog.label(trip.transportMode)}"
        ),
        sections = listOf(
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

/** The money tracker, itemised, as a printable statement. */
private fun moneyDocument(
    trip: ActiveTripEntity,
    expenses: List<ExpenseEntity>,
    summary: Map<String, Any?>?
): JourneyPdf.Document {
    val rows = expenses.sortedBy { it.tMs }.map { e ->
        JourneyPdf.Row(
            left = TimeFmt.date(e.tMs),
            middle = buildString {
                append(e.item.ifBlank { e.type.lowercase().replaceFirstChar { c -> c.uppercase() } })
                if (e.quantity != null && e.unit != null) append("  (${e.quantity} ${e.unit})")
            },
            right = JourneyPdf.money(e.amount)
        )
    }
    val total = expenses.sumOf { it.amount }
    val distanceKm = (summary?.get("distanceKm") as? Number)?.toDouble() ?: 0.0
    val byType = expenses.groupBy { it.type }.map { (type, list) ->
        JourneyPdf.Row(
            left = "",
            middle = type.lowercase().replaceFirstChar { it.uppercase() },
            right = JourneyPdf.money(list.sumOf { it.amount })
        )
    }

    return JourneyPdf.Document(
        title = "Journey costs",
        subtitle = "${trip.originName} → ${trip.destName}",
        meta = listOfNotNull(
            "Journey number: ${trip.tripId}",
            trip.completedAtMs?.let { "Ended: ${TimeFmt.dateTime(it)}" },
            if (distanceKm > 0) "Distance: %.0f km".format(distanceKm) else null
        ),
        sections = listOf(
            JourneyPdf.Section(
                title = "Items",
                header = JourneyPdf.Row("DATE", "ITEM", "AMOUNT"),
                rows = rows,
                note = if (rows.isEmpty()) "No expenses were recorded for this journey." else null
            ),
            JourneyPdf.Section(
                title = "Totals",
                header = null,
                rows = byType + JourneyPdf.Row("", "TOTAL", JourneyPdf.money(total)),
                note = if (distanceKm > 0 && total > 0)
                    "Cost per kilometre: %.2f".format(total / distanceKm) else null
            )
        ),
        fileLabel = "Koode-costs-${trip.tripId}"
    )
}
