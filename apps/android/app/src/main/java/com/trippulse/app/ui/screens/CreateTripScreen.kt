package com.trippulse.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.core.InputRules
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.core.TripCredentials
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.ui.CreateVm
import com.trippulse.app.ui.LegDraft
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.components.TravelDetailFields
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.KoodeChip
import com.trippulse.app.ui.components.LocalWindowClass
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.SecondaryButton
import com.trippulse.app.ui.map.JourneyMap
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Spacing

/**
 * Planning a journey.
 *
 * The important structural change here is that a journey is a *list of legs*,
 * not a single from/to with one mode. Real journeys are hybrid — Thrissur to
 * Bangalore by train, then Bangalore to Hyderabad by bus — and modelling that
 * as the normal case (rather than a special one) is what lets every downstream
 * rule stay simple: each leg carries its own mode, and the app switches rule
 * sets automatically when the traveller changes vehicle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateTripScreen(nav: NavHostController) {
    val vm: CreateVm = viewModel(factory = CreateVm.Factory)
    val colors = KoodeTheme.colors
    val windowClass = LocalWindowClass.current

    val legs by vm.legs.collectAsStateWithLifecycle()
    val editing by vm.editingLeg.collectAsStateWithLifecycle()
    val passcode by vm.passcode.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val places by vm.savedPlaces.collectAsStateWithLifecycle()
    val pinMode by vm.pinMode.collectAsStateWithLifecycle()
    val departure by vm.departureMs.collectAsStateWithLifecycle()
    val myName by vm.myName.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var newPlaceName by remember { mutableStateOf("") }
    var customWhen by remember { mutableStateOf("") }

    // "Current location" as a start point needs the permission up front, not
    // after the journey has been created.
    val context = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    val editingLeg = legs.getOrElse(editing) { legs.first() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(Spacing.lg))
        AdaptiveContainer {
            Text("New journey", color = colors.textHigh, style = MaterialTheme.typography.displaySmall)
            Text(
                "Add a stage for each vehicle you'll travel in. Most journeys have one.",
                color = colors.textMid, style = MaterialTheme.typography.bodyLarge
            )

            OutlinedTextField(
                value = myName,
                onValueChange = { vm.myName.value = it },
                label = { Text("Your name — your circle sees \"…'s Journey\"") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ---- the stages ----
            legs.forEachIndexed { index, leg ->
                LegCard(
                    index = index,
                    total = legs.size,
                    leg = leg,
                    isEditing = index == editing,
                    onFocus = { vm.editLeg(index) },
                    onRemove = { vm.removeLeg(index) },
                    onModeChange = { vm.setMode(index, it) },
                    onDetailChange = { key, value -> vm.setDetail(index, key, value) },
                    onFromChange = { vm.setFromText(index, it) },
                    onToChange = { vm.setToText(index, it) },
                    onBoardingChange = { vm.setBoardingPoint(index, it) }
                )
            }

            SecondaryButton(
                "Add another stage",
                { vm.addLeg() },
                leading = "＋",
                accent = colors.traveller,
                height = 44.dp
            )
            if (legs.size > 1) {
                Text(
                    "Koode switches its rules as you move between stages: refuelling questions on the car leg, " +
                        "boarding milestones on the train leg, and no break nagging where you're not driving.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }

            // ---- place search, applied to the stage being edited ----
            KoodeCard(title = "Find a place") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search by name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Box(Modifier.width(96.dp)) {
                        SecondaryButton(
                            if (searching) "…" else "Search",
                            { vm.searchPlaces(searchQuery) },
                            enabled = !searching, height = 48.dp
                        )
                    }
                }
                results.forEach { r ->
                    Spacer(Modifier.height(Spacing.sm))
                    Text(r.name, color = colors.textHigh, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { vm.useSearchResult(r, asStart = true) }) {
                            Text("Set as start", color = colors.accent, fontSize = 13.sp)
                        }
                        TextButton(onClick = { vm.useSearchResult(r, asStart = false) }) {
                            Text("Set as destination", color = colors.accent, fontSize = 13.sp)
                        }
                    }
                }
                if (results.isNotEmpty()) {
                    TextButton(onClick = { vm.clearSearch() }) {
                        Text("Clear results", color = colors.textLow, fontSize = 12.sp)
                    }
                }
                if (places.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text("Saved places", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                    places.forEach { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.name, color = colors.textHigh,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vm.useAsStart(p) }) {
                                Text("From", color = colors.accent, fontSize = 13.sp)
                            }
                            TextButton(onClick = { vm.useAsDest(p) }) {
                                Text("To", color = colors.accent, fontSize = 13.sp)
                            }
                            TextButton(onClick = { vm.deletePlace(p.name) }) {
                                Text("✕", color = colors.textLow, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // ---- map pinning for the stage being edited ----
            KoodeCard(title = "Or pin it on the map") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Long-press to set the",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    KoodeChip("Destination", pinMode == "DEST", { vm.pinMode.value = "DEST" })
                    Spacer(Modifier.width(Spacing.sm))
                    KoodeChip("Start", pinMode == "START", { vm.pinMode.value = "START" })
                }
                Spacer(Modifier.height(Spacing.md))
                JourneyMap(
                    origin = editingLeg.from,
                    destination = editingLeg.to,
                    height = windowClass.mapHeight,
                    showPlayControl = false,
                    live = false,
                    onLongPress = { vm.onMapLongPress(it) }
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPlaceName,
                        onValueChange = { newPlaceName = InputRules.itemText(it) },
                        label = { Text("Save this pin as…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Box(Modifier.width(90.dp)) {
                        SecondaryButton("Save", { vm.savePlace(newPlaceName); newPlaceName = "" }, height = 48.dp)
                    }
                }
            }

            // ---- passcode ----
            KoodeCard(title = "Passcode for followers") {
                Text(
                    "Six digits you choose. Anyone with your journey number AND this passcode goes straight in — " +
                        "everyone else has to be approved by you.",
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
}

/**
 * One stage of the journey. Collapsed unless it's the stage being edited, so a
 * three-leg journey doesn't turn the screen into a wall of fields.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegCard(
    index: Int,
    total: Int,
    leg: LegDraft,
    isEditing: Boolean,
    onFocus: () -> Unit,
    onRemove: () -> Unit,
    onModeChange: (String) -> Unit,
    onDetailChange: (String, String) -> Unit,
    onFromChange: (String) -> Unit,
    onToChange: (String) -> Unit,
    onBoardingChange: (String) -> Unit
) {
    val colors = KoodeTheme.colors
    val profile = leg.profile

    KoodeCard(
        accent = if (isEditing) colors.accent else null,
        onClick = if (isEditing) null else onFocus
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(profile.emoji, fontSize = 18.sp)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                if (total == 1) "Your journey" else "Stage ${index + 1} · ${profile.label}",
                color = colors.textHigh, style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.weight(1f))
            if (total > 1) {
                TextButton(onClick = onRemove) { Text("Remove", color = colors.textLow, fontSize = 12.sp) }
            }
        }

        if (!isEditing) {
            Text(
                "${leg.fromText.ifBlank { "…" }} → ${leg.toText.ifBlank { "…" }}",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
            return@KoodeCard
        }

        Spacer(Modifier.height(Spacing.md))
        OutlinedTextField(
            value = leg.fromText, onValueChange = onFromChange,
            label = { Text("From") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = leg.toText, onValueChange = onToChange,
            label = { Text("To") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(Spacing.md))
        Text("How are you travelling?", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(Spacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TransportCatalog.ALL.forEach { p ->
                KoodeChip(p.label, leg.mode == p.key, { onModeChange(p.key) }, leading = p.emoji)
            }
        }

        Spacer(Modifier.height(Spacing.md))
        // Rendered from the mode's own declaration, so this screen and the
        // mid-journey switch always ask the same questions in the same words.
        TravelDetailFields(
            mode = leg.mode,
            values = leg.details,
            onChange = onDetailChange
        )

        if (!profile.isPrivateVehicle) {
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = leg.boardingPoint, onValueChange = onBoardingChange,
                label = { Text(profile.boardingPointLabel + " (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            "Kept on this phone only — never shared with anyone following you.",
            color = colors.textLow, style = MaterialTheme.typography.bodySmall
        )
    }
}
