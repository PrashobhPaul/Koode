package com.trippulse.app.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.core.TripCredentials
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.ui.CreateVm
import com.trippulse.app.ui.LegDraft
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.SharedPlaceInbox
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.BackButton
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.KoodeChip
import com.trippulse.app.ui.components.LocalWindowClass
import com.trippulse.app.ui.components.PlacePicker
import com.trippulse.app.ui.components.openGoogleMaps
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.SecondaryButton
import com.trippulse.app.ui.components.TravelDetailFields
import com.trippulse.app.ui.map.JourneyMap
import com.trippulse.app.ui.map.Vehicle3D
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing
import com.trippulse.app.core.InputRules

/**
 * Planning a journey.
 *
 * A journey is a *list of stages*, each with its own mode, because real
 * journeys are hybrid (train to Bangalore, bus onward). Every place field is
 * tappable and opens one picker for exactly that field: search as you type,
 * paste or share from Google Maps, use current location, a saved place, or
 * drop a pin. The map below previews the stage being edited in 3D.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateTripScreen(nav: NavHostController, scheduleLater: Boolean = false) {
    val vm: CreateVm = viewModel(factory = CreateVm.Factory)
    val colors = KoodeTheme.colors
    val windowClass = LocalWindowClass.current
    val context = LocalContext.current

    val legs by vm.legs.collectAsStateWithLifecycle()
    val editing by vm.editingLeg.collectAsStateWithLifecycle()
    val passcode by vm.passcode.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val running by vm.runningTripId.collectAsStateWithLifecycle()
    val saved by vm.savedPlaces.collectAsStateWithLifecycle()
    val suggestions by vm.suggestedPlaces.collectAsStateWithLifecycle()
    val departure by vm.departureMs.collectAsStateWithLifecycle()
    val myName by vm.myName.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    // "Schedule for later" from Home/Journeys lands with a departure already
    // an hour out, which the traveller then adjusts.
    androidx.compose.runtime.LaunchedEffect(scheduleLater) {
        if (scheduleLater && vm.departureMs.value == null) vm.departureMs.value = System.currentTimeMillis() + 3_600_000L
    }
    val here by vm.here.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

    var picker by remember { mutableStateOf<SharedPlaceInbox.Target?>(null) }
    var customWhen by remember { mutableStateOf("") }

    // "Current location" as a start needs the permission up front.
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshHere() }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // A place shared back from Google Maps fills the field that asked for it.
    val shared by SharedPlaceInbox.pending.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        // An open picker takes shares for its own field; this handles the rest.
        if (SharedPlaceInbox.pickerOpen) return@LaunchedEffect
        val (text, where) = SharedPlaceInbox.take() ?: return@LaunchedEffect
        picker = null
        vm.applySharedText(text, where.legIndex.coerceIn(0, vm.legs.value.lastIndex), where.asStart)
    }

    val editingLeg = legs.getOrElse(editing) { legs.first() }

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
            Text("Plan a journey", color = colors.textHigh, style = MaterialTheme.typography.displaySmall)
            Text(
                "Tap a place to set it. Add a stage whenever you change vehicle.",
                color = colors.textMid, style = MaterialTheme.typography.bodyLarge
            )

            AnimatedVisibility(visible = notice != null) {
                KoodeCard(accent = colors.accent, onClick = { vm.notice.value = null }) {
                    Text(notice.orEmpty(), color = colors.textHigh, style = MaterialTheme.typography.bodyMedium)
                    Text("Tap to dismiss", color = colors.textLow, style = MaterialTheme.typography.bodySmall)
                }
            }

            OutlinedTextField(
                value = myName,
                onValueChange = { vm.myName.value = it },
                label = { Text("Your name — followers see \u201C…'s journey\u201D") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ---- stages ----
            legs.forEachIndexed { index, leg ->
                StageCard(
                    index = index,
                    total = legs.size,
                    leg = leg,
                    isEditing = index == editing,
                    onFocus = { vm.editLeg(index) },
                    onRemove = { vm.removeLeg(index) },
                    onModeChange = { vm.setMode(index, it) },
                    onDetailChange = { key, value -> vm.setDetail(index, key, value) },
                    onPickFrom = { vm.editLeg(index); picker = SharedPlaceInbox.Target(index, asStart = true) },
                    onPickTo = { vm.editLeg(index); picker = SharedPlaceInbox.Target(index, asStart = false) },
                    onBoardingChange = { vm.setBoardingPoint(index, it) }
                )
            }

            SecondaryButton("Add another stage", { vm.addLeg() }, leading = "＋", accent = colors.traveller, height = 44.dp)

            // ---- live preview of the stage being edited ----
            if (editingLeg.from != null || editingLeg.to != null) {
                val from = editingLeg.from
                val to = editingLeg.to
                JourneyMap(
                    origin = from,
                    destination = to,
                    current = from,
                    bearingDeg = if (from != null && to != null) Vehicle3D.bearing(from, to).toFloat() else null,
                    moving = from != null && to != null,
                    mode = editingLeg.mode,
                    live = false,
                    height = windowClass.mapHeight,
                    showPlayControl = false
                )
                Text(
                    "Preview of ${if (legs.size > 1) "stage ${editing + 1}" else "your route"} — " +
                        "the straight line is only a sketch; the real path is drawn as you travel.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }

            // ---- passcode ----
            KoodeCard(title = "Passcode for followers") {
                Text(
                    "Six digits. Anyone with your journey number AND this passcode goes straight in — " +
                        "everyone else waits for your approval.",
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = passcode,
                        onValueChange = { vm.setPasscode(it) },
                        label = { Text("${TripCredentials.PASSCODE_LENGTH} digits") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Box(Modifier.width(110.dp)) {
                        SecondaryButton("Suggest", { vm.regeneratePasscode() }, height = 48.dp)
                    }
                }
            }

            // ---- departure ----
            KoodeCard(title = "Departure") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    KoodeChip("Now", departure == null, { vm.departureMs.value = null; customWhen = "" })
                    KoodeChip("In an hour", false, {
                        vm.departureMs.value = System.currentTimeMillis() + 3_600_000L; customWhen = ""
                    })
                    KoodeChip("Tomorrow 6 AM", false, {
                        val cal = java.util.Calendar.getInstance().apply {
                            add(java.util.Calendar.DAY_OF_YEAR, 1)
                            set(java.util.Calendar.HOUR_OF_DAY, 6); set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                        }
                        vm.departureMs.value = cal.timeInMillis; customWhen = ""
                    })
                }
                Spacer(Modifier.height(Spacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customWhen,
                        onValueChange = { customWhen = it },
                        label = { Text("Or yyyy-MM-dd HH:mm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Box(Modifier.width(90.dp)) {
                        SecondaryButton("Set", {
                            val parsed = runCatching {
                                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                                    .parse(customWhen.trim())?.time
                            }.getOrNull()
                            if (parsed != null && parsed > System.currentTimeMillis()) vm.departureMs.value = parsed
                        }, height = 48.dp)
                    }
                }
                if (departure != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "Scheduled for ${TimeFmt.clockWithDay(departure!!, System.currentTimeMillis())} — " +
                            "you'll get a reminder 30 minutes before.",
                        color = colors.accent, style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ---- emergency contact ----
            KoodeCard(title = "Emergency contact (optional)") {
                val emName by vm.emergencyName.collectAsStateWithLifecycle()
                val emPhone by vm.emergencyPhone.collectAsStateWithLifecycle()
                OutlinedTextField(
                    value = emName, onValueChange = { vm.emergencyName.value = InputRules.itemText(it) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = emPhone, onValueChange = { vm.emergencyPhone.value = InputRules.phoneText(it) },
                    label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
            }

            if (error != null) {
                KoodeCard(accent = colors.danger) {
                    Text(error!!, color = colors.danger, style = MaterialTheme.typography.bodyMedium)
                    // When the refusal was "you're already on one", the useful
                    // next step is the journey they are on, not a retry.
                    running?.let { tripId ->
                        Spacer(Modifier.height(Spacing.md))
                        PrimaryButton("Open my journey", { nav.navigate(Routes.driver(tripId)) { popUpTo(Routes.HOME) } })
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xs))
            if (busy) {
                Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = colors.accent)
                }
            } else {
                PrimaryButton(
                    "Create journey",
                    { vm.create { tripId -> nav.navigate(Routes.credentials(tripId)) { popUpTo(Routes.HOME) } } },
                    leading = "🧭"
                )
            }
            SecondaryButton("Cancel", { nav.popBackStack() }, accent = colors.textMid, height = 44.dp)
            Spacer(Modifier.height(Spacing.scrollBottom))
        }
    }

    picker?.let { target ->
        val leg = legs.getOrElse(target.legIndex) { legs.first() }
        PlacePicker(
            asStart = target.asStart,
            results = results,
            searching = searching,
            saved = saved,
            recent = suggestions,
            here = here,
            pinStart = (if (target.asStart) leg.from else leg.to) ?: leg.from ?: here,
            onQuery = { vm.searchPlaces(it) },
            onPick = { place -> vm.applyPlace(target.legIndex, target.asStart, place); picker = null },
            offerCurrentLocation = target.asStart,
            onLocated = { vm.here.value = it },
            onSharedText = { text -> picker = null; vm.applySharedText(text, target.legIndex, target.asStart) },
            onOpenGoogleMaps = { query ->
                SharedPlaceInbox.target = target
                openGoogleMaps(context, query)
            },
            onSavePlace = { name, point -> vm.savePlaceAt(name, point) },
            onDeleteSaved = { vm.deletePlace(it) },
            onDismiss = { picker = null; vm.clearSearch() }
        )
    }
}


/**
 * One stage. Collapsed unless it's the one being edited, so a three-stage
 * journey doesn't become a wall of fields.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageCard(
    index: Int,
    total: Int,
    leg: LegDraft,
    isEditing: Boolean,
    onFocus: () -> Unit,
    onRemove: () -> Unit,
    onModeChange: (String) -> Unit,
    onDetailChange: (String, String) -> Unit,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onBoardingChange: (String) -> Unit
) {
    val colors = KoodeTheme.colors
    val profile = leg.profile

    KoodeCard(accent = if (isEditing) colors.accent else null, onClick = if (isEditing) null else onFocus) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(Radii.pill)).background(colors.surfaceRaised),
                contentAlignment = Alignment.Center
            ) { com.trippulse.app.ui.components.ModeArt(profile.key, 28.dp) }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                if (total == 1) "Your journey" else "Stage ${index + 1} · ${profile.label}",
                color = colors.textHigh, style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.weight(1f))
            if (total > 1) TextButton(onClick = onRemove) { Text("Remove", color = colors.textLow, fontSize = 12.sp) }
        }

        if (!isEditing) {
            Text(
                "${leg.fromText.ifBlank { "…" }} → ${leg.toText.ifBlank { "…" }}",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
            return@KoodeCard
        }

        Spacer(Modifier.height(Spacing.md))
        RouteFields(leg = leg, onPickFrom = onPickFrom, onPickTo = onPickTo)

        Spacer(Modifier.height(Spacing.lg))
        Text("HOW ARE YOU TRAVELLING?", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(Spacing.sm))
        // FlowRow, not a horizontal scroll: every mode (train, flight, metro,
        // ship…) must be visible at once, not hidden off the right edge.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TransportCatalog.ALL.forEach { p ->
                ModeTile(p.key, p.label, leg.mode == p.key) { onModeChange(p.key) }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        // Rendered from the mode's own declaration, so this screen and the
        // mid-journey switch always ask the same questions in the same words.
        var changeVehicle by remember(leg.mode) { mutableStateOf(false) }
        if (profile.isPrivateVehicle && leg.ready && !changeVehicle) {
            // A remembered car or bike: one line, no questions.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.md)).background(colors.surfaceRaised)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.trippulse.app.ui.components.ModeArt(profile.key, 32.dp)
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text("Your ${profile.label.lowercase()}", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                    Text(
                        com.trippulse.app.domain.TravelDetails.summary(leg.mode, leg.details).ifBlank { "Saved" },
                        color = colors.textHigh, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { changeVehicle = true }) { Text("Change", color = colors.traveller) }
            }
        } else {
            TravelDetailFields(mode = leg.mode, values = leg.details, onChange = onDetailChange)
        }

        if (!profile.isPrivateVehicle) {
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = leg.boardingPoint, onValueChange = onBoardingChange,
                label = { Text(profile.boardingPointLabel + " (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Kept on this phone only — never shared with anyone following you.",
                color = colors.textLow, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** From and To as one connected route, each row opening the picker for itself. */
