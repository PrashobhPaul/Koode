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
import com.trippulse.app.data.export.JourneyDocuments
import com.trippulse.app.data.export.JourneyPdf
import com.trippulse.app.data.local.ExpenseEntity
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.JourneyAnalytics
import com.trippulse.app.domain.Measures
import com.trippulse.app.domain.Nourishment
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
 * The journey, after the fact.
 *
 * The design intent: nobody should have to do arithmetic to understand their
 * own journey. Everything here is *derived* — how much of the time was
 * actually spent moving, how often breaks came, what it cost per kilometre,
 * what the vehicle returned — and the same [JourneyAnalytics.JourneyReport]
 * feeds both this screen and the exported PDFs, so they can never disagree.
 *
 * A completed journey is read-only. There are no edit affordances here at all,
 * because the timeline everyone followed has to stay the thing that happened.
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
    val report by vm.report.collectAsStateWithLifecycle()
    val measures = vm.measures

    fun export(kind: PdfKind) {
        val t = trip ?: return
        val r = report ?: return
        vm.exporting.value = true
        scope.launch {
            val doc = when (kind) {
                PdfKind.TIMELINE -> JourneyDocuments.timeline(t, events, r, measures)
                PdfKind.MONEY -> JourneyDocuments.money(t, expenses, r, measures)
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

            val r = report
            if (r == null) {
                KoodeCard {
                    Text(
                        "Working out the numbers…",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                JourneyDashboard(r, measures, TransportCatalog.isPrivate(trip?.transportMode))
            }

            // ---- exports ----
            SectionHeader("Keep a copy")
            KoodeCard {
                Text(
                    "Both documents carry the same figures you see above, the Koode watermark, " +
                        "and are flat non-editable PDFs generated on this phone.",
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.weight(1f)) {
                        SecondaryButton(
                            if (exporting) "Preparing…" else "Timeline PDF",
                            { export(PdfKind.TIMELINE) },
                            enabled = !exporting && trip != null && report != null,
                            leading = "🧾", height = 46.dp
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SecondaryButton(
                            if (exporting) "Preparing…" else "Money PDF",
                            { export(PdfKind.MONEY) },
                            enabled = !exporting && trip != null && report != null,
                            leading = "₹", accent = colors.traveller, height = 46.dp
                        )
                    }
                }
                Text(
                    "The money PDF is yours alone — it is never sent to anyone following you.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(Spacing.sm))
            PrimaryButton("Done", { nav.popBackStack(Routes.HOME, inclusive = false) })
            Spacer(Modifier.height(Spacing.scrollBottom))
        }
    }
}

// ---------------------------------------------------------------------------
// The dashboard — shared with the pre-closure review
// ---------------------------------------------------------------------------

/**
 * The analysed picture of a journey.
 *
 * Reused verbatim by the review sheet shown before a journey is closed, so the
 * traveller verifies exactly what everyone else will later read.
 */
@Composable
fun JourneyDashboard(
    report: JourneyAnalytics.JourneyReport,
    measures: Measures,
    privateVehicle: Boolean,
    compact: Boolean = false
) {
    val colors = KoodeTheme.colors

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        StatTile("Distance", measures.distance(report.distanceM), Modifier.weight(1f), colors.accent)
        StatTile("Moving", TimeFmt.durationShort(report.movingSeconds), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        StatTile("Total time", TimeFmt.durationShort(report.totalSeconds), Modifier.weight(1f))
        StatTile("Stopped", TimeFmt.durationShort(report.stoppedSeconds), Modifier.weight(1f))
    }
    if (!compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            StatTile("Average moving", measures.speed(report.averageMovingSpeedKmh), Modifier.weight(1f))
            StatTile("Door to door", measures.speed(report.overallSpeedKmh), Modifier.weight(1f))
        }
    }

    // ---- what the numbers mean ----
    if (report.insights.isNotEmpty()) {
        KoodeCard(title = "What the journey says", accent = colors.traveller) {
            report.insights.forEach {
                Text("• $it", color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    // ---- breaks & wellbeing ----
    KoodeCard(title = "Breaks and wellbeing") {
        DetailRow("Stops", report.stops.toString(), leading = "🅿")
        DetailRow("Breaks logged", report.breakCount.toString(), leading = "✅")
        report.averageGapBetweenBreaksSeconds?.let {
            DetailRow("A break about every", TimeFmt.durationShort(it), leading = "⏱")
        }
        DetailRow("Longest break", TimeFmt.durationShort(report.longestBreakSeconds), leading = "😴")
        DetailRow(
            "Longest stretch without stopping",
            TimeFmt.durationShort(report.longestLegSeconds), leading = "🛣"
        )
        Spacer(Modifier.height(Spacing.sm))
        listOf(
            Nourishment.BREAKFAST, Nourishment.LUNCH, Nourishment.DINNER,
            Nourishment.SNACK, Nourishment.TEA_COFFEE
        ).forEach { kind ->
            val count = report.meals[kind] ?: 0
            if (count > 0) DetailRow(kind.label, count.toString(), leading = kind.emoji)
        }
        if (report.waterCount > 0) DetailRow("Water", report.waterCount.toString(), leading = "💧")
        if (report.toiletCount > 0) DetailRow("Toilet", report.toiletCount.toString(), leading = "🚻")
        if (privateVehicle && report.fuelStops > 0) {
            DetailRow("Refuelling stops", report.fuelStops.toString(), leading = "⛽")
        }
    }

    // ---- stages ----
    if (report.legs.size > 1) {
        KoodeCard(title = "Stages") {
            report.legs.forEach { leg ->
                DetailRow(
                    "${leg.fromName} → ${leg.toName}",
                    leg.seconds?.let { TimeFmt.durationShort(it) } ?: "—",
                    leading = TransportCatalog.emoji(leg.mode)
                )
            }
        }
    }

    // ---- money ----
    if (report.hasCosts) {
        KoodeCard(title = "Money tracker · only you can see this") {
            report.costLines.forEach { line ->
                DetailRow(
                    line.label,
                    measures.money(line.amount),
                    leading = costEmoji(line.type)
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            DetailRow(
                "Total", measures.money(report.totalCost),
                emphasis = true, valueColor = colors.accent
            )
            measures.costPerDistance(report.totalCost, report.distanceM)?.let {
                DetailRow("Cost per ${measures.distanceUnit}", it, leading = "📐")
            }
            report.costPerHour?.let {
                DetailRow("Cost per hour", measures.money(it), leading = "⏳")
            }
            if (privateVehicle) {
                measures.efficiency(report.distanceM, report.litres)?.let {
                    DetailRow("Fuel efficiency", it, leading = "⛽", valueColor = colors.accent)
                }
                measures.electricEfficiency(report.distanceM, report.kwh)?.let {
                    DetailRow("EV efficiency", it, leading = "🔌", valueColor = colors.accent)
                }
            }
        }
    }
}

private fun costEmoji(type: String): String = when (type) {
    "FUEL" -> "⛽"
    "TICKET" -> "🎫"
    "FOOD" -> "🍛"
    "STAY" -> "🏨"
    else -> "🧾"
}

private enum class PdfKind { TIMELINE, MONEY }
