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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
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
import com.trippulse.app.domain.JourneyClosure
import com.trippulse.app.ui.DriverVm
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
 * A closed journey is first a review: the traveller checks what Koode
 * recorded, may correct the destination or end time, and approves what the
 * people following them receive. Once approved it is read-only. Expenses are
 * confirmed separately and privately; their PDF is saved on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val originLabel by vm.originLabel.collectAsStateWithLifecycle()
    val destLabel by vm.destLabel.collectAsStateWithLifecycle()
    val fastagSummary by vm.fastagSummary.collectAsStateWithLifecycle()
    val closure by vm.closure.collectAsStateWithLifecycle()
    val approving by vm.approving.collectAsStateWithLifecycle()
    val measures = vm.measures
    val reviewing = closure?.pendingReview == true
    var safeConfirmed by remember { mutableStateOf(false) }
    var editingDestination by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    val sender: DriverVm = viewModel(factory = DriverVm.factory(tripId))

    // Opening the review is itself recorded (once).
    LaunchedEffect(closure?.stage) {
        if (closure?.stage == JourneyClosure.Lifecycle.CLOSED_PENDING_REVIEW) vm.startReview()
    }

    /** The private expense PDF, saved on this phone once the traveller confirms. */
    fun confirmExpensesAndSave() {
        val t = trip ?: return
        val r = report ?: return
        vm.exporting.value = true
        scope.launch {
            try {
                vm.confirmExpenses()
                val doc = JourneyDocuments.money(t, expenses, r, measures, originLabel = originLabel, destLabel = destLabel)
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { JourneyPdf.write(context, doc) }
                val where = JourneyPdf.saveToDownloads(context, file, "Koode-expenses-${t.tripId}.pdf")
                android.widget.Toast.makeText(
                    context,
                    where?.let { "Expenses confirmed · saved to $it" } ?: "Expenses confirmed. Use Money PDF below to keep a copy.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.util.Log.e("Summary", "Expense PDF failed", e)
                android.widget.Toast.makeText(context, "Couldn't save the expense PDF. Please try again.", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                vm.exporting.value = false
            }
        }
    }

    fun export(kind: PdfKind) {
        val t = trip ?: return
        val r = report ?: return
        vm.exporting.value = true
        scope.launch {
            try {
                val doc = when (kind) {
                    PdfKind.TIMELINE -> JourneyDocuments.timeline(
                        t, events, r, measures, path = samples.map { it.lat to it.lng },
                        originLabel = originLabel, destLabel = destLabel, fastagSummary = fastagSummary
                    )
                    PdfKind.MONEY -> JourneyDocuments.money(
                        t, expenses, r, measures,
                        originLabel = originLabel, destLabel = destLabel
                    )
                }
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { JourneyPdf.write(context, doc) }
                vm.lastExport.value = file
                context.startActivity(JourneyPdf.shareIntent(context, file, doc.title))
            } catch (e: Exception) {
                android.util.Log.e("Summary", "PDF export failed", e)
                android.widget.Toast.makeText(context, "Couldn't prepare that PDF. Please try again.", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                vm.exporting.value = false
            }
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
            // "Reached Thrissur" only when arrival was detected; "safely" only
            // when the traveller said so themselves.
            val done = trip?.completedAtMs != null
            Text(
                when {
                    reviewing -> "Review your journey"
                    done && trip?.arrivedAtMs != null -> "Reached ${destLabel ?: trip?.destName}"
                    done -> "Journey complete"
                    else -> "Journey summary"
                },
                color = colors.textHigh, style = MaterialTheme.typography.displaySmall
            )
            Text(
                "${originLabel ?: trip?.originName ?: "Start"} → ${destLabel ?: trip?.destName ?: "Destination"}",
                color = colors.textMid, style = MaterialTheme.typography.bodyLarge
            )
            trip?.completedAtMs?.let {
                Text(
                    buildString {
                        append(TimeFmt.clock(it))
                        append(if (closure?.auto == true) " · closed automatically after arrival" else " · You ended the journey")
                        if (closure?.safeConfirmed == true) append(" · you confirmed arriving safely")
                    },
                    color = colors.accent, style = MaterialTheme.typography.titleSmall
                )
                Text(TimeFmt.date(it), color = colors.textLow, style = MaterialTheme.typography.bodySmall)
            }

            // ---- review before anything is shared ----
            if (reviewing) {
                KoodeCard(accent = colors.accent) {
                    Text(
                        if (closure?.auto == true)
                            "Koode closed this journey after you arrived and didn't respond. Nothing has been shared yet."
                        else "Nothing has been shared yet. The people following this journey hear it ended — with its report — once you approve it.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                }
                KoodeCard(title = "Check these details") {
                    ReviewRow("Destination", destLabel ?: trip?.destName ?: "—", onEdit = { editingDestination = true })
                    val end = trip?.completedAtMs
                    val arrived = trip?.arrivedAtMs
                    ReviewRow(
                        "Ended at", end?.let { TimeFmt.clockWithDay(it, System.currentTimeMillis()) } ?: "—",
                        editLabel = if (arrived != null && end != null && kotlin.math.abs(end - arrived) > 5 * 60_000L) "Use arrival time" else null,
                        onEdit = { arrived?.let { vm.correctEndTime(it) } }
                    )
                    ReviewRow("Travelling by", TransportCatalog.label(trip?.transportMode), onEdit = null)
                    report?.let { ReviewRow("Tolls recorded", it.tollsCrossed.toString(), onEdit = null) }
                    Spacer(Modifier.height(Spacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = safeConfirmed, onCheckedChange = { safeConfirmed = it })
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            "I arrived safely (optional)",
                            color = colors.textHigh, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "Only if you tick this will the people following you read that you arrived safely.",
                        color = colors.textLow, style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(Spacing.md))
                    PrimaryButton(
                        if (approving) "Sharing…" else "Approve & share journey",
                        {
                            vm.approve(safeConfirmed) { ok ->
                                android.widget.Toast.makeText(
                                    context,
                                    if (ok) "Shared with the people following this journey." else "Couldn't approve the journey. Please try again.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                if (ok && sender.whatsAppEnabled) {
                                    scope.launch { if (sender.prepareTimeline()) showSend = true }
                                }
                            }
                        },
                        enabled = !approving && report != null
                    )
                    Text(
                        "Your expenses are never included — they stay with you.",
                        color = colors.textLow, style = MaterialTheme.typography.bodySmall
                    )
                }
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
                // FASTag — only when this journey's vehicle has a tracked
                // balance. The journey toll count and the vehicle balance are
                // separate numbers.
                fastagSummary?.let { balance ->
                    KoodeCard(title = "FASTag") {
                        DetailRow("Tolls crossed this journey", r.tollsCrossed.toString(), leading = "🛣")
                        DetailRow("Balance", balance, leading = "🎫")
                    }
                }
            }

            // ---- private: the traveller's own expenses ----
            if (trip?.completedAtMs != null && expenses.isNotEmpty()) {
                KoodeCard(title = "Your travel expenses · private", accent = colors.traveller) {
                    val total = expenses.sumOf { it.amount }
                    DetailRow("Total", measures.money(total), leading = "₹")
                    Text(
                        "Check them above, then confirm. A PDF is saved on this phone — it is never sent to anyone following you.",
                        color = colors.textMid, style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    if (closure?.expensesApprovedAtMs != null) {
                        Text(
                            "✓ Confirmed ${TimeFmt.clockWithDay(closure!!.expensesApprovedAtMs!!, System.currentTimeMillis())}",
                            color = colors.accent, style = MaterialTheme.typography.titleSmall
                        )
                    } else {
                        SecondaryButton(
                            if (exporting) "Saving…" else "Confirm expenses",
                            { confirmExpensesAndSave() },
                            enabled = !exporting && report != null,
                            accent = colors.traveller, height = 46.dp
                        )
                    }
                }
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

    // ✎ Destination, as it should be recorded.
    if (editingDestination) {
        var text by remember { mutableStateOf(destLabel ?: trip?.destName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingDestination = false },
            title = { Text("Where did this journey end?") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it.take(80) }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { editingDestination = false; vm.correctDestination(text) }, enabled = text.isNotBlank()) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { editingDestination = false }) { Text("Cancel") } }
        )
    }

    // After approval: optionally hand the approved report to WhatsApp yourself.
    if (showSend) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val recipients by sender.sendRecipients.collectAsStateWithLifecycle()
        ModalBottomSheet(onDismissRequest = { showSend = false }, sheetState = sheet) {
            SendTimelineSheet(
                recipients = recipients,
                whatsAppAvailable = sender.whatsAppAvailable,
                onSend = { recipient ->
                    val intent = sender.sendIntentFor(recipient) ?: sender.fallbackSendIntent()
                    if (intent != null) context.startActivity(intent)
                },
                onShareOther = { sender.fallbackSendIntent()?.let { context.startActivity(it) } },
                onDone = { showSend = false }
            )
        }
    }
}

/** One detail to verify: ✓ as recorded, or ✎ to correct it. */
@Composable
private fun ReviewRow(label: String, value: String, editLabel: String? = "Edit", onEdit: (() -> Unit)?) {
    val colors = KoodeTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("✓", color = colors.accent, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.textLow, style = MaterialTheme.typography.labelSmall)
            Text(value, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
        }
        if (onEdit != null && editLabel != null) {
            TextButton(onClick = onEdit) { Text("✎ $editLabel", color = colors.accent) }
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
        StatTile("Total time", TimeFmt.durationShort(report.totalSeconds), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        StatTile("Moving", TimeFmt.durationShort(report.movingSeconds), Modifier.weight(1f))
        StatTile("Stopped", TimeFmt.durationShort(report.stoppedSeconds), Modifier.weight(1f))
    }
    if (!compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            StatTile("Average moving", measures.speed(report.averageMovingSpeedKmh), Modifier.weight(1f))
            StatTile("Stops", report.stops.toString(), Modifier.weight(1f))
        }
    }

    // ---- what the numbers mean ----
    if (report.insights.isNotEmpty()) {
        KoodeCard(title = "Journey insights", accent = colors.traveller) {
            report.insights.forEach {
                Text("• $it", color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    // ---- stops & journey activity ----
    KoodeCard(title = "Stops & journey activity") {
        DetailRow("Stops", report.stops.toString(), leading = "🅿")
        DetailRow("Breaks logged", report.breakCount.toString(), leading = "✅")
        report.averageGapBetweenBreaksSeconds?.let {
            DetailRow("A break about every", TimeFmt.durationShort(it), leading = "⏱")
        }
        DetailRow("Longest stop", TimeFmt.durationShort(report.longestBreakSeconds), leading = "🅿")
        DetailRow(
            "Longest continuous moving stretch",
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
        if (report.tollsCrossed > 0) {
            DetailRow("Tolls crossed", report.tollsCrossed.toString(), leading = "🛣")
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
