package com.trippulse.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import android.content.Intent
import com.trippulse.app.core.InputRules
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.data.EventCodec
import com.trippulse.app.data.TripManager
import com.trippulse.app.data.local.TripLegEntity
import com.trippulse.app.data.share.TimelineDelivery
import com.trippulse.app.domain.LegDetails
import com.trippulse.app.domain.TravelDetails
import com.trippulse.app.domain.JourneyAnalytics
import com.trippulse.app.domain.EtaMode
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.JourneyStatus
import com.trippulse.app.domain.Nourishment
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TransportProfile
import com.trippulse.app.ui.DriverVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.components.TravelDetailFields
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.KoodeChip
import com.trippulse.app.ui.components.KoodeHeroCard
import com.trippulse.app.ui.components.LocalWindowClass
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.PulsingDot
import com.trippulse.app.ui.components.SecondaryButton
import com.trippulse.app.ui.components.SectionHeader
import com.trippulse.app.ui.components.StatusPill
import com.trippulse.app.ui.map.JourneyMap
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The traveller's own screen while a journey is running.
 *
 * Everything offered here comes from the current leg's [TransportProfile]: a
 * driver gets break logging and refuelling, a train passenger gets "Boarded",
 * "Train halted", "Deboarded" and simple wellbeing taps. Nothing on this
 * screen asks a question that doesn't apply to the vehicle you're in.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DriverScreen(nav: NavHostController, tripId: String) {
    val vm: DriverVm = viewModel(factory = DriverVm.factory(tripId))
    val colors = KoodeTheme.colors
    val windowClass = LocalWindowClass.current

    val trip by vm.trip.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val legs by vm.legs.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val breadcrumb by vm.breadcrumb.collectAsStateWithLifecycle()
    val requests by vm.joinRequests.collectAsStateWithLifecycle()

    val s = state
    val t = trip
    val now = System.currentTimeMillis()
    val moving = s?.journey == JourneyStatus.DRIVING.name

    val activeLeg = legs.firstOrNull { it.legIndex == (t?.activeLegIndex ?: 0) }
    val profile = TransportCatalog.profile(activeLeg?.mode ?: t?.transportMode)
    val hasNextLeg = legs.any { it.legIndex == (t?.activeLegIndex ?: 0) + 1 }

    var showNotes by remember { mutableStateOf(false) }
    var showCheckpoint by remember { mutableStateOf(false) }
    var showEtaBreakdown by remember { mutableStateOf(false) }
    var showEndReview by remember { mutableStateOf(false) }
    var showExpense by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<JourneyAnalytics.JourneyReport?>(null) }
    val measures = vm.measures
    val context = LocalContext.current

    // The review needs current numbers, not the ones from when the screen
    // opened — the traveller is about to publish them.
    LaunchedEffect(showEndReview) {
        if (showEndReview) report = vm.buildReport()
    }

    val checkpointDue = s?.checkpointDue == true
    val overnightDue = s?.longStopPromptDue == true
    val arrivalDue = s?.arrivalPromptDue == true

    /**
     * When the phone last came back from a silence, if it was recent.
     *
     * Read from the return event rather than from the trip's dark marker,
     * because the marker is cleared the moment the phone reports again -- so
     * by the time there is a screen to show it on, it is already gone. The
     * event is the durable record, and it is the one worth showing.
     */
    val cameBack = remember(events) {
        events.filter { it.type == EventTypes.DEVICE_BACK_ONLINE }
            .maxByOrNull { it.eventTimeMs }
            ?.takeIf { System.currentTimeMillis() - it.eventTimeMs < RECENT_RETURN_MS }
    }
    val darkGapMs = cameBack?.let {
        (EventCodec.payloadFromJson(it.payloadJson)["gapMs"] as? Number)?.toLong()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(Spacing.md))
        AdaptiveContainer {
            // ---- header ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.emoji, fontSize = 16.sp)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            activeLeg?.toName ?: t?.destName ?: "Journey",
                            color = colors.textHigh, style = MaterialTheme.typography.headlineMedium
                        )
                    }
                    Text(
                        journeyLabel(s?.journey),
                        color = colors.accent, style = MaterialTheme.typography.titleSmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    StatusPill(
                        if (s?.connectivity == "OFFLINE") "SAVING LOCALLY" else "LIVE",
                        if (s?.connectivity == "OFFLINE") colors.warn else colors.accent,
                        pulsing = s?.connectivity != "OFFLINE"
                    )
                    s?.batteryPct?.let {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "🔋 $it%",
                            color = if (it <= 15) colors.warn else colors.textLow,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (pending > 0) {
                        Text(
                            "$pending waiting to sync",
                            color = colors.textLow, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---- arrival: the app asks, the traveller decides ----
            AnimatedBanner(visible = arrivalDue) {
                KoodeHeroCard(accent = colors.accent) {
                    Text("Looks like you've arrived", color = colors.accent, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "Your journey stays live until you end it — nobody watching will see it close until you do.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Box(Modifier.weight(1f)) {
                            PrimaryButton("Review and end", { showEndReview = true }, height = 46.dp)
                        }
                        Box(Modifier.weight(1f)) {
                            SecondaryButton(
                                if (hasNextLeg) "Next stage" else "Not yet",
                                { if (hasNextLeg) vm.nextLeg() else vm.dismissArrivalPrompt() },
                                accent = colors.textMid, height = 46.dp
                            )
                        }
                    }
                }
            }

            // ---- SOS ----
            AnimatedBanner(visible = s?.sosActive == true) {
                KoodeHeroCard(accent = colors.danger) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(colors.danger, size = 9.dp)
                        Text("SOS is active", color = colors.danger, style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        "Everyone following you has been alerted. Resolve it when you're safe.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.md))
                    PrimaryButton("I'm safe — resolve SOS", { vm.resolveSos() }, height = 46.dp)
                }
            }

            // ---- the phone was off, and is back ----
            //
            // Shown to the traveller because they may have no idea it
            // happened -- a battery that died overnight, a restart they slept
            // through -- and the people following them certainly noticed. Being
            // told what their circle was told is the difference between the app
            // reporting on them and the app working with them.
            AnimatedBanner(visible = cameBack != null) {
                KoodeCard(accent = colors.warn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔌", fontSize = 16.sp)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            "Your phone stopped reporting",
                            color = colors.textHigh,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        buildString {
                            append("Your phone went quiet")
                            darkGapMs?.let { append(" for ${TimeFmt.durationShort(it / 1000)}") }
                            append(" and is reporting again now. ")
                            append("Everyone following you was told, and has been told you're back.")
                        },
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Your last known position was saved the whole time.",
                        color = colors.textLow, style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---- map with inline playback ----
            JourneyMap(
                current = s?.lat?.let { la -> s.lng?.let { lo -> GeoPoint(la, lo) } },
                origin = activeLeg?.let { GeoPoint(it.fromLat, it.fromLng) }
                    ?: t?.let { GeoPoint(it.originLat, it.originLng) },
                destination = activeLeg?.let { GeoPoint(it.toLat, it.toLng) }
                    ?: t?.let { GeoPoint(it.destLat, it.destLng) },
                breadcrumb = remember(breadcrumb) { breadcrumb.map { GeoPoint(it.lat, it.lng) } },
                breadcrumbTimesMs = remember(breadcrumb) { breadcrumb.map { it.tMs } },
                bearingDeg = s?.bearing?.toFloat(),
                live = s?.connectivity != "OFFLINE",
                height = windowClass.mapHeight
            )

            // ---- stages, when there is more than one ----
            if (legs.size > 1) {
                KoodeCard(title = "Stages") {
                    legs.forEach { leg ->
                        val legProfile = TransportCatalog.profile(leg.mode)
                        val isActive = leg.legIndex == (t?.activeLegIndex ?: 0)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(legProfile.emoji, fontSize = 15.sp)
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                "${leg.fromName} → ${leg.toName}",
                                color = if (isActive) colors.textHigh else colors.textMid,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            when {
                                leg.completedAtMs != null -> StatusPill("Done", colors.textLow)
                                isActive -> StatusPill("Now", colors.accent, pulsing = true)
                                else -> StatusPill("Next", colors.textLow)
                            }
                        }
                    }
                    if (hasNextLeg) {
                        Spacer(Modifier.height(Spacing.md))
                        SecondaryButton(
                            "I've changed vehicle — start next stage",
                            { vm.nextLeg() },
                            accent = colors.traveller, height = 44.dp
                        )
                    }
                }
            }

            // ---- ETA + progress ----
            KoodeCard {
                when (s?.etaMode) {
                    EtaMode.OVERNIGHT_PENDING.name -> {
                        Text("Resting overnight", color = colors.warn, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A new estimate appears when you're on the move again.",
                            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    EtaMode.ARRIVED.name ->
                        Text("Arrived", color = colors.accent, style = MaterialTheme.typography.headlineSmall)
                    else -> {
                        Text("ESTIMATED ARRIVAL", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            etaRangeText(s?.etaLikelyMs, s?.etaLowMs, s?.etaHighMs),
                            color = colors.textHigh, style = MaterialTheme.typography.headlineMedium
                        )
                        if (s?.etaBreakdownJson != null) {
                            TextButton(onClick = { showEtaBreakdown = true }) {
                                Text("Why this estimate?", color = colors.accent, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                LinearProgressIndicator(
                    progress = { (s?.progressPct ?: 0.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(Radii.pill)),
                    color = colors.accent, trackColor = colors.surfaceRaised
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    // Distances are rendered in the traveller's own units,
                    // worked out from where they actually are.
                    Text(
                        "${measures.distance(s?.distanceCoveredM ?: 0.0)} done",
                        color = colors.textMid, style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${measures.distance(s?.distanceRemainingM ?: 0.0)} to go",
                        color = colors.textMid, style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---- who wants to follow ----
            val pendingReqs = requests.filter { it["status"] == "PENDING" }
            AnimatedBanner(visible = pendingReqs.isNotEmpty()) {
                KoodeCard(accent = colors.warn, title = "Who wants to follow you") {
                    pendingReqs.forEach { r ->
                        val name = r["name"] as? String ?: "Unknown"
                        val token = r["token"] as? String ?: return@forEach
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name, color = colors.textHigh,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vm.setViewerApproval(token, true) }) {
                                Text("Let them in", color = colors.accent, fontSize = 13.sp)
                            }
                            TextButton(onClick = { vm.setViewerApproval(token, false) }) {
                                Text("No", color = colors.danger, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            val approvedNames = requests.filter { it["status"] == "APPROVED" }.mapNotNull { it["name"] as? String }
            if (approvedNames.isNotEmpty()) {
                Text(
                    "Watching: ${approvedNames.joinToString(", ")}",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }

            // ---- one-tap wellbeing ----
            // On public transport these are simply notes: eating on a train
            // is not a break, and logging it must not imply the journey stopped.
            KoodeCard(title = if (profile.wellbeingIsBreak) "Log a break" else "How are you doing?") {
                Text(
                    if (profile.wellbeingIsBreak)
                        "One tap each. Koode works out whether that was breakfast, lunch or dinner."
                    else "One tap each. On ${profile.label.lowercase()} these are just notes for your family — " +
                        "they never count as stopping the journey.",
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.md))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    KoodeChip("Water", false, { vm.logNourishment(Nourishment.WATER) }, leading = "💧")
                    KoodeChip("Tea / coffee", false, { vm.logNourishment(Nourishment.TEA_COFFEE) }, leading = "☕")
                    KoodeChip("Snack", false, { vm.logNourishment(Nourishment.SNACK) }, leading = "🍪")
                    KoodeChip("Food", false, { vm.submitCheckpoint(TripManager.Checkpoint(food = true)) }, leading = "🍛")
                }
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.weight(1f)) {
                        SecondaryButton(
                            if (profile.wellbeingIsBreak) "Full break log" else "More",
                            { showCheckpoint = true }, height = 44.dp
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SecondaryButton("Add a note", { showNotes = true }, height = 44.dp)
                    }
                }
            }

            // ---- mode-specific milestones ----
            if (profile.quickActions.isNotEmpty() && !profile.isPrivateVehicle) {
                KoodeCard(title = "${profile.label} updates") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        profile.quickActions.forEach { action ->
                            KoodeChip(
                                action.label, false,
                                { vm.addNote(action.eventType, action.timelineText) },
                                leading = action.emoji
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.weight(1f)) {
                    SecondaryButton(
                        "Add an expense", { showExpense = true },
                        leading = "₹", accent = colors.traveller, height = 46.dp
                    )
                }
                Box(Modifier.weight(1f)) {
                    // Plans change mid-journey more often than at the start.
                    SecondaryButton(
                        "Edit journey", { showEdit = true },
                        leading = "✏️", height = 46.dp
                    )
                }
            }

            // Sharing with someone new is a mid-journey thought ("send it to my
            // sister too"), so it lives one tap away rather than back on the
            // screen that was shown once when the journey started.
            SecondaryButton(
                "Share this journey with someone",
                {
                    val text = shareInvitation(t?.tripId, t?.secret)
                    if (text != null) {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                },
                                "Share journey"
                            )
                        )
                    }
                },
                leading = "📤", accent = colors.accent
            )

            // ---- journey controls ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.weight(1f)) {
                    if (s?.journey == JourneyStatus.PAUSED.name) {
                        SecondaryButton("Resume", { vm.resume() }, leading = "▶", height = 46.dp)
                    } else {
                        SecondaryButton("Pause", { vm.pause() }, leading = "⏸", height = 46.dp)
                    }
                }
                Box(Modifier.weight(1f)) {
                    SecondaryButton(
                        "End journey", { showEndReview = true },
                        accent = colors.warn, height = 46.dp
                    )
                }
            }

            SosHoldButton(onTriggered = { vm.activateSos() }, enabled = s?.sosActive != true)

            // ---- timeline ----
            SectionHeader("Timeline")
            KoodeCard {
                val items = remember(events) {
                    timelineItems(
                        events.map { e ->
                            e.type to (e.eventTimeMs to com.trippulse.app.data.EventCodec.payloadFromJson(e.payloadJson))
                        }
                    )
                }
                TimelineList(items, now)
            }
            Spacer(Modifier.height(Spacing.scrollBottom))
        }
    }

    // ---- ETA breakdown ----
    if (showEtaBreakdown && s?.etaBreakdownJson != null) {
        val map = remember(s.etaBreakdownJson) {
            com.trippulse.app.data.EventCodec.payloadFromJson(s.etaBreakdownJson!!)
        }
        AlertDialog(
            onDismissRequest = { showEtaBreakdown = false },
            confirmButton = { TextButton(onClick = { showEtaBreakdown = false }) { Text("Got it") } },
            title = { Text("How the estimate is built") },
            text = {
                Column {
                    val travel = (map["travelSeconds"] as? Number)?.toLong() ?: 0
                    val breaks = (map["breakBudgetSeconds"] as? Number)?.toLong() ?: 0
                    val uncertainty = (map["uncertaintySeconds"] as? Number)?.toLong() ?: 0
                    Text("Travel time: ${TimeFmt.durationShort(travel)}", color = colors.textHigh)
                    Text("Expected breaks: ${TimeFmt.durationShort(breaks)}", color = colors.textHigh)
                    Text("Buffer: ±${TimeFmt.durationShort(uncertainty)}", color = colors.textMid)
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "The window widens with distance and narrows as you get close.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }

    // ---- review, then end ----
    if (showEndReview) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showEndReview = false }, sheetState = sheet) {
            EndJourneyReview(
                report = report,
                measures = measures,
                privateVehicle = profile.isPrivateVehicle,
                whatsAppEnabled = vm.whatsAppEnabled,
                onAddExpense = { showEndReview = false; showExpense = true },
                onCancel = { showEndReview = false },
                onConfirm = { note ->
                    showEndReview = false
                    vm.complete(note) { readyToSend ->
                        if (readyToSend) showSend = true
                        else nav.navigate(Routes.summary(tripId)) { popUpTo(Routes.HOME) }
                    }
                }
            )
        }
    }

    // ---- send the timeline to the circle ----
    if (showSend) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val recipients by vm.sendRecipients.collectAsStateWithLifecycle()
        ModalBottomSheet(
            onDismissRequest = {
                showSend = false
                nav.navigate(Routes.summary(tripId)) { popUpTo(Routes.HOME) }
            },
            sheetState = sheet
        ) {
            SendTimelineSheet(
                recipients = recipients,
                whatsAppAvailable = vm.whatsAppAvailable,
                onSend = { recipient ->
                    val intent = vm.sendIntentFor(recipient) ?: vm.fallbackSendIntent()
                    if (intent != null) context.startActivity(intent)
                },
                onShareOther = { vm.fallbackSendIntent()?.let { context.startActivity(it) } },
                onDone = {
                    showSend = false
                    nav.navigate(Routes.summary(tripId)) { popUpTo(Routes.HOME) }
                }
            )
        }
    }

    // ---- edit the running journey ----
    if (showEdit) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val editBusy by vm.editBusy.collectAsStateWithLifecycle()
        val editMessage by vm.editMessage.collectAsStateWithLifecycle()
        ModalBottomSheet(
            onDismissRequest = { showEdit = false; vm.clearEditMessage() },
            sheetState = sheet
        ) {
            EditJourneySheet(
                legs = legs,
                activeLegIndex = t?.activeLegIndex ?: 0,
                busy = editBusy,
                message = editMessage,
                onSwitchMode = { mode, details, breakdown -> vm.switchMode(mode, details, breakdown) },
                onUpdateDetails = { index, details -> vm.updateStageDetails(index, details) },
                onClose = { showEdit = false; vm.clearEditMessage() }
            )
        }
    }

    // ---- sheets ----
    if (showNotes) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showNotes = false }, sheetState = sheet) {
            QuickNoteSheet(profile = profile, moving = moving) { type, text ->
                vm.addNote(type, text); showNotes = false
            }
        }
    }

    if (showCheckpoint || checkpointDue) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showCheckpoint = false; if (checkpointDue) vm.skipCheckpoint() },
            sheetState = sheet
        ) {
            CheckpointSheet(
                profile = profile,
                fuelUnit = TravelDetails.fuelUnit(activeLeg?.fuelType ?: t?.fuelType),
                onSubmit = { c, refuelAmount, refuelQty, unit ->
                    if (refuelAmount != null) vm.submitCheckpointWithRefuel(c, refuelAmount, refuelQty, unit)
                    else vm.submitCheckpoint(c)
                    showCheckpoint = false
                },
                onSkip = { vm.skipCheckpoint(); showCheckpoint = false }
            )
        }
    }

    if (overnightDue) {
        OvernightDialog { type -> vm.answerOvernight(type) }
    }

    if (showExpense) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showExpense = false }, sheetState = sheet) {
            ExpenseSheet(
                profile = profile,
                onSave = { type, item, amount, qty, unit ->
                    vm.addExpense(type, item, amount, qty, unit, null)
                    showExpense = false
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sheets
// ---------------------------------------------------------------------------

/** The invitation text, built the same way the credentials screen builds it. */
private fun shareInvitation(tripId: String?, passcode: String?): String? {
    if (tripId == null) return null
    return buildString {
        appendLine("I'm on a journey — follow along on Koode.")
        appendLine("You'll know the moment I arrive safely, without having to call.")
        appendLine()
        appendLine("Journey number: $tripId")
        if (!passcode.isNullOrBlank()) appendLine("Passcode: $passcode")
        appendLine()
        appendLine("Watch in any web browser — nothing to install:")
        appendLine(com.trippulse.app.ui.Links.WEB_VIEWER)
        appendLine()
        appendLine("Or get the Koode app (free):")
        append(com.trippulse.app.ui.Links.APK)
    }
}

/**
 * The last look before a journey is closed.
 *
 * Ending a journey is the one irreversible action in the app: it publishes a
 * summary to everyone who was following, it may send a document to their
 * phones, and nothing can be edited afterwards. So it gets a review — the
 * same analysed dashboard everyone else will see, a chance to add a missing
 * expense or a closing note, and only then a confirmation.
 */
@Composable
private fun EndJourneyReview(
    report: JourneyAnalytics.JourneyReport?,
    measures: com.trippulse.app.domain.Measures,
    privateVehicle: Boolean,
    whatsAppEnabled: Boolean,
    onAddExpense: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    val colors = KoodeTheme.colors
    var note by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text("Before you close this journey", color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
        Text(
            "This is what everyone following you will see, and it can't be changed afterwards. " +
                "Have a look before you confirm.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )

        if (report == null) {
            Text("Working out the numbers…", color = colors.textLow, style = MaterialTheme.typography.bodyMedium)
        } else {
            JourneyDashboard(report, measures, privateVehicle, compact = true)
        }

        SecondaryButton("Add a missing expense", onAddExpense, leading = "₹", height = 44.dp)

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Anything to add? (optional)") },
            placeholder = { Text("Reached safely, roads were clear") },
            modifier = Modifier.fillMaxWidth()
        )

        if (whatsAppEnabled) {
            KoodeCard(accent = colors.traveller) {
                Text(
                    "Your timeline will be prepared for your circle on WhatsApp as soon as you confirm.",
                    color = colors.traveller, style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Costs are never included.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }
        }

        PrimaryButton(
            "Confirm and end journey",
            { onConfirm(note.trim().ifBlank { null }) },
            leading = "🏁"
        )
        SecondaryButton("Not yet — keep going", onCancel, accent = colors.textMid, height = 44.dp)
        Spacer(Modifier.height(Spacing.lg))
    }
}

/**
 * One tap per person, right after the journey closes.
 *
 * Android has no way for an app to send a WhatsApp message on someone's
 * behalf, and it should not: an app that could silently message your contacts
 * from your account is not one anybody should install. What Koode does instead
 * is prepare the message completely — the PDF built, the recipient chosen, the
 * covering text written — and open WhatsApp on that conversation. The
 * traveller taps send, and it genuinely comes from them.
 */
@Composable
private fun SendTimelineSheet(
    recipients: List<TimelineDelivery.Recipient>,
    whatsAppAvailable: Boolean,
    onSend: (TimelineDelivery.Recipient) -> Unit,
    onShareOther: () -> Unit,
    onDone: () -> Unit
) {
    val colors = KoodeTheme.colors
    val sent = remember { mutableStateListOf<String>() }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text("Journey ended safely 🏁", color = colors.accent, style = MaterialTheme.typography.headlineSmall)
        Text(
            if (whatsAppAvailable)
                "Your timeline is ready. Tap a name to open WhatsApp with the document attached — " +
                    "it sends from your own account."
            else "WhatsApp isn't installed, so use the share sheet below to send your timeline.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )

        if (whatsAppAvailable) {
            recipients.forEach { r ->
                val alreadySent = sent.contains(r.phone)
                KoodeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                            Text(r.phone, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
                        }
                        Box(Modifier.width(120.dp)) {
                            SecondaryButton(
                                if (alreadySent) "Sent ✓" else "Send",
                                { onSend(r); if (!alreadySent) sent.add(r.phone) },
                                accent = if (alreadySent) colors.textLow else colors.accent,
                                height = 42.dp
                            )
                        }
                    }
                }
            }
            if (recipients.isEmpty()) {
                Text(
                    "No circle contacts have a phone number yet — add them under More → Emergency contacts.",
                    color = colors.warn, style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        SecondaryButton("Send another way", onShareOther, leading = "📤", height = 44.dp)
        Text(
            "Only the timeline goes. Your money tracker stays on this phone.",
            color = colors.textLow, style = MaterialTheme.typography.bodySmall
        )
        PrimaryButton("Done", onDone)
        Spacer(Modifier.height(Spacing.lg))
    }
}

/**
 * Changing a journey that is already running.
 *
 * There is exactly one thing to change here, and it is not the destination.
 * Where someone is going was settled when they started and was told to
 * everyone following them; quietly re-pointing it would turn the journey they
 * agreed to watch into a different journey. What genuinely changes mid-way is
 * the vehicle -- getting off the train at Bangalore and carrying on by bus --
 * and that needs no destination field and no "where are you now", because the
 * journey knows the first and the phone knows the second.
 *
 * So: which vehicle, its details, and go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditJourneySheet(
    legs: List<TripLegEntity>,
    activeLegIndex: Int,
    busy: Boolean,
    message: String?,
    onSwitchMode: (String, Map<String, String>, Boolean) -> Unit,
    onUpdateDetails: (Int, Map<String, String>) -> Unit,
    onClose: () -> Unit
) {
    val colors = KoodeTheme.colors
    val current = legs.firstOrNull { it.legIndex == activeLegIndex }
    val currentProfile = TransportCatalog.profile(current?.mode)
    val leavingPrivate = currentProfile.isPrivateVehicle

    // Keyed on the current stage, not remembered once. `legs` arrives from a
    // flow and is empty on the first composition, so an unkeyed remember would
    // latch "CAR" and then keep showing it to someone sitting on a train.
    var mode by remember(current?.legIndex, current?.mode) {
        mutableStateOf(current?.mode ?: TransportCatalog.CAR.key)
    }
    var details by remember(current?.legIndex) {
        mutableStateOf(LegDetails.fromJson(current?.detailsJson))
    }
    var breakdown by remember { mutableStateOf(false) }
    // Correcting the current stage's details is a different act from changing
    // vehicle, so it is a different button rather than a mode of this one.
    var correcting by remember { mutableStateOf(false) }

    // Switching to a new mode starts from a clean sheet; the coach number of
    // the train you just got off means nothing on the bus.
    val onModeChosen: (String) -> Unit = { chosen ->
        if (chosen != mode) {
            mode = chosen
            details =
                if (chosen == current?.mode) LegDetails.fromJson(current?.detailsJson)
                else emptyMap()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text("Update this journey", color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
        Text(
            "You're still heading to the same place — this is for when how you're " +
                "getting there changes. Everyone following you sees it immediately.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )

        if (legs.isNotEmpty()) {
            KoodeCard(title = "Stages so far") {
                legs.sortedBy { it.legIndex }.forEach { leg ->
                    val done = leg.completedAtMs != null
                    val active = leg.legIndex == activeLegIndex
                    val vehicle = LegDetails.summaryOf(leg.mode, leg.detailsJson)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(TransportCatalog.emoji(leg.mode), fontSize = 15.sp)
                        Spacer(Modifier.width(Spacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${leg.fromName} → ${leg.toName}",
                                color = if (done) colors.textLow else colors.textHigh,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (vehicle.isNotBlank()) {
                                Text(vehicle, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        when {
                            done -> StatusPill("Done", colors.textLow)
                            active -> TextButton(onClick = {
                                correcting = true
                                mode = leg.mode
                                details = LegDetails.fromJson(leg.detailsJson)
                            }) { Text("Correct details", color = colors.accent, fontSize = 13.sp) }
                            else -> StatusPill("Planned", colors.textMid)
                        }
                    }
                }
            }
        }

        KoodeCard(
            title = if (correcting) "Correct the current stage" else "I've changed vehicle",
            accent = colors.accent
        ) {
            if (!correcting) {
                Text(
                    "How are you travelling now?",
                    color = colors.textLow, style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(Spacing.sm))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TransportCatalog.ALL.forEach { p ->
                        KoodeChip(p.label, mode == p.key, { onModeChosen(p.key) }, leading = p.emoji)
                    }
                }
                Spacer(Modifier.height(Spacing.md))
            }

            TravelDetailFields(
                mode = mode,
                values = details,
                onChange = { key, value -> details = details + (key to value) }
            )

            // Leaving your own vehicle part-way is not a plan change, it is
            // something going wrong, and the timeline should say so.
            if (!correcting && leavingPrivate && mode != current?.mode) {
                Spacer(Modifier.height(Spacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = breakdown, onCheckedChange = { breakdown = it })
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        "My vehicle has broken down",
                        color = colors.textHigh, style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "A car journey has no stages unless something went wrong, so this " +
                        "is the only reason to switch out of one.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(Spacing.md))
            val ready = TravelDetails.isComplete(mode, details)
            PrimaryButton(
                when {
                    busy -> "Working…"
                    correcting -> "Save these details"
                    else -> "I'm travelling this way now"
                },
                {
                    if (correcting) onUpdateDetails(activeLegIndex, details)
                    else onSwitchMode(mode, details, breakdown)
                    correcting = false
                },
                enabled = !busy && ready
            )
            if (!ready) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "Still needed: " +
                        TravelDetails.missingRequired(mode, details).joinToString(", ") { it.label } +
                        ". In your own vehicle these are what someone would repeat down " +
                        "the phone to find you.",
                    color = colors.warn, style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (message != null) {
            Text(message, color = colors.accent, style = MaterialTheme.typography.bodyMedium)
        }
        SecondaryButton("Close", onClose)
        Spacer(Modifier.height(Spacing.lg))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpenseSheet(
    profile: TransportProfile,
    onSave: (String, String, Double, Double?, String?) -> Unit
) {
    val colors = KoodeTheme.colors
    var type by remember { mutableStateOf(if (profile.asksAboutFuel) "FUEL" else "TICKET") }
    var item by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("L") }

    val valid = InputRules.isValidExpense(item, amount)

    Column(
        Modifier.fillMaxWidth().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text("Add an expense", color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Private to you — it stays on this phone and nobody following you ever sees it.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (profile.asksAboutFuel) KoodeChip("Fuel", type == "FUEL", { type = "FUEL" }, leading = "⛽")
            if (!profile.isPrivateVehicle) KoodeChip("Ticket", type == "TICKET", { type = "TICKET" }, leading = "🎫")
            KoodeChip("Food", type == "FOOD", { type = "FOOD" }, leading = "🍛")
            KoodeChip("Stay", type == "STAY", { type = "STAY" }, leading = "🏨")
            KoodeChip("Other", type == "OTHER", { type = "OTHER" }, leading = "🧾")
        }
        OutlinedTextField(
            value = item,
            onValueChange = { item = InputRules.itemText(it) },
            label = { Text("Item — what was it?") },
            placeholder = { Text("Highway dhaba lunch") },
            singleLine = true,
            supportingText = { Text("Letters only", color = colors.textLow, fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = InputRules.amountText(it) },
            label = { Text("Amount") },
            placeholder = { Text("450") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Numbers only", color = colors.textLow, fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth()
        )
        if (type == "FUEL") {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = qty,
                    onValueChange = { qty = InputRules.quantityText(it) },
                    label = { Text("Quantity") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                KoodeChip("Litres", unit == "L", { unit = "L" })
                KoodeChip("kWh", unit == "kWh", { unit = "kWh" })
            }
            Text(
                "Used for this journey's fuel-efficiency figure.",
                color = colors.textLow, style = MaterialTheme.typography.bodySmall
            )
        }
        PrimaryButton(
            "Save expense",
            {
                val amt = InputRules.parseAmount(amount) ?: return@PrimaryButton
                onSave(type, item, amt, qty.toDoubleOrNull(), if (type == "FUEL") unit else null)
            },
            enabled = valid
        )
        Spacer(Modifier.height(Spacing.md))
    }
}

/**
 * Hold-to-send SOS. A deliberate 1.5 s hold, because an accidental SOS costs
 * the people watching a genuine fright.
 */
@Composable
private fun SosHoldButton(onTriggered: () -> Unit, enabled: Boolean) {
    val colors = KoodeTheme.colors
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(Radii.md))
            .background(colors.danger.copy(alpha = 0.12f))
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
            modifier = Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(Radii.md)),
            color = colors.danger.copy(alpha = 0.35f),
            trackColor = androidx.compose.ui.graphics.Color.Transparent
        )
        Text(
            if (enabled) "Hold for SOS" else "SOS active",
            color = colors.danger,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickNoteSheet(
    profile: TransportProfile,
    moving: Boolean,
    onEvent: (String, String?) -> Unit
) {
    val colors = KoodeTheme.colors
    var text by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text("Add a note", color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            profile.quickActions
                .filter { !moving || it.availableWhileMoving }
                .forEach { action ->
                    KoodeChip(action.label, false, { onEvent(action.eventType, action.timelineText) }, leading = action.emoji)
                }
        }
        if (!moving) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Anything else") }, modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton(
                "Add note",
                { onEvent(EventTypes.QUICK_NOTE, text.ifBlank { null }) },
                enabled = text.isNotBlank(), height = 46.dp
            )
        } else {
            Text(
                "Typing is disabled while you're moving. Tap one of the options above instead.",
                color = colors.textLow, style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

/**
 * The break log.
 *
 * Refuelling only appears for private vehicles — a train passenger is never
 * asked about fuel — and the wording changes with the mode, so a bus passenger
 * is logging what they had, not "what happened on this stop".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckpointSheet(
    profile: TransportProfile,
    fuelUnit: String,
    onSubmit: (TripManager.Checkpoint, Double?, Double?, String) -> Unit,
    onSkip: () -> Unit
) {
    val colors = KoodeTheme.colors
    var water by remember { mutableStateOf(false) }
    var food by remember { mutableStateOf(false) }
    var tea by remember { mutableStateOf(false) }
    var snack by remember { mutableStateOf(false) }
    var toilet by remember { mutableStateOf(false) }
    var rest by remember { mutableStateOf(false) }
    var fuel by remember { mutableStateOf(false) }
    var charge by remember { mutableStateOf(false) }
    var mealKind by remember { mutableStateOf<Nourishment?>(null) }
    var refuelCost by remember { mutableStateOf("") }
    var refuelQty by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            if (profile.wellbeingIsBreak) "What happened on this stop?" else "What have you had?",
            color = colors.textHigh, style = MaterialTheme.typography.headlineSmall
        )
        Text(
            if (profile.wellbeingIsBreak)
                "Tap all that apply. It takes two seconds and it's what keeps your family relaxed."
            else "Tap all that apply. On ${profile.label.lowercase()} this is a note, not a stop.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            KoodeChip("Water", water, { water = !water }, leading = "💧")
            KoodeChip("Food", food, { food = !food }, leading = "🍛")
            KoodeChip("Tea / coffee", tea, { tea = !tea }, leading = "☕")
            KoodeChip("Snack", snack, { snack = !snack }, leading = "🍪")
            KoodeChip("Toilet", toilet, { toilet = !toilet }, leading = "🚻")
            KoodeChip("Rest", rest, { rest = !rest }, leading = "😴")
            // Fuel questions exist only for private vehicles.
            if (profile.asksAboutFuel) {
                if (fuelUnit == "kWh") KoodeChip("Charged", charge, { charge = !charge }, leading = "🔌")
                else KoodeChip("Refuelled", fuel, { fuel = !fuel }, leading = "⛽")
            }
        }

        // Koode names the meal from the clock; this row exists only for the
        // times it guesses wrong, or the traveller wants to be explicit.
        if (food) {
            Text("Which meal?", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                KoodeChip("Let Koode decide", mealKind == null, { mealKind = null })
                listOf(Nourishment.BREAKFAST, Nourishment.LUNCH, Nourishment.DINNER, Nourishment.SNACK).forEach { m ->
                    KoodeChip(m.label, mealKind == m, { mealKind = m }, leading = m.emoji)
                }
            }
        }

        if (profile.asksAboutFuel && (fuel || charge)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = refuelCost, onValueChange = { refuelCost = InputRules.amountText(it) },
                    label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = refuelQty, onValueChange = { refuelQty = InputRules.quantityText(it) },
                    label = { Text(fuelUnit) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        PrimaryButton(
            "Save",
            {
                onSubmit(
                    TripManager.Checkpoint(
                        water = water, food = food, toilet = toilet, rest = rest,
                        fuel = fuel, charge = charge, tea = tea, snack = snack,
                        mealKind = mealKind
                    ),
                    InputRules.parseAmount(refuelCost), refuelQty.toDoubleOrNull(), fuelUnit
                )
            },
            enabled = water || food || tea || snack || toilet || rest || fuel || charge,
            height = 48.dp
        )
        SecondaryButton("Not now", onSkip, accent = colors.textMid, height = 44.dp)
        Spacer(Modifier.height(Spacing.md))
    }
}

@Composable
private fun OvernightDialog(onChoice: (String) -> Unit) {
    val colors = KoodeTheme.colors
    AlertDialog(
        onDismissRequest = { },
        confirmButton = {},
        title = { Text("Stopping for the night?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "You've been stopped a while. Let your family know what's happening.",
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.xs))
                SecondaryButton("Hotel or lodge", { onChoice("HOTEL") }, leading = "🏨", height = 44.dp)
                SecondaryButton("With family or friends", { onChoice("FAMILY") }, leading = "🏠", height = 44.dp)
                SecondaryButton("Resting in the vehicle", { onChoice("VEHICLE") }, leading = "🚗", height = 44.dp)
                PrimaryButton("Still going — continue", { onChoice("CONTINUING") }, height = 46.dp)
            }
        }
    )
}

// ---------------------------------------------------------------------------
// Text helpers
// ---------------------------------------------------------------------------

private fun journeyLabel(journey: String?): String = when (journey) {
    JourneyStatus.READY.name -> "Ready to go"
    JourneyStatus.DRIVING.name -> "On the move"
    JourneyStatus.POSSIBLE_STOP.name -> "Slowing down"
    JourneyStatus.STOPPED.name -> "Stopped"
    JourneyStatus.LONG_STOP.name -> "Long stop"
    JourneyStatus.OVERNIGHT.name -> "Overnight rest"
    JourneyStatus.PAUSED.name -> "Paused"
    JourneyStatus.ARRIVED.name -> "At the destination"
    JourneyStatus.COMPLETED.name -> "Journey ended"
    else -> "—"
}

private fun etaRangeText(likely: Long?, low: Long?, high: Long?): String {
    if (likely == null) return "Calculating…"
    val l = low ?: likely
    val h = high ?: likely
    return "${TimeFmt.clockWithDay(l, System.currentTimeMillis())} – ${TimeFmt.clock(h)}"
}

/**
 * How long after coming back the traveller is still told about it.
 *
 * Long enough to survive a night's sleep -- a battery that died at 2am should
 * still be explained at breakfast -- and short enough that it is gone by the
 * next journey.
 */
private const val RECENT_RETURN_MS = 12 * 60 * 60 * 1000L
