package com.trippulse.app.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.data.TripManager
import com.trippulse.app.domain.EtaMode
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.JourneyStatus
import com.trippulse.app.ui.DriverVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.theme.Amber
import com.trippulse.app.ui.theme.Danger
import com.trippulse.app.ui.theme.Surface2
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextHigh
import com.trippulse.app.ui.theme.TextMid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverScreen(nav: NavHostController, tripId: String) {
    val vm: DriverVm = viewModel(factory = DriverVm.factory(tripId))
    val trip by vm.trip.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()

    val s = state
    val t = trip
    val now = System.currentTimeMillis()
    val moving = s?.journey == JourneyStatus.DRIVING.name

    var showNotes by remember { mutableStateOf(false) }
    var showCheckpoint by remember { mutableStateOf(false) }
    var showEtaBreakdown by remember { mutableStateOf(false) }
    var showEndConfirm by remember { mutableStateOf(false) }
    var showExpense by remember { mutableStateOf(false) }

    // state-driven prompts
    val checkpointDue = s?.checkpointDue == true
    val overnightDue = s?.longStopPromptDue == true

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // header
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t?.destName ?: "Trip", color = TextHigh, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(journeyLabel(s?.journey), color = Teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(connectivityLabel(s?.connectivity), color = if (s?.connectivity == "OFFLINE") Amber else Teal, fontSize = 12.sp)
                val bat = s?.batteryPct
                if (bat != null) Text("🔋 $bat%", color = if (bat <= 15) Amber else TextMid, fontSize = 12.sp)
                if (pending > 0) Text("$pending queued", color = TextMid, fontSize = 11.sp)
            }
        }

        // map
        MapPanel(
            current = s?.lat?.let { la -> s.lng?.let { lo -> GeoPoint(la, lo) } },
            origin = t?.let { GeoPoint(it.originLat, it.originLng) },
            destination = t?.let { GeoPoint(it.destLat, it.destLng) },
            route = emptyList(),
            heightDp = 220
        )

        // ETA + progress
        SectionCard {
            val mode = s?.etaMode
            if (mode == EtaMode.OVERNIGHT_PENDING.name) {
                Text("ETA paused — overnight rest", color = Amber, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("A new estimate will appear when the trip resumes.", color = TextMid, fontSize = 12.sp)
            } else if (mode == EtaMode.ARRIVED.name) {
                Text("Arrived", color = Teal, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            } else {
                Text("Estimated arrival", color = TextMid, fontSize = 12.sp)
                Text(etaRangeText(s?.etaLikelyMs, s?.etaLowMs, s?.etaHighMs), color = TextHigh, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                if (s?.etaBreakdownJson != null) {
                    TextButton(onClick = { showEtaBreakdown = true }) { Text("Why this estimate?", color = Teal, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (s?.progressPct ?: 0.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Teal, trackColor = Surface2
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${TimeFmt.km(s?.distanceCoveredM ?: 0.0)} done", color = TextMid, fontSize = 12.sp)
                Text("${TimeFmt.km(s?.distanceRemainingM ?: 0.0)} left", color = TextMid, fontSize = 12.sp)
            }
        }

        // SOS active banner
        if (s?.sosActive == true) {
            SectionCard {
                Text("🚨 SOS is active", color = Danger, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Viewers have been alerted. Resolve when you're safe.", color = TextMid, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.resolveSos() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal)
                ) { Text("I'm safe — resolve SOS") }
            }
        }

        // viewers asking to follow (trip-id-only requests) — approve by name
        val requests by vm.joinRequests.collectAsStateWithLifecycle()
        val pendingReqs = requests.filter { it["status"] == "PENDING" }
        if (pendingReqs.isNotEmpty()) {
            SectionCard {
                Text("Who wants to follow you", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                pendingReqs.forEach { r ->
                    val name = r["name"] as? String ?: "Unknown"
                    val token = r["token"] as? String ?: return@forEach
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = TextHigh, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.setViewerApproval(token, true) }) { Text("Approve", color = Teal, fontSize = 13.sp) }
                        TextButton(onClick = { vm.setViewerApproval(token, false) }) { Text("Deny", color = Danger, fontSize = 13.sp) }
                    }
                }
            }
        }
        val approvedNames = requests.filter { it["status"] == "APPROVED" }.mapNotNull { it["name"] as? String }
        if (approvedNames.isNotEmpty()) {
            Text("Watching: ${approvedNames.joinToString(", ")}", color = TextMid, fontSize = 12.sp)
        }

        // quick actions
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showNotes = true }, modifier = Modifier.weight(1f)) { Text("Add note") }
            OutlinedButton(onClick = { showCheckpoint = true }, modifier = Modifier.weight(1f)) { Text("Log a break") }
        }
        OutlinedButton(onClick = { showExpense = true }, modifier = Modifier.fillMaxWidth()) { Text("₹ Add expense (fuel / food / stay)") }

        // pause / resume / change dest / end
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s?.journey == JourneyStatus.PAUSED.name) {
                OutlinedButton(onClick = { vm.resume() }, modifier = Modifier.weight(1f)) { Text("Resume") }
            } else {
                OutlinedButton(onClick = { vm.pause() }, modifier = Modifier.weight(1f)) { Text("Pause") }
            }
            OutlinedButton(onClick = { showEndConfirm = true }, modifier = Modifier.weight(1f)) { Text("End trip") }
        }

        // SOS hold button
        SosHoldButton(onTriggered = { vm.activateSos() }, enabled = s?.sosActive != true)

        Spacer(Modifier.height(8.dp))
        Text("Timeline", color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        val events by vm.events.collectAsStateWithLifecycle()
        val items = remember(events) {
            events.filter { it.type in com.trippulse.app.domain.EventTypes.TIMELINE_TYPES }
                .sortedByDescending { it.eventTimeMs }
                .take(40)
                .map { e ->
                    val (emoji, label) = eventLabel(e.type)
                    TimelineItem(e.eventTimeMs, emoji, label, null)
                }
        }
        SectionCard { TimelineList(items, now) }
        Spacer(Modifier.height(24.dp))
    }

    // ---- ETA breakdown dialog ----
    if (showEtaBreakdown && s?.etaBreakdownJson != null) {
        val map = remember(s.etaBreakdownJson) { com.trippulse.app.data.EventCodec.payloadFromJson(s.etaBreakdownJson!!) }
        AlertDialog(
            onDismissRequest = { showEtaBreakdown = false },
            confirmButton = { TextButton(onClick = { showEtaBreakdown = false }) { Text("Got it") } },
            title = { Text("How the estimate is built") },
            text = {
                Column {
                    val travel = (map["travelSeconds"] as? Number)?.toLong() ?: 0
                    val breaks = (map["breakBudgetSeconds"] as? Number)?.toLong() ?: 0
                    val uncertainty = (map["uncertaintySeconds"] as? Number)?.toLong() ?: 0
                    Text("Driving time: ${TimeFmt.durationShort(travel)}", color = TextHigh, fontSize = 14.sp)
                    Text("Planned breaks: ${TimeFmt.durationShort(breaks)}", color = TextHigh, fontSize = 14.sp)
                    Text("Buffer: ±${TimeFmt.durationShort(uncertainty)}", color = TextMid, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("The arrival window widens with distance and narrows as you get close.", color = TextMid, fontSize = 12.sp)
                }
            }
        )
    }

    // ---- end confirm ----
    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    showEndConfirm = false
                    vm.complete()
                    nav.navigate(Routes.summary(tripId)) { popUpTo(Routes.HOME) }
                }) { Text("End trip", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { showEndConfirm = false }) { Text("Keep going") } },
            title = { Text("End this trip?") },
            text = { Text("This completes the trip and stops tracking. Viewers will see the final summary.") }
        )
    }

    // ---- notes sheet ----
    if (showNotes) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showNotes = false }, sheetState = sheet) {
            QuickNoteSheet(moving = moving, onEvent = { type, text ->
                vm.addNote(type, text); showNotes = false
            })
        }
    }

    // ---- checkpoint sheet (manual or due) ----
    if (showCheckpoint || checkpointDue) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showCheckpoint = false; if (checkpointDue) vm.skipCheckpoint() },
            sheetState = sheet
        ) {
            CheckpointSheet(
                onSubmit = { c -> vm.submitCheckpoint(c); showCheckpoint = false },
                onSkip = { vm.skipCheckpoint(); showCheckpoint = false }
            )
        }
    }

    // ---- overnight dialog ----
    if (overnightDue) {
        OvernightDialog(
            onChoice = { type -> vm.answerOvernight(type) }
        )
    }

    // ---- expense sheet (owner-only, stored on this phone) ----
    if (showExpense) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showExpense = false }, sheetState = sheet) {
            ExpenseSheet(onSave = { type, amount, qty, unit, note ->
                vm.addExpense(type, amount, qty, unit, note)
                showExpense = false
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ExpenseSheet(onSave: (String, Double, Double?, String?, String?) -> Unit) {
    var type by remember { mutableStateOf("FUEL") }
    var amount by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("L") }
    var note by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Add a journey expense", color = TextHigh, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Private to you — stays on this phone, viewers never see it.", color = TextMid, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Toggle("⛽ Fuel", type == "FUEL") { type = "FUEL" }
            Toggle("🍛 Food", type == "FOOD") { type = "FOOD" }
            Toggle("🏨 Stay", type == "STAY") { type = "STAY" }
            Toggle("🧾 Other", type == "OTHER") { type = "OTHER" }
        }
        OutlinedTextField(
            value = amount, onValueChange = { amount = it },
            label = { Text("Amount spent") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        if (type == "FUEL") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = qty, onValueChange = { qty = it },
                    label = { Text("Quantity refilled") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                Toggle("Litres", unit == "L") { unit = "L" }
                Toggle("kWh", unit == "kWh") { unit = "kWh" }
            }
            Text("Petrol/diesel in litres, EV charge in kWh — used for the trip's fuel-efficiency summary.", color = TextMid, fontSize = 11.sp)
        }
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val amt = amount.toDoubleOrNull() ?: return@Button
                onSave(type, amt, qty.toDoubleOrNull(), if (type == "FUEL") unit else null, note)
            },
            enabled = amount.toDoubleOrNull() != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) { Text("Save expense") }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SosHoldButton(onTriggered: () -> Unit, enabled: Boolean) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var progress by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        progress = 0f
                        val job = scope.launch {
                            val holdMs = 1500L
                            val step = 50L
                            var elapsed = 0L
                            while (elapsed < holdMs) {
                                kotlinx.coroutines.delay(step)
                                elapsed += step
                                progress = elapsed.toFloat() / holdMs
                            }
                            onTriggered()
                            progress = 0f
                        }
                        val released = tryAwaitRelease()
                        if (progress < 1f || !released) {
                            job.cancel()
                            progress = 0f
                        }
                    }
                )
            }
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)),
            color = Danger.copy(alpha = 0.35f), trackColor = Danger.copy(alpha = 0.15f)
        )
        Text(
            if (enabled) "Hold for SOS" else "SOS active",
            color = Danger, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QuickNoteSheet(moving: Boolean, onEvent: (String, String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    val types = com.trippulse.app.domain.EventTypes
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Quick note", color = TextHigh, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChoice("👤 Passenger joined") { onEvent(types.PASSENGER_JOINED, null) }
            AssistChoice("👋 Passenger left") { onEvent(types.PASSENGER_LEFT, null) }
            AssistChoice("💊 Medicine") { onEvent(types.MEDICINE, null) }
            AssistChoice("🔧 Vehicle issue") { onEvent(types.VEHICLE_ISSUE, null) }
        }
        if (!moving) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Custom note") }, modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onEvent(types.QUICK_NOTE, text.ifBlank { null }) },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) { Text("Add note") }
        } else {
            Text("Free-text notes are disabled while moving for safety. Tap a quick option above.", color = TextMid, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AssistChoice(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(label, fontSize = 13.sp) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CheckpointSheet(onSubmit: (TripManager.Checkpoint) -> Unit, onSkip: () -> Unit) {
    var water by remember { mutableStateOf(false) }
    var food by remember { mutableStateOf(false) }
    var toilet by remember { mutableStateOf(false) }
    var rest by remember { mutableStateOf(false) }
    var fuel by remember { mutableStateOf(false) }
    var charge by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("What happened on this stop?", color = TextHigh, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Tap all that apply. This keeps your family's mind at ease.", color = TextMid, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Toggle("💧 Water", water) { water = it }
            Toggle("🍛 Food", food) { food = it }
            Toggle("🚻 Toilet", toilet) { toilet = it }
            Toggle("😴 Rest", rest) { rest = it }
            Toggle("⛽ Fuel", fuel) { fuel = it }
            Toggle("🔌 Charge", charge) { charge = it }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onSubmit(TripManager.Checkpoint(water, food, toilet, rest, fuel, charge)) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) { Text("Save break") }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip", color = TextMid) }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Toggle(label: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onCheck(!checked) },
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Teal, selectedLabelColor = androidx.compose.ui.graphics.Color.Black)
    )
}

@Composable
private fun OvernightDialog(onChoice: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        confirmButton = {},
        title = { Text("Stopping for the night?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You've been stopped a while. Let your family know what's happening.", color = TextMid, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Button(onClick = { onChoice("HOTEL") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Surface2)) { Text("🏨 Hotel / lodge") }
                Button(onClick = { onChoice("FAMILY") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Surface2)) { Text("🏠 Staying with family") }
                Button(onClick = { onChoice("VEHICLE") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Surface2)) { Text("🚗 Resting in the vehicle") }
                Button(onClick = { onChoice("CONTINUING") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text("Still driving — continue") }
            }
        }
    )
}

// ---- small text helpers ----

private fun journeyLabel(journey: String?): String = when (journey) {
    JourneyStatus.READY.name -> "Ready"
    JourneyStatus.DRIVING.name -> "On the move"
    JourneyStatus.POSSIBLE_STOP.name -> "Slowing down"
    JourneyStatus.STOPPED.name -> "Stopped"
    JourneyStatus.LONG_STOP.name -> "Long stop"
    JourneyStatus.OVERNIGHT.name -> "Overnight rest"
    JourneyStatus.PAUSED.name -> "Paused"
    JourneyStatus.ARRIVED.name -> "Arrived"
    JourneyStatus.COMPLETED.name -> "Completed"
    else -> "—"
}

private fun connectivityLabel(c: String?): String = when (c) {
    "OFFLINE" -> "Offline — will sync"
    "ONLINE" -> "Live"
    else -> "—"
}

private fun etaRangeText(likely: Long?, low: Long?, high: Long?): String {
    if (likely == null) return "Calculating…"
    val l = low ?: likely
    val h = high ?: likely
    return "${TimeFmt.clockWithDay(l, System.currentTimeMillis())} – ${TimeFmt.clock(h)}"
}
