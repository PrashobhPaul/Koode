package com.trippulse.app.ui.screens

import com.trippulse.app.R
import com.trippulse.app.domain.Expenses.Category as ExpenseCategory
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.trippulse.app.domain.Halts
import com.trippulse.app.domain.JourneyPlan
import com.trippulse.app.domain.WellbeingCoach
import com.trippulse.app.domain.JourneyStatus
import com.trippulse.app.domain.Nourishment
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TransportProfile
import com.trippulse.app.ui.DriverVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.components.TravelDetailFields
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.OwnerAvatar
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
import androidx.compose.foundation.layout.PaddingValues
import com.trippulse.app.ui.components.RideProgress
import com.trippulse.app.ui.components.JourneyHero
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
    val routeAhead by vm.routeAhead.collectAsStateWithLifecycle()
    val stageMessage by vm.stageMessage.collectAsStateWithLifecycle()
    val requests by vm.joinRequests.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val expenseRows by vm.expenses.collectAsStateWithLifecycle()
    val opportunities by vm.opportunities.collectAsStateWithLifecycle()

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
    var showHalt by remember { mutableStateOf(false) }
    var showSpending by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TimelineItem?>(null) }
    var pickDestination by remember { mutableStateOf(false) }
    var haltPlanDismissed by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<JourneyAnalytics.JourneyReport?>(null) }
    val measures = vm.measures
    val context = LocalContext.current

    // The review needs current numbers, not the ones from when the screen
    // opened — the traveller is about to publish them.
    var suggestedEnd by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(showEndReview) {
        if (showEndReview) {
            suggestedEnd = vm.suggestedEndMs()
            report = vm.buildReport(suggestedEnd)
        }
    }

    val checkpointDue = s?.checkpointDue == true
    val haltQuestionDue = s?.longStopPromptDue == true
    val halting = s?.overnightType != null
    val stationary = s?.journey == JourneyStatus.STOPPED.name || s?.journey == JourneyStatus.LONG_STOP.name
    // Koode suggested planning an overnight halt on this long journey.
    val haltPlanSuggested = remember(events) {
        events.any {
            it.type == EventTypes.HALT_SUGGESTED &&
                EventCodec.payloadFromJson(it.payloadJson)["kind"] == "LONG_HAUL"
        }
    }
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
    ) {
        // ---- the journey, map first: header over the 3D map, status card on its edge ----
        JourneyHero(
            map = { mapHeight, controlsPadding ->
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
                    // The road still ahead, for a stage that goes by road.
                    route = if (profile.isRoadMode) routeAhead else emptyList(),
                    mode = profile.key,
                    stages = remember(legs) { com.trippulse.app.domain.MapStages.of(legs.map { it.startedAtMs to it.mode }) },
                    moving = moving,
                    immersive = true,
                    height = mapHeight,
                    controlsPadding = controlsPadding
                )
            },
            header = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OwnerAvatar(40.dp)
                    Spacer(Modifier.width(Spacing.sm))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.trippulse.app.ui.components.ModeArt(profile.key, 22.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                journeyLabel(s?.journey),
                                color = colors.accent, style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Text(
                            activeLeg?.toName ?: t?.destName ?: "Journey",
                            color = colors.textHigh, style = MaterialTheme.typography.headlineMedium,
                            maxLines = 1
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
                                color = if (it <= 15) colors.warn else colors.textMid,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (pending > 0) {
                            Text(
                                "$pending waiting to sync",
                                color = colors.textMid, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            card = {
                when (s?.etaMode) {
                    EtaMode.OVERNIGHT_PENDING.name -> {
                        Text(
                            "Halting · ${Halts.Type.from(s?.overnightType).label}",
                            color = colors.warn, style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "A new estimate appears when you're on the move again.",
                            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    EtaMode.ARRIVED.name ->
                        Text(stringResource(R.string.t_arrived_a22d6), color = colors.accent, style = MaterialTheme.typography.headlineSmall)
                    else -> {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.t_estimated_arrival_454fa), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                                Text(
                                    etaRangeText(s?.etaLikelyMs, s?.etaLowMs, s?.etaHighMs),
                                    color = colors.textHigh, style = MaterialTheme.typography.headlineSmall
                                )
                            }
                            s?.speedKmh?.takeIf { moving && it >= 1.0 }?.let { kmh ->
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(stringResource(R.string.t_speed_265ff), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        measures.speed(kmh, profile.key),
                                        color = colors.textHigh, style = MaterialTheme.typography.titleLarge
                                    )
                                }
                            }
                        }
                        if (s?.etaBreakdownJson != null) {
                            TextButton(onClick = { showEtaBreakdown = true }, contentPadding = PaddingValues(0.dp)) {
                                Text(stringResource(R.string.t_why_this_estimate_551da), color = colors.accent, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                RideProgress(
                    progress = (s?.progressPct ?: 0.0).toFloat(),
                    mode = profile.key,
                    emoji = profile.emoji,
                    moving = moving
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    // Distances are rendered in the traveller's own units,
                    // worked out from where they actually are.
                    Text(
                        "${measures.distance(s?.distanceCoveredM ?: 0.0, profile.key)} done",
                        color = colors.textMid, style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${measures.distance(s?.distanceRemainingM ?: 0.0, profile.key)} to go",
                        color = colors.textMid, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        )

        Spacer(Modifier.height(Spacing.lg))
        AdaptiveContainer {
            // ---- tracking died (force stop, update, battery manager): say so,
            // and bring it back from here, where Android allows the start ----
            val journeyOpen = s?.journey?.let { it != JourneyStatus.COMPLETED.name && it != JourneyStatus.EXPIRED.name } == true
            var trackingAlive by remember { mutableStateOf(true) }
            LaunchedEffect(journeyOpen) {
                while (journeyOpen) {
                    if (!com.trippulse.app.service.TrackingResume.isAlive(context)) {
                        com.trippulse.app.service.TrackingResume.ensureRunning(context)
                        kotlinx.coroutines.delay(2_000)
                    }
                    trackingAlive = com.trippulse.app.service.TrackingResume.isAlive(context)
                    kotlinx.coroutines.delay(5_000)
                }
            }
            AnimatedBanner(visible = journeyOpen && !trackingAlive) {
                KoodeHeroCard(accent = colors.warn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(colors.warn, size = 9.dp)
                        Text(stringResource(R.string.t_tracking_is_off_1dd13), color = colors.warn, style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        "Your journey is open but nobody following it is getting updates. Resume tracking to go live again.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.md))
                    PrimaryButton(
                        "Resume tracking",
                        {
                            runCatching { com.trippulse.app.service.TripTrackingService.start(context) }
                            trackingAlive = true
                        },
                        accent = colors.warn, height = 46.dp
                    )
                }
            }

            // ---- arrival: the app asks, the traveller decides ----
            AnimatedBanner(visible = arrivalDue) {
                KoodeHeroCard(accent = colors.accent) {
                    Text(
                        com.trippulse.app.domain.JourneyClosure.closePromptTitle(activeLeg?.toName ?: t?.destName ?: "your destination"),
                        color = colors.accent, style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "It looks like you've arrived at your destination. Close your journey when you're ready.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Nobody following you hears it ended until you've reviewed it. With no answer, Koode closes it " +
                            "after about ${com.trippulse.app.domain.JourneyClosure.AUTO_CLOSE_AFTER_MIN} minutes, still for your review.",
                        color = colors.textLow, style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(Spacing.md))
                    val arrivalScope = rememberCoroutineScope()
                    if (hasNextLeg) {
                        PrimaryButton(stringResource(R.string.t_next_stage_f9c81), { vm.nextLeg() }, height = 48.dp)
                        Spacer(Modifier.height(Spacing.sm))
                    }
                    // End now: closed at the arrival time, straight into the review.
                    PrimaryButton(
                        "End journey",
                        {
                            arrivalScope.launch {
                                val endAt = vm.suggestedEndMs()
                                vm.complete(null, endAt) { nav.navigate(Routes.summary(tripId)) { popUpTo(Routes.HOME) } }
                            }
                        },
                        accent = if (hasNextLeg) colors.textMid else null,
                        height = 48.dp
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    SecondaryButton(stringResource(R.string.t_review_journey_first_ec2b6), { showEndReview = true }, height = 46.dp)
                    Spacer(Modifier.height(Spacing.sm))
                    SecondaryButton(stringResource(R.string.t_i_m_still_travelling_4e3f9), { vm.dismissArrivalPrompt() }, accent = colors.textMid, height = 46.dp)
                }
            }

            // ---- a confirmed halt: resume when ready ----
            AnimatedBanner(visible = halting) {
                KoodeHeroCard(accent = colors.warn) {
                    val type = Halts.Type.from(s?.overnightType)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.trippulse.app.ui.components.ArtImage(com.trippulse.app.ui.components.KoodeArt.stay, 44.dp)
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Halting · ${type.label}", color = colors.warn, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        buildString {
                            append("Everyone following you knows you're halting")
                            s?.overnightSinceMs?.let { append(" since ${TimeFmt.clock(it)}") }
                            append(". Coaching is paused until you set off.")
                        },
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Box(Modifier.weight(1f)) {
                            PrimaryButton(stringResource(R.string.t_resume_journey_b03fe), { vm.resumeFromHalt() }, height = 46.dp)
                        }
                        Box(Modifier.weight(1f)) {
                            SecondaryButton(stringResource(R.string.t_cancel_halt_9923b), { vm.cancelHalt() }, accent = colors.textMid, height = 46.dp)
                        }
                    }
                }
            }

            // ---- a halt is the traveller's call, not stop detection's: the
            // button is there whenever the journey is open, and says what it
            // is for when the vehicle hasn't been seen stopping yet ----
            AnimatedBanner(visible = !halting && !haltQuestionDue && (profile.stopPromptsEnabled || stationary)) {
                SecondaryButton(
                    if (stationary) "I'm taking a halt here" else "Taking a halt (hotel, family, rest stop)",
                    { showHalt = true }, leading = "🛏", height = 44.dp
                )
            }

            // ---- long-haul planning: a suggestion, never a command ----
            AnimatedBanner(visible = haltPlanSuggested && plan?.plannedHalt == null && !halting && !haltPlanDismissed) {
                KoodeCard(accent = colors.traveller, title = "A long journey ahead") {
                    Text(
                        com.trippulse.app.domain.HaltPlanning.TEXT,
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Box(Modifier.weight(1f)) {
                            SecondaryButton(stringResource(R.string.t_plan_a_halt_38967), { showEdit = true }, accent = colors.traveller, height = 44.dp)
                        }
                        Box(Modifier.weight(1f)) {
                            SecondaryButton(stringResource(R.string.t_not_now_e4571), { haltPlanDismissed = true }, accent = colors.textMid, height = 44.dp)
                        }
                    }
                }
            }

            // ---- SOS ----
            AnimatedBanner(visible = s?.sosActive == true) {
                KoodeHeroCard(accent = colors.danger) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(colors.danger, size = 9.dp)
                        Text(stringResource(R.string.t_sos_is_active_eef6c), color = colors.danger, style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        "Everyone following you has been alerted. Resolve it when you're safe.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.md))
                    PrimaryButton(stringResource(R.string.t_i_m_safe_resolve_sos_d0cf9), { vm.resolveSos() }, height = 46.dp)
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

            // ---- stages, when there is more than one ----
            // A change tapped twice is one stage: the pair reads as one.
            val stages = remember(legs) {
                com.trippulse.app.domain.StageRepair.folded(legs, { it.mode }, { it.startedAtMs }, { it.completedAtMs }) { a, b ->
                    b.copy(fromName = a.fromName, fromLat = a.fromLat, fromLng = a.fromLng, startedAtMs = a.startedAtMs)
                }
            }
            if (stages.size > 1) {
                KoodeCard(title = stringResource(R.string.t_stages_c1d33)) {
                    stages.forEach { leg ->
                        val isActive = leg.legIndex == (t?.activeLegIndex ?: 0)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            com.trippulse.app.ui.components.ModeArt(leg.mode, 34.dp, faceRight = true)
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                "${leg.fromName} → ${leg.toName}",
                                color = if (isActive) colors.textHigh else colors.textMid,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            when {
                                leg.completedAtMs != null -> StatusPill(stringResource(R.string.t_done_e9b45), colors.textLow)
                                isActive -> StatusPill(stringResource(R.string.t_now_e3b82), colors.accent, pulsing = true)
                                else -> StatusPill(stringResource(R.string.t_next_bc981), colors.textLow)
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
                                Text(stringResource(R.string.t_let_them_in_5adbc), color = colors.accent, fontSize = 13.sp)
                            }
                            TextButton(onClick = { vm.setViewerApproval(token, false) }) {
                                Text(stringResource(R.string.t_no_816c5), color = colors.danger, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            // ---- the people following this journey (for this journey only) ----
            val approved = requests.filter { it["status"] == "APPROVED" }
            KoodeCard(title = stringResource(R.string.t_people_following_this_journey_16d5b)) {
                if (approved.isEmpty()) {
                    Text(
                        "No one yet. Share this journey with the people you'd like to keep informed.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                }
                approved.forEach { r ->
                    val name = (r["name"] as? String)?.ifBlank { null } ?: "Someone"
                    val token = r["token"] as? String
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        com.trippulse.app.ui.components.PersonAvatar(name, 32.dp)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            name, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (token != null) {
                            TextButton(onClick = { vm.setViewerApproval(token, false) }) {
                                Text(stringResource(R.string.t_remove_e9639), color = colors.textLow, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Text(
                    "They hear about meaningful moments of this journey only — never your coaching, and nothing after it ends.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }

            // ---- one-tap wellbeing ----
            // On public transport these are simply notes: eating on a train
            // is not a break, and logging it must not imply the journey stopped.
            // ---- how you're doing: private coaching state, explicit answers ----
            KoodeCard(title = stringResource(R.string.t_how_you_re_doing_da2d4)) {
                val tiles = coachTiles(s, t?.startedAtMs, plan, profile.key, now)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    tiles.forEach { tile ->
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Radii.md))
                                .background(colors.backgroundElevated)
                                .padding(Spacing.md)
                        ) {
                            tile.art?.let { com.trippulse.app.ui.components.ArtImage(it, 40.dp) }
                            Text(tile.label, color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                            Text(tile.value, color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
                            Text(tile.caption, color = colors.textMid, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    if (profile.wellbeingIsBreak)
                        "Tap what you've had. Koode works out whether it was breakfast, lunch or dinner — this stays with you."
                    else "Tap what you've had. On ${profile.label.lowercase()} these are notes, never a stop in the journey.",
                    color = colors.textMid, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(Spacing.md))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    val art = com.trippulse.app.ui.components.KoodeArt
                    val pic = com.trippulse.app.domain.Pictures
                    KoodeChip(stringResource(R.string.t_had_water_48a89), false, { vm.logNourishment(Nourishment.WATER) }, leading = "💧", leadingArt = art.file(pic.WATER))
                    KoodeChip(stringResource(R.string.t_ate_something_309d1), false, { vm.submitCheckpoint(TripManager.Checkpoint(food = true)) }, leading = "🍛", leadingArt = art.file(pic.FOOD))
                    KoodeChip(stringResource(R.string.t_restroom_8ff8e), false, { vm.submitCheckpoint(TripManager.Checkpoint(toilet = true)) }, leading = "🚻", leadingArt = art.file(pic.TOILET))
                    KoodeChip(stringResource(R.string.t_rested_1ec20), false, { vm.submitCheckpoint(TripManager.Checkpoint(rest = true)) }, leading = "😴", leadingArt = art.file(pic.REST))
                    KoodeChip(stringResource(R.string.t_had_tea_a1480), false, { vm.logNourishment(Nourishment.TEA_COFFEE) }, leading = "☕")
                    KoodeChip(stringResource(R.string.t_had_a_snack_3c99a), false, { vm.logNourishment(Nourishment.SNACK) }, leading = "🍪")
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
                        SecondaryButton(stringResource(R.string.t_add_a_note_f9ee6), { showNotes = true }, height = 44.dp)
                    }
                }
            }

            // ---- trip spending: private, captured as it happens ----
            val role = WellbeingCoach.Role.fromKey(plan?.role) ?: WellbeingCoach.defaultRole(profile.key)
            val safeToAsk = com.trippulse.app.domain.Expenses.safeToAsk(role == WellbeingCoach.Role.DRIVER, moving)
            val pendingAsk = opportunities.filter { it.status == com.trippulse.app.domain.Expenses.Status.PENDING }
            val deferred = opportunities.count { it.status == com.trippulse.app.domain.Expenses.Status.DEFERRED }
            if (expenseRows.isNotEmpty() || opportunities.any { it.open }) {
                KoodeCard(
                    title = "Trip spending so far · private", accent = colors.traveller,
                    onClick = if (expenseRows.isNotEmpty()) ({ showSpending = true }) else null
                ) {
                    Text(
                        vm.measures.money(expenseRows.sumOf { it.amount }),
                        color = colors.textHigh, style = MaterialTheme.typography.headlineSmall
                    )
                    if (expenseRows.isNotEmpty()) {
                        Text(
                            "${expenseRows.size} ${if (expenseRows.size == 1) "entry" else "entries"} · tap to see, correct or remove",
                            color = colors.textLow, style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (pendingAsk.isNotEmpty() && !safeToAsk) {
                        Text(
                            "${pendingAsk.size} expense${if (pendingAsk.size == 1) "" else "s"} to note when you next stop — never while you drive.",
                            color = colors.textMid, style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (safeToAsk) pendingAsk.take(3).forEach { o ->
                        var amountText by remember(o.id) { mutableStateOf("") }
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "${o.category.emoji} ${com.trippulse.app.domain.Expenses.question(o)}",
                            color = colors.textHigh, style = MaterialTheme.typography.titleSmall
                        )
                        Text(o.label, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = { amountText = InputRules.amountText(it) },
                                placeholder = { Text(stringResource(R.string.t_amount_43dc8)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { amountText.toDoubleOrNull()?.let { vm.recordExpenseAmount(o.id, it) } },
                                enabled = amountText.toDoubleOrNull() != null
                            ) { Text(stringResource(R.string.t_save_efc00), color = colors.accent) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            TextButton(onClick = { vm.markNoExpense(o.id) }) { Text(stringResource(R.string.t_no_expense_bdc10), color = colors.textMid) }
                            TextButton(onClick = { vm.deferExpense(o.id) }) { Text(stringResource(R.string.t_skip_for_now_6fc09), color = colors.textMid) }
                        }
                    }
                    if (deferred > 0) {
                        Text(
                            "$deferred skipped — you can add ${if (deferred == 1) "it" else "them"} when you review the journey.",
                            color = colors.textLow, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---- the way the phone sees you moving, when it no longer fits the stage ----
            val sensed = remember(breadcrumb, profile.key) {
                com.trippulse.app.domain.ModeSense.hint(
                    profile.key,
                    breadcrumb.map { com.trippulse.app.domain.ModeSense.Fix(it.tMs, it.lat, it.lng, it.speedMps) },
                    System.currentTimeMillis()
                )
            }
            var sensedDismissed by remember(activeLeg?.legIndex) { mutableStateOf<com.trippulse.app.domain.ModeSense.Hint?>(null) }
            AnimatedBanner(visible = sensed != null && sensed != sensedDismissed) {
                KoodeCard(accent = colors.traveller) {
                    val walking = sensed == com.trippulse.app.domain.ModeSense.Hint.ON_FOOT
                    Text(
                        if (walking) "Looks like you're walking now" else "Moving faster than walking",
                        color = colors.textHigh, style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (walking) "Out of the ${if (profile.key == TransportCatalog.CAB.key) "cab" else profile.label.lowercase()}? One tap and the journey follows."
                        else "On something now? Pick it and the journey follows.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        val options = if (walking) listOf(TransportCatalog.WALK)
                            else TransportCatalog.COMMUTE.filter { it.key != TransportCatalog.WALK.key }
                        options.forEach { p ->
                            KoodeChip(if (walking) "Yes, walking" else com.trippulse.app.ui.Names.mode(p.key), false, { vm.quickSwitch(p.key) }, leading = p.emoji)
                        }
                        KoodeChip(stringResource(R.string.t_not_now_e4571), false, { sensedDismissed = sensed })
                    }
                }
            }

            // ---- how you're travelling, and changing it in one tap ----
            // A commute is rarely planned stage by stage: the cab that didn't
            // come, the walk to the metro, the auto home. Each change is one tap
            // here, from wherever you are; nothing needs adding up front.
            val stageSince = activeLeg?.startedAtMs ?: t?.startedAtMs
            val offerSwitch = !profile.isPrivateVehicle || !moving
            KoodeCard(title = stringResource(R.string.t_how_you_re_travelling_7b7fc)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.emoji, fontSize = 22.sp)
                    Spacer(Modifier.width(Spacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text(com.trippulse.app.ui.Names.mode(profile.key), color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
                        val from = activeLeg?.fromName?.takeIf { it.isNotBlank() }
                        Text(
                            listOfNotNull(stageSince?.let { "Since ${TimeFmt.clock(it)}" }, from?.let { "from $it" }).joinToString(" "),
                            color = colors.textLow, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (profile.quickActions.isNotEmpty() && !profile.isPrivateVehicle) {
                    Spacer(Modifier.height(Spacing.sm))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        profile.quickActions.forEach { action ->
                            KoodeChip(
                                action.label, false,
                                { vm.addNote(action.eventType, action.timelineText) },
                                leading = action.emoji
                            )
                        }
                    }
                }
                if (offerSwitch) {
                    Spacer(Modifier.height(Spacing.md))
                    Text(stringResource(R.string.t_now_on_something_else_de931), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(Spacing.xs))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        TransportCatalog.COMMUTE.filter { it.key != profile.key }.forEach { p ->
                            KoodeChip(com.trippulse.app.ui.Names.mode(p.key), false, { vm.quickSwitch(p.key) }, leading = p.emoji)
                        }
                        KoodeChip(stringResource(R.string.t_other_6e6a6), false, { showEdit = true }, leading = "⋯")
                    }
                    stageMessage?.let {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(it, color = colors.warn, style = MaterialTheme.typography.bodySmall)
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
                        SecondaryButton(stringResource(R.string.t_resume_b3bd0), { vm.resume() }, leading = "▶", height = 46.dp)
                    } else {
                        SecondaryButton(stringResource(R.string.t_pause_78196), { vm.pause() }, leading = "⏸", height = 46.dp)
                    }
                }
                Box(Modifier.weight(1f)) {
                    SecondaryButton(
                        "End journey", { showEndReview = true },
                        accent = colors.warn, height = 46.dp
                    )
                }
            }

            // Tap opens a short, cancellable countdown that shows who will be
            // alerted; it sends by itself when the count runs out.
            var sosCountdown by remember { mutableStateOf(false) }
            com.trippulse.app.ui.components.SosButton(
                enabled = s?.sosActive != true,
                onClick = { sosCountdown = true }
            )
            if (sosCountdown) {
                com.trippulse.app.ui.components.SosCountdown(
                    recipients = requests
                        .filter { it["status"] == "APPROVED" }
                        .mapNotNull { (it["name"] as? String)?.takeIf { n -> n.isNotBlank() } },
                    onSend = { sosCountdown = false; vm.activateSos() },
                    onCancel = { sosCountdown = false },
                    emergencyNumber = com.trippulse.app.ui.theme.LocalMarket.current.emergencyNumber
                )
            }

            // ---- timeline ----
            SectionHeader(stringResource(R.string.t_timeline_01851))
            KoodeCard {
                val items = remember(events) {
                    timelineItems(
                        events.map { e ->
                            TimelineEvent(e.eventId, e.type, e.eventTimeMs, com.trippulse.app.data.EventCodec.payloadFromJson(e.payloadJson))
                        }
                    )
                }
                Text(
                    "Tap one of your own entries to change its time or remove it.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(Spacing.sm))
                TimelineList(items, now, onEdit = { editing = it })
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
            confirmButton = { TextButton(onClick = { showEtaBreakdown = false }) { Text(stringResource(R.string.t_got_it_5b802)) } },
            title = { Text(stringResource(R.string.t_how_the_estimate_is_built_30f16)) },
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
                arrivalMs = suggestedEnd,
                onConfirm = { note, endAt ->
                    showEndReview = false
                    // Closing leads straight into the review, where the traveller
                    // approves what the people following them receive.
                    vm.complete(note, endAt) {
                        nav.navigate(Routes.summary(tripId)) { popUpTo(Routes.HOME) }
                    }
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
                destination = t?.destName,
                plan = plan,
                planHistory = remember(plan) { vm.planHistory() },
                onChangeDestination = { pickDestination = true },
                onRole = { vm.setTravellerRole(it) },
                onPlannedHalt = { vm.setPlannedHalt(it) },
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
                autoTimed = checkpointDue || s?.stopStartedAtMs != null,
                onSubmit = { c, refuelAmount, refuelQty, unit, startAt, lastedS ->
                    if (refuelAmount != null) vm.submitCheckpointWithRefuel(c, refuelAmount, refuelQty, unit)
                    else vm.submitCheckpoint(c, startAt, lastedS)
                    showCheckpoint = false
                },
                onSkip = { vm.skipCheckpoint(); showCheckpoint = false }
            )
        }
    }

    // ---- what was spent, line by line ----
    if (showSpending) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSpending = false }, sheetState = sheet) {
            SpendingSheet(
                rows = expenseRows,
                money = { vm.measures.money(it) },
                onCorrect = { id, amount -> vm.correctExpense(id, amount) },
                onDelete = { vm.deleteExpense(it) },
                onAdd = { showSpending = false; showExpense = true }
            )
        }
    }

    // ---- correcting one of the traveller's own timeline entries ----
    editing?.let { item ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { editing = null }, sheetState = sheet) {
            EditEntrySheet(
                item = item,
                onSave = { atMs, durationS ->
                    val breakId = item.breakId
                    if (breakId != null) vm.reviseBreak(breakId, atMs, durationS, removed = false)
                    else item.eventId?.let { vm.editTimelineEntry(it, atMs, removed = false) }
                    editing = null
                },
                onRemove = {
                    val breakId = item.breakId
                    if (breakId != null) vm.reviseBreak(breakId, null, null, removed = true)
                    else item.eventId?.let { vm.editTimelineEntry(it, null, removed = true) }
                    editing = null
                },
                onCancel = { editing = null }
            )
        }
    }

    if (haltQuestionDue || showHalt) {
        HaltDialog(
            askFirst = haltQuestionDue && !showHalt,
            stoppedForMin = s?.stopStartedAtMs?.let { (now - it) / 60_000 },
            onConfirm = { type, expected, since -> showHalt = false; vm.confirmHalt(type, expected, since) },
            onDecline = { showHalt = false; if (haltQuestionDue) vm.declineHalt() }
        )
    }

    // ---- a new destination ----
    if (pickDestination) {
        val results by vm.destResults.collectAsStateWithLifecycle()
        val searching by vm.destSearching.collectAsStateWithLifecycle()
        val saved by vm.savedPlaces.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        com.trippulse.app.ui.components.PlacePicker(
            asStart = false,
            title = "Where are you going now?",
            results = results,
            searching = searching,
            saved = saved,
            recent = emptyList(),
            here = s?.lat?.let { la -> s.lng?.let { lo -> GeoPoint(la, lo) } },
            pinStart = s?.lat?.let { la -> s.lng?.let { lo -> GeoPoint(la, lo) } },
            onQuery = { vm.searchDestination(it) },
            onPick = { place -> pickDestination = false; vm.searchDestination(""); vm.changeDestination(place) },
            offerCurrentLocation = false,
            onOpenGoogleMaps = { query -> com.trippulse.app.ui.components.openGoogleMaps(context, query) },
            onSharedText = { text ->
                scope.launch {
                    val place = vm.resolveShared(text)
                    pickDestination = false
                    if (place != null) vm.changeDestination(place)
                }
            },
            onSavePlace = { name, point -> vm.savePlaceAt(name, point) },
            onDeleteSaved = { vm.deletePlace(it) },
            onDismiss = { pickDestination = false; vm.searchDestination("") }
        )
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
        appendLine("Koode will keep you informed along the way, without you having to call.")
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
 * Closing stops tracking. Nothing is published yet: the next screen is the
 * review, where the traveller checks what Koode recorded and approves what
 * the people following them receive. This sheet is the chance to add a
 * missing expense or a closing note and to pick the real end time.
 */
@Composable
private fun EndJourneyReview(
    report: JourneyAnalytics.JourneyReport?,
    measures: com.trippulse.app.domain.Measures,
    privateVehicle: Boolean,
    whatsAppEnabled: Boolean,
    onAddExpense: () -> Unit,
    onCancel: () -> Unit,
    arrivalMs: Long? = null,
    onConfirm: (String?, Long?) -> Unit
) {
    val colors = KoodeTheme.colors
    var note by remember { mutableStateOf("") }
    var endAtArrival by remember(arrivalMs) { mutableStateOf(arrivalMs != null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(stringResource(R.string.t_end_this_journey_c2f21), color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Tracking stops now. Next you'll review what Koode recorded — nothing is shared with the people " +
                "following you until you approve it.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )

        if (report == null) {
            Text(stringResource(R.string.t_working_out_the_numbers_badf6), color = colors.textLow, style = MaterialTheme.typography.bodyMedium)
        } else {
            JourneyDashboard(report, measures, privateVehicle, compact = true)
        }

        SecondaryButton(stringResource(R.string.t_add_a_missing_expense_08cab), onAddExpense, leading = "₹", height = 44.dp)

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(stringResource(R.string.t_anything_to_add_optional_d0e20)) },
            placeholder = { Text(stringResource(R.string.t_roads_were_clear_05690)) },
            modifier = Modifier.fillMaxWidth()
        )

        if (whatsAppEnabled) {
            KoodeCard(accent = colors.traveller) {
                Text(
                    "After you approve the journey, its timeline can be sent to your emergency contacts on WhatsApp.",
                    color = colors.traveller, style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Costs are never included.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (arrivalMs != null) {
            val now = System.currentTimeMillis()
            KoodeCard(accent = colors.accent) {
                Text(
                    "You reached the destination at ${TimeFmt.clockWithDay(arrivalMs, now)} (${TimeFmt.ago(now, arrivalMs)}).",
                    color = colors.textHigh, style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "The journey's times and speeds should stop there, not when you closed the app.",
                    color = colors.textMid, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    KoodeChip("End at ${TimeFmt.clock(arrivalMs)}", endAtArrival, { endAtArrival = true }, leading = "🏁")
                    KoodeChip(stringResource(R.string.t_end_now_fb5bb), !endAtArrival, { endAtArrival = false })
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }
        PrimaryButton(
            "End journey",
            { onConfirm(note.trim().ifBlank { null }, if (endAtArrival) arrivalMs else null) },
            leading = "🏁"
        )
        SecondaryButton(stringResource(R.string.t_not_yet_keep_going_f97ef), onCancel, accent = colors.textMid, height = 44.dp)
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
internal fun SendTimelineSheet(
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
        Text(stringResource(R.string.t_journey_complete_71362), color = colors.accent, style = MaterialTheme.typography.headlineSmall)
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
                    "None of your emergency contacts has a phone number yet — add them under More → Emergency contacts.",
                    color = colors.warn, style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        SecondaryButton(stringResource(R.string.t_send_another_way_c0e8a), onShareOther, leading = "📤", height = 44.dp)
        Text(
            "Only the timeline goes. Your money tracker stays on this phone.",
            color = colors.textLow, style = MaterialTheme.typography.bodySmall
        )
        PrimaryButton(stringResource(R.string.t_done_e9b45), onDone)
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
    destination: String?,
    plan: JourneyPlan?,
    planHistory: List<JourneyPlan>,
    onChangeDestination: () -> Unit,
    onRole: (WellbeingCoach.Role) -> Unit,
    onPlannedHalt: (String?) -> Unit,
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
        Text(stringResource(R.string.t_what_s_changing_53e22), color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Plans change. Each change is kept as a new version of your journey plan, " +
                "and everyone following you is told what changed.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )

        KoodeCard(title = stringResource(R.string.t_where_you_re_going_111c4)) {
            Text(destination ?: "—", color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
            plan?.takeIf { it.version > 1 }?.let {
                Text("Plan version ${it.version}", color = colors.textLow, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(Spacing.sm))
            SecondaryButton(stringResource(R.string.t_change_destination_2b6ae), onChangeDestination, leading = "🧭", height = 44.dp)
        }

        // Driving or being driven changes what the coach suggests: a passenger
        // is never asked to take a driving break.
        if (current?.mode in setOf("CAR", "BIKE", "CAB", "AUTO")) {
            val role = WellbeingCoach.Role.fromKey(plan?.role) ?: WellbeingCoach.defaultRole(current?.mode)
            KoodeCard(title = stringResource(R.string.t_travelling_as_3d8e7)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    KoodeChip(
                        if (current?.mode == "BIKE") "Rider" else "Driver",
                        role == WellbeingCoach.Role.DRIVER, { onRole(WellbeingCoach.Role.DRIVER) }, leading = "🧑‍✈️"
                    )
                    KoodeChip(
                        "Passenger", role == WellbeingCoach.Role.PASSENGER,
                        { onRole(WellbeingCoach.Role.PASSENGER) }, leading = "🧍"
                    )
                }
            }
        }

        KoodeCard(title = stringResource(R.string.t_planned_halt_8df3b)) {
            var haltText by remember(plan?.plannedHalt) { mutableStateOf(plan?.plannedHalt.orEmpty()) }
            OutlinedTextField(
                value = haltText,
                onValueChange = { haltText = it.take(60) },
                label = { Text(stringResource(R.string.t_where_you_plan_to_halt_e_g_salem_3296d)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.weight(1f)) {
                    SecondaryButton(
                        if (plan?.plannedHalt == null) "Plan this halt" else "Update halt",
                        { onPlannedHalt(haltText.trim().ifBlank { null }) },
                        height = 44.dp
                    )
                }
                if (plan?.plannedHalt != null) {
                    Box(Modifier.weight(1f)) {
                        SecondaryButton(stringResource(R.string.t_remove_e9639), { haltText = ""; onPlannedHalt(null) }, accent = colors.textMid, height = 44.dp)
                    }
                }
            }
        }

        // Every version is kept: the plan's history is part of the journey.
        if (planHistory.size > 1) {
            KoodeCard(title = stringResource(R.string.t_plan_history_f5fdf)) {
                planHistory.sortedByDescending { it.version }.forEach { v ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            "v${v.version}",
                            color = if (v.version == plan?.version) colors.accent else colors.textLow,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.width(36.dp)
                        )
                        Text(
                            buildString {
                                append("→ ${v.destination} · ${com.trippulse.app.domain.JourneyPlans.modeWord(v.mode)}")
                                v.plannedHalt?.let { append(", halt $it") }
                            },
                            color = if (v.version == plan?.version) colors.textHigh else colors.textMid,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        if (legs.isNotEmpty()) {
            KoodeCard(title = stringResource(R.string.t_stages_so_far_a6fb6)) {
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
                            done -> StatusPill(stringResource(R.string.t_done_e9b45), colors.textLow)
                            active -> TextButton(onClick = {
                                correcting = true
                                mode = leg.mode
                                details = LegDetails.fromJson(leg.detailsJson)
                            }) { Text(stringResource(R.string.t_correct_details_1d31f), color = colors.accent, fontSize = 13.sp) }
                            else -> StatusPill(stringResource(R.string.t_planned_9cbe4), colors.textMid)
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
                        KoodeChip(com.trippulse.app.ui.Names.mode(p.key), mode == p.key, { onModeChosen(p.key) }, leading = p.emoji)
                    }
                }
                Spacer(Modifier.height(Spacing.md))
            }

            TravelDetailFields(
                mode = mode,
                values = details,
                onChange = { key, value -> details = details + (key to value) }
            )

            // Leaving your own vehicle part-way may be a plan (car to the
            // station, then train) or something going wrong; if it is the
            // latter, the timeline should say so.
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
                    "Leave this unticked if you're simply continuing another way.",
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
        SecondaryButton(stringResource(R.string.t_close_bbfa7), onClose)
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
    var type by remember {
        mutableStateOf(
            if (profile.asksAboutFuel) "FUEL"
            else (com.trippulse.app.domain.Expenses.Category.fareFor(profile.key)?.name ?: "OTHER")
        )
    }
    var item by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("L") }

    val valid = InputRules.isValidExpense(item, amount)

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(stringResource(R.string.t_add_an_expense_52852), color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Private to you — it stays on this phone and nobody following you ever sees it.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        // Only the categories this way of travelling makes relevant.
        val cats = remember(profile.key) {
            if (profile.isPrivateVehicle) listOf(ExpenseCategory.FUEL, ExpenseCategory.TOLL, ExpenseCategory.PARKING, ExpenseCategory.FOOD, ExpenseCategory.ACCOMMODATION, ExpenseCategory.VEHICLE_REPAIR, ExpenseCategory.OTHER)
            else listOfNotNull(ExpenseCategory.fareFor(profile.key), ExpenseCategory.FOOD, ExpenseCategory.CAB, ExpenseCategory.AUTO, ExpenseCategory.ACCOMMODATION, ExpenseCategory.OTHER).distinct()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            cats.forEach { c -> KoodeChip(c.label, type == c.name, { type = c.name }, leading = c.emoji) }
        }
        OutlinedTextField(
            value = item,
            onValueChange = { item = InputRules.itemText(it) },
            label = { Text(stringResource(R.string.t_item_what_was_it_37426)) },
            placeholder = { Text(stringResource(R.string.t_highway_dhaba_lunch_c1328)) },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.t_letters_only_fff61), color = colors.textLow, fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = InputRules.amountText(it) },
            label = { Text(stringResource(R.string.t_amount_43dc8)) },
            placeholder = { Text(stringResource(R.string.t_450_d96ad)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text(stringResource(R.string.t_numbers_only_eb324), color = colors.textLow, fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth()
        )
        if (type == "FUEL") {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = qty,
                    onValueChange = { qty = InputRules.quantityText(it) },
                    label = { Text(stringResource(R.string.t_quantity_44f6a)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                KoodeChip(stringResource(R.string.t_litres_92517), unit == "L", { unit = "L" })
                KoodeChip(stringResource(R.string.t_kwh_72c28), unit == "kWh", { unit = "kWh" })
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
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(stringResource(R.string.t_add_a_note_f9ee6), color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
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
                label = { Text(stringResource(R.string.t_anything_else_ead90)) }, modifier = Modifier.fillMaxWidth()
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
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CheckpointSheet(
    profile: TransportProfile,
    fuelUnit: String,
    onSubmit: (TripManager.Checkpoint, Double?, Double?, String, Long?, Long?) -> Unit,
    onSkip: () -> Unit,
    /** True when stop detection already knows when this break began and ended. */
    autoTimed: Boolean = false
) {
    // Timing. "Detected" means stop detection's own start and end are used;
    // it is the default only when there is a detected stop to use, and any
    // tap on a time replaces it -- the traveller's word always wins.
    var useDetected by remember { mutableStateOf(autoTimed) }
    var startedAgoMin by remember { mutableStateOf(0) }
    var lastedMin by remember { mutableStateOf<Int?>(null) }
    var pickedStartMs by remember { mutableStateOf<Long?>(null) }
    var pickingTime by remember { mutableStateOf(false) }
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

    // The questions scroll; Save and Not now never do. On a phone, choosing
    // Food and a time pushed Save off the bottom of the sheet, which read as
    // "there is no Save". The footer is pinned so it cannot happen again.
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.xl),
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
                KoodeChip(stringResource(R.string.t_water_de9b1), water, { water = !water }, leading = "💧")
                KoodeChip(stringResource(R.string.t_food_35b25), food, { food = !food }, leading = "🍛")
                KoodeChip(stringResource(R.string.t_tea_coffee_e3814), tea, { tea = !tea }, leading = "☕")
                KoodeChip(stringResource(R.string.t_snack_071a0), snack, { snack = !snack }, leading = "🍪")
                KoodeChip(stringResource(R.string.t_toilet_0a527), toilet, { toilet = !toilet }, leading = "🚻")
                KoodeChip(stringResource(R.string.t_rest_b79e5), rest, { rest = !rest }, leading = "😴")
                // Fuel questions exist only for private vehicles.
                if (profile.asksAboutFuel) {
                    if (fuelUnit == "kWh") KoodeChip(stringResource(R.string.t_charged_deb41), charge, { charge = !charge }, leading = "🔌")
                    else KoodeChip(stringResource(R.string.t_refuelled_4dec1), fuel, { fuel = !fuel }, leading = "⛽")
                }
            }

            // Koode names the meal from the clock; this row exists only for the
            // times it guesses wrong, or the traveller wants to be explicit.
            if (food) {
                Text(stringResource(R.string.t_which_meal_a5e8e), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    KoodeChip(stringResource(R.string.t_let_koode_decide_c4d43), mealKind == null, { mealKind = null })
                    listOf(Nourishment.BREAKFAST, Nourishment.LUNCH, Nourishment.DINNER, Nourishment.SNACK).forEach { m ->
                        KoodeChip(m.label, mealKind == m, { mealKind = m }, leading = m.emoji)
                    }
                }
            }

            if (profile.asksAboutFuel && (fuel || charge)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = refuelCost, onValueChange = { refuelCost = InputRules.amountText(it) },
                        label = { Text(stringResource(R.string.t_amount_43dc8)) }, singleLine = true,
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

            Spacer(Modifier.height(Spacing.sm))
            Text(stringResource(R.string.t_when_did_it_start_0d5bc), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (autoTimed) KoodeChip(stringResource(R.string.t_when_i_stopped_7cd0d), useDetected, { useDetected = true; pickedStartMs = null }, leading = "📍")
                // Reaches back a whole evening: a dinner logged after the night's
                // halt is still logged at dinner time.
                listOf(0 to "Just now", 10 to "10 min ago", 20 to "20 min ago", 30 to "30 min ago", 45 to "45 min ago",
                    60 to "1 h ago", 120 to "2 h ago", 180 to "3 h ago", 240 to "4 h ago", 360 to "6 h ago")
                    .forEach { (m, label) ->
                        KoodeChip(label, !useDetected && pickedStartMs == null && startedAgoMin == m,
                            { useDetected = false; pickedStartMs = null; startedAgoMin = m })
                    }
                KoodeChip(
                    pickedStartMs?.let { "At " + TimeFmt.clock(it) } ?: "Pick a time",
                    pickedStartMs != null, { pickingTime = true }, leading = "🕗"
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(stringResource(R.string.t_how_long_was_it_5f98d), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                val justNow = !useDetected && pickedStartMs == null && startedAgoMin == 0
                KoodeChip(
                    when { useDetected -> "Until I moved off"; justNow -> "Still on it"; else -> "Until now" },
                    lastedMin == null, { lastedMin = null }
                )
                listOf(5, 10, 15, 20, 30, 45, 60, 90, 120).forEach { m ->
                    KoodeChip(if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "1½ h", lastedMin == m, { lastedMin = m; useDetected = false })
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }

        // ---- pinned footer ----
        Column(
            Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            PrimaryButton(
                "Save",
                {
                    val nowMs = System.currentTimeMillis()
                    val lasted = lastedMin
                    val picked = pickedStartMs
                    val startAt: Long? = when {
                        useDetected -> null
                        picked != null -> picked
                        startedAgoMin == 0 && lasted == null -> null
                        lasted == null -> nowMs - startedAgoMin * 60_000L
                        else -> nowMs - (if (startedAgoMin == 0) lasted else startedAgoMin) * 60_000L
                    }
                    val lastedS: Long? = when {
                        useDetected -> null
                        lasted != null -> lasted * 60L
                        picked != null -> ((nowMs - picked) / 1000L).coerceAtLeast(60L)
                        startedAgoMin > 0 -> startedAgoMin * 60L
                        else -> null
                    }
                    onSubmit(
                        TripManager.Checkpoint(
                            water = water, food = food, toilet = toilet, rest = rest,
                            fuel = fuel, charge = charge, tea = tea, snack = snack,
                            mealKind = mealKind
                        ),
                        InputRules.parseAmount(refuelCost), refuelQty.toDoubleOrNull(), fuelUnit,
                        startAt, lastedS
                    )
                },
                enabled = water || food || tea || snack || toilet || rest || fuel || charge,
                height = 48.dp
            )
            SecondaryButton(stringResource(R.string.t_not_now_e4571), onSkip, accent = colors.textMid, height = 44.dp)
        }
    }

    // An exact clock time, for the traveller who knows dinner was at 9:10.
    // A time later than now means yesterday.
    if (pickingTime) {
        val cal = remember { java.util.Calendar.getInstance() }
        val state = rememberTimePickerState(
            initialHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(java.util.Calendar.MINUTE),
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text(stringResource(R.string.t_when_did_it_start_0d5bc)) },
            text = { TimeInput(state = state) },
            confirmButton = {
                TextButton({
                    val c = java.util.Calendar.getInstance()
                    c.set(java.util.Calendar.HOUR_OF_DAY, state.hour)
                    c.set(java.util.Calendar.MINUTE, state.minute)
                    c.set(java.util.Calendar.SECOND, 0)
                    c.set(java.util.Calendar.MILLISECOND, 0)
                    if (c.timeInMillis > System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_YEAR, -1)
                    pickedStartMs = c.timeInMillis
                    useDetected = false
                    pickingTime = false
                }) { Text(stringResource(R.string.t_use_this_time_70a50)) }
            },
            dismissButton = { TextButton({ pickingTime = false }) { Text(stringResource(R.string.t_cancel_77dfd)) } }
        )
    }
}

/**
 * Every expense on this journey, with the two corrections that matter in the
 * field: a wrong amount, and an entry that should not be there. Private to
 * the traveller; nothing here is shared.
 */
@Composable
private fun SpendingSheet(
    rows: List<com.trippulse.app.data.local.ExpenseEntity>,
    money: (Double) -> String,
    onCorrect: (Long, Double) -> Unit,
    onDelete: (Long) -> Unit,
    onAdd: () -> Unit
) {
    val colors = KoodeTheme.colors
    var editingId by remember { mutableStateOf<Long?>(null) }
    var amountText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(stringResource(R.string.t_trip_spending_dbc63), color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${money(rows.sumOf { it.amount })} across ${rows.size} ${if (rows.size == 1) "entry" else "entries"}. Private to you.",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(Spacing.xs))
            rows.sortedByDescending { it.tMs }.forEach { e ->
                val cat = ExpenseCategory.fromType(e.type)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.md))
                        .background(colors.backgroundElevated)
                        .padding(Spacing.md)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cat.emoji, fontSize = 18.sp)
                        Spacer(Modifier.width(Spacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.item.ifBlank { cat.label },
                                color = colors.textHigh, style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                buildList {
                                    add(cat.label)
                                    e.quantity?.let { q ->
                                        add((if (q % 1.0 == 0.0) q.toLong().toString() else "%.1f".format(q)) + " " + e.unit.orEmpty())
                                    }
                                    add(TimeFmt.clockWithDay(e.tMs, System.currentTimeMillis()))
                                    e.note?.takeIf { it.isNotBlank() }?.let { add(it) }
                                }.joinToString(" · "),
                                color = colors.textLow, style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(money(e.amount), color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
                    }
                    if (editingId == e.id) {
                        Spacer(Modifier.height(Spacing.sm))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            OutlinedTextField(
                                value = amountText, onValueChange = { amountText = InputRules.amountText(it) },
                                label = { Text(stringResource(R.string.t_amount_43dc8)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { InputRules.parseAmount(amountText)?.let { onCorrect(e.id, it) }; editingId = null },
                                enabled = InputRules.parseAmount(amountText) != null
                            ) { Text(stringResource(R.string.t_save_efc00), color = colors.accent) }
                            TextButton(onClick = { editingId = null }) { Text(stringResource(R.string.t_cancel_77dfd), color = colors.textMid) }
                        }
                    } else if (confirmDelete == e.id) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            TextButton(onClick = { onDelete(e.id); confirmDelete = null }) { Text(stringResource(R.string.t_remove_this_entry_b9aca), color = colors.danger) }
                            TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.t_keep_466fc), color = colors.textMid) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            TextButton(onClick = { editingId = e.id; amountText = InputRules.amountText(e.amount.toString()) }) {
                                Text(stringResource(R.string.t_correct_amount_4634a), color = colors.accent)
                            }
                            TextButton(onClick = { confirmDelete = e.id }) { Text(stringResource(R.string.t_remove_e9639), color = colors.textMid) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
        Column(
            Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            PrimaryButton(stringResource(R.string.t_add_an_expense_52852), onAdd, height = 46.dp)
        }
    }
}

/**
 * Correcting one timeline entry the traveller wrote: when it happened and, for
 * a break, how long it lasted; or taking it off the timeline altogether.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EditEntrySheet(
    item: TimelineItem,
    onSave: (Long?, Long?) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = KoodeTheme.colors
    val isBreak = item.breakId != null
    var atMs by remember { mutableStateOf<Long?>(null) }
    var durationMin by remember { mutableStateOf<Int?>(null) }
    var pickingTime by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val currentDurationMin = (item.payload["durationS"] as? Number)?.toLong()?.let { (it / 60).toInt() }

    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(stringResource(R.string.t_correct_this_entry_f7815), color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
            Text(item.label, color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Currently at ${TimeFmt.clockWithDay(item.timeMs, System.currentTimeMillis())}. The people following you see the corrected line.",
                color = colors.textLow, style = MaterialTheme.typography.bodySmall
            )

            Text(if (isBreak) "When did it start?" else "When did it happen?", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                KoodeChip("Keep ${TimeFmt.clock(item.timeMs)}", atMs == null, { atMs = null })
                listOf(15, 30, 60, 120, 180, 360).forEach { m ->
                    val candidate = item.timeMs - m * 60_000L
                    KoodeChip("${if (m < 60) "$m min" else "${m / 60} h"} earlier", atMs == candidate, { atMs = candidate })
                }
                KoodeChip(
                    atMs?.takeIf { a -> listOf(15, 30, 60, 120, 180, 360).none { item.timeMs - it * 60_000L == a } }
                        ?.let { "At " + TimeFmt.clock(it) } ?: "Pick a time",
                    false, { pickingTime = true }, leading = "🕗"
                )
            }

            if (isBreak) {
                Text(stringResource(R.string.t_how_long_was_it_5f98d), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    KoodeChip(
                        currentDurationMin?.let { "Keep $it min" } ?: "As recorded", durationMin == null, { durationMin = null }
                    )
                    listOf(5, 10, 15, 20, 30, 45, 60, 90, 120).forEach { m ->
                        KoodeChip(if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "1½ h", durationMin == m, { durationMin = m })
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
        Column(
            Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            PrimaryButton(
                "Save correction",
                { onSave(atMs, durationMin?.let { it * 60L }) },
                enabled = atMs != null || durationMin != null, height = 48.dp
            )
            if (confirmRemove) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.weight(1f)) { SecondaryButton(stringResource(R.string.t_yes_remove_it_5289f), onRemove, accent = colors.danger, height = 44.dp) }
                    Box(Modifier.weight(1f)) { SecondaryButton(stringResource(R.string.t_keep_it_d9f36), { confirmRemove = false }, accent = colors.textMid, height = 44.dp) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.weight(1f)) { SecondaryButton(stringResource(R.string.t_remove_from_timeline_c2826), { confirmRemove = true }, accent = colors.danger, height = 44.dp) }
                    Box(Modifier.weight(1f)) { SecondaryButton(stringResource(R.string.t_cancel_77dfd), onCancel, accent = colors.textMid, height = 44.dp) }
                }
            }
        }
    }

    if (pickingTime) {
        val cal = remember { java.util.Calendar.getInstance().apply { timeInMillis = item.timeMs } }
        val state = rememberTimePickerState(
            initialHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(java.util.Calendar.MINUTE),
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text(if (isBreak) "When did it start?" else "When did it happen?") },
            text = { TimeInput(state = state) },
            confirmButton = {
                TextButton({
                    val c = java.util.Calendar.getInstance()
                    c.set(java.util.Calendar.HOUR_OF_DAY, state.hour)
                    c.set(java.util.Calendar.MINUTE, state.minute)
                    c.set(java.util.Calendar.SECOND, 0)
                    c.set(java.util.Calendar.MILLISECOND, 0)
                    if (c.timeInMillis > System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_YEAR, -1)
                    atMs = c.timeInMillis
                    pickingTime = false
                }) { Text(stringResource(R.string.t_use_this_time_70a50)) }
            },
            dismissButton = { TextButton({ pickingTime = false }) { Text(stringResource(R.string.t_cancel_77dfd)) } }
        )
    }
}

/**
 * "Taking a longer break?" Asked, never assumed: a long stop may be a meal, a
 * room, family, or nothing at all. The traveller picks what kind of halt and,
 * optionally, how long; the button then says exactly what is confirmed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HaltDialog(
    askFirst: Boolean,
    stoppedForMin: Long?,
    /** Type, expected length in minutes, and when the halt began (null = now). */
    onConfirm: (Halts.Type, Int?, Long?) -> Unit,
    onDecline: () -> Unit
) {
    val colors = KoodeTheme.colors
    var type by remember { mutableStateOf<Halts.Type?>(null) }
    var duration by remember { mutableStateOf<Halts.Duration?>(null) }
    var sinceMin by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = { if (!askFirst) onDecline() },
        confirmButton = {},
        title = {
            Column {
                stoppedForMin?.takeIf { it >= 1 }?.let {
                    Text(
                        "STOPPED ${WellbeingCoach.duration(it).uppercase()}",
                        color = colors.warn, style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(if (askFirst) "Taking a longer break?" else "Taking a halt?")
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    if (askFirst) "Looks like you've stopped for a while. Are you taking a halt?"
                    else "A room, family, or a proper rest: the people following you will see you've halted.",
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Halts.Type.entries.forEach { t ->
                        KoodeChip(t.label, type == t, { type = t }, leading = t.emoji)
                    }
                }
                // Asked only when the traveller opened this themselves: a halt
                // logged in the morning still began the night before.
                if (!askFirst) {
                    Text(stringResource(R.string.t_since_when_3fbbd), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        listOf(0 to "Just now", 30 to "30 min ago", 60 to "1 h ago", 120 to "2 h ago",
                            180 to "3 h ago", 360 to "6 h ago", 600 to "10 h ago")
                            .forEach { (m, label) -> KoodeChip(label, sinceMin == m, { sinceMin = m }) }
                    }
                }
                Text(stringResource(R.string.t_for_about_optional_93471), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Halts.Duration.entries.forEach { d ->
                        KoodeChip(d.label, duration == d, { duration = if (duration == d) null else d })
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                PrimaryButton(
                    Halts.confirmLabel(duration),
                    {
                        type?.let {
                            onConfirm(it, duration?.minutes, if (sinceMin > 0) System.currentTimeMillis() - sinceMin * 60_000L else null)
                        }
                    },
                    enabled = type != null, height = 46.dp
                )
                if (askFirst) {
                    SecondaryButton(stringResource(R.string.t_just_a_long_break_dc30f), onDecline, height = 44.dp)
                    SecondaryButton(stringResource(R.string.t_not_stopped_yet_f7943), onDecline, accent = colors.textMid, height = 44.dp)
                } else {
                    SecondaryButton(stringResource(R.string.t_not_now_e4571), onDecline, accent = colors.textMid, height = 44.dp)
                }
                Text(
                    "The people following this journey are told only if you confirm a halt.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }
        }
    )
}

private data class CoachTile(val label: String, val value: String, val caption: String, val art: Int? = null)

/**
 * "How you're doing" as three facts: time since water, since food, and — for
 * someone at the wheel — driving without a recorded break. Only what was
 * recorded; a gap is "not yet", never a guess.
 */
private fun coachTiles(
    s: com.trippulse.app.data.local.TripStateEntity?, startedAtMs: Long?, plan: JourneyPlan?,
    modeKey: String, now: Long
): List<CoachTile> {
    fun since(at: Long?): String? = startedAtMs?.let { start ->
        at?.takeIf { it >= start }?.let { WellbeingCoach.duration((now - it) / 60_000) }
    }
    val role = WellbeingCoach.Role.fromKey(plan?.role) ?: WellbeingCoach.defaultRole(modeKey)
    val rules = WellbeingCoach.rulesFor(modeKey, role)
    val atWheel = rules != null && rules.breakKind != WellbeingCoach.BreakKind.STRETCH
    return buildList {
        val water = com.trippulse.app.ui.components.KoodeArt.file(com.trippulse.app.domain.Pictures.WATER)
        val food = com.trippulse.app.ui.components.KoodeArt.file(com.trippulse.app.domain.Pictures.FOOD)
        val rest = com.trippulse.app.ui.components.KoodeArt.file(com.trippulse.app.domain.Pictures.REST)
        add(since(s?.waterAtMs)?.let { CoachTile("WATER", it, "since last drink", water) } ?: CoachTile("WATER", "—", "none recorded yet", water))
        add(since(s?.foodAtMs)?.let { CoachTile("FOOD", it, "since you ate", food) } ?: CoachTile("FOOD", "—", "none recorded yet", food))
        if (atWheel) {
            val verb = if (rules?.breakKind == WellbeingCoach.BreakKind.RIDING) "RIDING" else "DRIVING"
            val moving = s?.journey == JourneyStatus.DRIVING.name
            val cont = s?.drivingSinceMs?.takeIf { moving }?.let { WellbeingCoach.duration((now - it) / 60_000) }
            add(cont?.let { CoachTile(verb, it, "without a recorded break", rest) } ?: CoachTile(verb, "—", "stopped now", rest))
        }
    }
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
    JourneyStatus.OVERNIGHT.name -> "Halting"
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
