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
import com.trippulse.app.data.export.ReportFactory
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
import com.trippulse.app.ui.components.BackButton
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
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    val story by vm.story.collectAsStateWithLifecycle()
    val originLabel by vm.originLabel.collectAsStateWithLifecycle()
    val destLabel by vm.destLabel.collectAsStateWithLifecycle()
    val fastagSummary by vm.fastagSummary.collectAsStateWithLifecycle()
    val closure by vm.closure.collectAsStateWithLifecycle()
    val opportunities by vm.opportunities.collectAsStateWithLifecycle()
    val passCrossings = remember(events) { vm.passCoveredCrossings() }
    val checklist = remember(expenses, opportunities, passCrossings) {
        com.trippulse.app.domain.Expenses.checklist(
            expenses.map { com.trippulse.app.domain.Expenses.Category.fromType(it.type) to it.amount },
            opportunities, passCrossings
        )
    }
    var addingExpense by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<ExpenseEntity?>(null) }
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
                val doc = ReportFactory.expenses(
                    context, t, events, samples, r, expenses, measures,
                    opportunities = opportunities, passCrossings = passCrossings,
                    approvedAtMs = System.currentTimeMillis(),
                    originLabel = originLabel, destLabel = destLabel
                )
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
                    PdfKind.TIMELINE -> ReportFactory.journey(
                        context, t, events, samples, r, measures,
                        originLabel = originLabel, destLabel = destLabel, fastagSummary = fastagSummary
                    )
                    PdfKind.MONEY -> ReportFactory.expenses(
                        context, t, events, samples, r, expenses, measures,
                        opportunities = opportunities, passCrossings = passCrossings,
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
        Spacer(Modifier.height(Spacing.xs))
        AdaptiveContainer {
            BackButton({ nav.popBackStack() })
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
                JourneyDashboard(r, measures, TransportCatalog.isPrivate(trip?.transportMode), story = story)
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

            // ---- private: reconcile and confirm the traveller's own expenses ----
            if (trip?.completedAtMs != null && (expenses.isNotEmpty() || opportunities.isNotEmpty())) {
                KoodeCard(title = "Your travel expenses · private", accent = colors.traveller) {
                    Text(checklist.headline, color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                    Text(measures.money(checklist.recordedTotal), color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
                    checklist.items.forEach { item ->
                        DetailRow(
                            item.category.label,
                            when (item.status) {
                                com.trippulse.app.domain.Expenses.ItemStatus.COVERED_BY_ANNUAL_PASS ->
                                    "${measures.money(0.0)} · ${item.reason}"
                                com.trippulse.app.domain.Expenses.ItemStatus.NEEDS_AMOUNT -> "Needs an amount"
                                com.trippulse.app.domain.Expenses.ItemStatus.UNKNOWN ->
                                    if (item.amount > 0) "${measures.money(item.amount)} + not recorded" else "Not recorded"
                                com.trippulse.app.domain.Expenses.ItemStatus.NO_EXPENSE -> "No expense"
                                com.trippulse.app.domain.Expenses.ItemStatus.RECORDED -> measures.money(item.amount)
                            },
                            leading = item.category.emoji
                        )
                    }
                    Text(
                        checklist.statusLine,
                        color = if (checklist.canApprove) colors.accent else colors.warn,
                        style = MaterialTheme.typography.titleSmall
                    )

                    // What still needs an answer: add the amount, say there was none, or leave it unknown.
                    opportunities.filter { it.open }.forEach { o ->
                        var amountText by remember(o.id) { mutableStateOf("") }
                        Spacer(Modifier.height(Spacing.sm))
                        Text("${o.category.emoji} ${o.label}", color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                        Text(TimeFmt.clockWithDay(o.atMs, System.currentTimeMillis()), color = colors.textLow, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = { amountText = com.trippulse.app.core.InputRules.amountText(it) },
                                placeholder = { Text("Amount") },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { amountText.toDoubleOrNull()?.let { vm.recordExpenseAmount(o.id, it) } },
                                enabled = amountText.toDoubleOrNull() != null
                            ) { Text("Save", color = colors.accent) }
                        }
                        Row {
                            TextButton(onClick = { vm.markNoExpense(o.id) }) { Text("No expense", color = colors.textMid) }
                            TextButton(onClick = { vm.leaveExpenseUnknown(o.id) }) { Text("Leave unknown", color = colors.textMid) }
                        }
                    }

                    // Everything recorded can be corrected or removed.
                    if (expenses.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text("Recorded", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                        expenses.forEach { e ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        e.item.ifBlank { com.trippulse.app.domain.Expenses.Category.fromType(e.type).label },
                                        color = colors.textHigh, style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "${TimeFmt.clock(e.tMs)} · ${measures.money(e.amount)}",
                                        color = colors.textLow, style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (closure?.expensesApprovedAtMs == null) {
                                    TextButton(onClick = { editingExpense = e }) { Text("Edit", color = colors.accent) }
                                    TextButton(onClick = { vm.deleteExpense(e.id) }) { Text("Delete", color = colors.textLow) }
                                }
                            }
                        }
                    }
                    Text(
                        "Private to you — never part of the journey report and never sent to anyone following you.",
                        color = colors.textLow, style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    if (closure?.expensesApprovedAtMs != null) {
                        Text(
                            "✓ Confirmed ${TimeFmt.clockWithDay(closure!!.expensesApprovedAtMs!!, System.currentTimeMillis())} · PDF saved on this phone",
                            color = colors.accent, style = MaterialTheme.typography.titleSmall
                        )
                    } else {
                        SecondaryButton("Add expense", { addingExpense = true }, leading = "＋", height = 44.dp)
                        Spacer(Modifier.height(Spacing.sm))
                        PrimaryButton(
                            if (exporting) "Saving…" else "Confirm expenses",
                            { confirmExpensesAndSave() },
                            enabled = !exporting && report != null && checklist.canApprove
                        )
                        if (!checklist.canApprove) {
                            Text(
                                "${checklist.needsAttention} expense item${if (checklist.needsAttention == 1) "" else "s"} need${if (checklist.needsAttention == 1) "s" else ""} your attention first.",
                                color = colors.warn, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else if (trip?.completedAtMs != null && closure?.expensesApprovedAtMs == null) {
                SecondaryButton("Add a travel expense (private)", { addingExpense = true }, leading = "₹", height = 44.dp)
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

    // ✎ Correct a recorded amount.
    editingExpense?.let { e ->
        var text by remember(e.id) { mutableStateOf(e.amount.toString().removeSuffix(".0")) }
        AlertDialog(
            onDismissRequest = { editingExpense = null },
            title = { Text(e.item.ifBlank { "Expense" }) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = com.trippulse.app.core.InputRules.amountText(it) }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { text.toDoubleOrNull()?.let { vm.correctExpense(e.id, it) }; editingExpense = null },
                    enabled = text.toDoubleOrNull() != null) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingExpense = null }) { Text("Cancel") } }
        )
    }

    // ＋ An expense Koode didn't notice.
    if (addingExpense) {
        var category by remember { mutableStateOf(com.trippulse.app.domain.Expenses.Category.OTHER) }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingExpense = false },
            title = { Text("Add an expense") },
            text = {
                Column {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        com.trippulse.app.domain.Expenses.Category.entries.forEach { c ->
                            com.trippulse.app.ui.components.KoodeChip(c.label, category == c, { category = c }, leading = c.emoji)
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = text, onValueChange = { text = com.trippulse.app.core.InputRules.amountText(it) },
                        placeholder = { Text("Amount") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { text.toDoubleOrNull()?.let { vm.addExpense(category, it) }; addingExpense = false },
                    enabled = text.toDoubleOrNull() != null) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addingExpense = false }) { Text("Cancel") } }
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
    compact: Boolean = false,
    /** The journey as a story, with its charts: shown when the screen has it. */
    story: com.trippulse.app.domain.report.JourneyStory.Story? = null
) {
    val colors = KoodeTheme.colors

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        StatTile("Distance", measures.distance(report.distanceM), Modifier.weight(1f), colors.accent)
        StatTile("Total time", TimeFmt.durationShort(report.totalSeconds), Modifier.weight(1f))
    }
    // The story's figures where it exists: they count a silence the car moved
    // through as driving and a halt as stopped, which the event-based numbers do not.
    val movingS = story?.movingSeconds ?: report.movingSeconds
    val stoppedS = story?.stoppedSeconds ?: report.stoppedSeconds
    val stopCount = story?.stops?.size ?: report.stops
    val avgKmh = if (story != null && movingS >= 600) report.distanceM / 1000.0 / (movingS / 3600.0) else report.averageMovingSpeedKmh
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        StatTile("Moving", TimeFmt.durationShort(movingS), Modifier.weight(1f))
        StatTile("Stopped", TimeFmt.durationShort(stoppedS), Modifier.weight(1f))
    }
    if (!compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            StatTile("Average moving", measures.speed(avgKmh), Modifier.weight(1f))
            StatTile("Stops", stopCount.toString(), Modifier.weight(1f))
        }
    }

    // ---- the story, in words and pictures ----
    if (story != null && story.paragraphs.isNotEmpty()) {
        KoodeCard(title = "The story", accent = colors.traveller) {
            (if (compact) story.paragraphs.take(2) else story.paragraphs).forEach {
                Text(it, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Spacing.sm))
            }
            if (story.highlights.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                com.trippulse.app.ui.components.HighlightChips(story.highlights)
            }
        }
        if (story.segments.isNotEmpty()) {
            KoodeCard(title = if (story.days.size > 1) "Day by day" else "The day") {
                val zone = java.time.ZoneId.systemDefault()
                val end = story.segments.last().toMs
                story.days.forEach { day ->
                    val from = maxOf(day.dateMs, story.segments.first().fromMs)
                    val to = minOf(day.dateMs + 24 * 3_600_000L, end)
                    if (to - from > 30 * 60_000L) {
                        if (story.days.size > 1) Text(day.title, color = colors.textMid, style = MaterialTheme.typography.titleSmall)
                        com.trippulse.app.ui.components.DayStripChart(story.segments, from, to, zone,
                            nowMs = if (story.status != "Completed") System.currentTimeMillis() else null)
                        Spacer(Modifier.height(Spacing.md))
                    }
                }
                if (!compact && story.kmByHour.size >= 3) {
                    Text("Distance by hour (${measures.distanceUnit})".uppercase(), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(Spacing.xs))
                    com.trippulse.app.ui.components.BarsChart(
                        story.kmByHour.map { h -> com.trippulse.app.ui.components.ChartBar(
                            com.trippulse.app.data.export.report.Reports.hourLabel(h.hourStartMs, zone),
                            measures.distanceValue(h.metres + h.estimatedM), measures.distanceValue(h.estimatedM)) },
                        valueText = { v -> if (v >= 10) v.toInt().toString() else "%.1f".format(v) }
                    )
                    if (story.kmByHour.any { it.estimatedM > 0 }) {
                        Text("Fainter bars are hours the phone was out of contact: the distance is estimated.", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    } else if (report.insights.isNotEmpty()) {
        KoodeCard(title = "Journey insights", accent = colors.traveller) {
            report.insights.forEach {
                Text("• $it", color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    // ---- stops & journey activity ----
    KoodeCard(title = "Stops & journey activity") {
        DetailRow("Stops", stopCount.toString(), leading = "🅿")
        DetailRow("Breaks logged", report.breakCount.toString(), leading = "✅")
        val properBreaks = story?.stops?.count { it.items.isNotEmpty() } ?: 0
        val breakEvery = if (story != null) (if (properBreaks >= 2 && movingS > 0) movingS / properBreaks else null) else report.averageGapBetweenBreaksSeconds
        breakEvery?.let {
            DetailRow("A break about every", TimeFmt.durationShort(it), leading = "⏱")
        }
        DetailRow("Longest stop", TimeFmt.durationShort(story?.longestStop?.seconds ?: report.longestBreakSeconds), leading = "🅿")
        DetailRow(
            "Longest continuous moving stretch",
            TimeFmt.durationShort(story?.longestDrive?.movingSeconds ?: report.longestLegSeconds), leading = "🛣"
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
            if (story?.tollsMayBeMissing == true) {
                Text(com.trippulse.app.domain.report.Prose.TOLL_NOTE, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
            }
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
            if (!compact && report.costLines.size >= 2) {
                com.trippulse.app.ui.components.DonutChart(
                    report.costLines.map { line ->
                        Triple(line.label, line.amount, com.trippulse.app.ui.components.KoodeArt.file(
                            com.trippulse.app.data.export.report.Reports.categoryIcon(com.trippulse.app.domain.Expenses.Category.fromType(line.type)).picture))
                    },
                    centreValue = measures.money(report.totalCost), centreLabel = "total", valueText = { measures.money(it) }
                )
                Spacer(Modifier.height(Spacing.md))
            }
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