@Composable
private fun RouteFields(leg: LegDraft, onPickFrom: () -> Unit, onPickTo: () -> Unit) {
    val colors = KoodeTheme.colors
    val shape = RoundedCornerShape(Radii.md)
    val fromSet = leg.from != null || leg.fromText.equals("Current location", ignoreCase = true)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.backgroundElevated)
            .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
    ) {
        PlaceFieldRow(
            marker = { Box(Modifier.size(12.dp).clip(RoundedCornerShape(Radii.pill)).background(colors.accent)) },
            label = "FROM",
            value = leg.fromText.ifBlank { null },
            placeholder = "Choose a starting point",
            confirmed = fromSet,
            onClick = onPickFrom
        )
        Row(Modifier.padding(start = 21.dp)) {
            Box(Modifier.width(2.dp).height(14.dp).background(colors.outline))
        }
        PlaceFieldRow(
            marker = { Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(colors.warn)) },
            label = "TO",
            value = leg.toText.ifBlank { null },
            placeholder = "Where are you going?",
            confirmed = leg.to != null,
            onClick = onPickTo
        )
    }
}

@Composable
private fun PlaceFieldRow(
    marker: @Composable () -> Unit,
    label: String,
    value: String?,
    placeholder: String,
    confirmed: Boolean,
    onClick: () -> Unit
) {
    val colors = KoodeTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) { marker() }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.textLow, style = MaterialTheme.typography.labelSmall)
            Text(
                value ?: placeholder,
                color = if (value != null) colors.textHigh else colors.textLow,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (value != null) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            when {
                value == null -> "Set"
                confirmed -> "✓"
                else -> "Find"
            },
            color = if (confirmed) colors.accent else colors.traveller,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun ModeTile(mode: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = KoodeTheme.colors
    val shape = RoundedCornerShape(Radii.md)
    Column(
        Modifier
            .width(84.dp)
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.16f) else colors.surfaceRaised)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        com.trippulse.app.ui.components.ModeArt(mode, 52.dp)
        Spacer(Modifier.height(4.dp))
        Text(
            label, color = if (selected) colors.textHigh else colors.textMid,
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
