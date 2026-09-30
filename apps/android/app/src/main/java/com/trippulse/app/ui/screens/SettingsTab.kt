package com.trippulse.app.ui.screens

import kotlinx.coroutines.launch
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trippulse.app.BuildConfig
import com.trippulse.app.core.InputRules
import com.trippulse.app.core.KoodeSettings
import com.trippulse.app.core.LocationCadence
import com.trippulse.app.core.Profile
import com.trippulse.app.core.ViewerRefresh
import com.trippulse.app.data.local.VehicleEntity
import com.trippulse.app.domain.MoneyFormat
import com.trippulse.app.domain.UnitPreference
import com.trippulse.app.domain.fastag.FastagMode
import com.trippulse.app.domain.fastag.VehicleKind
import com.trippulse.app.ui.SettingsVm
import com.trippulse.app.ui.components.Avatar
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.KoodeChip
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.SecondaryButton
import com.trippulse.app.ui.components.SectionHeader
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Spacing

private const val REPO_URL = "https://github.com/PrashobhPaul/Koode"

/**
 * Settings — profile, saved places, emergency contacts, and the behaviour
 * controls the product asked to expose.
 *
 * The two cadence settings are the important additions. Location sampling and
 * follower refresh are the app's whole battery budget, and the right answer
 * genuinely differs per journey: a night drive wants precision, a twelve-hour
 * train wants the phone to still be alive at the other end. Making them
 * visible turns "why did my battery die?" into a choice the user already made.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(onProfileChanged: () -> Unit) {
    val vm: SettingsVm = viewModel(factory = SettingsVm.Factory)
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val settings by vm.settings.collectAsStateWithLifecycle()
    val places by vm.savedPlaces.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val checking by vm.checkingUpdate.collectAsStateWithLifecycle()
    val vehicles by vm.vehicles.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf(Profile.name(context)) }
    var c1 by remember { mutableStateOf(Profile.contact(context, 1)) }
    var c2 by remember { mutableStateOf(Profile.contact(context, 2)) }
    var c3 by remember { mutableStateOf(Profile.contact(context, 3)) }

    // Pick an emergency contact from the device's contacts. Uses the system
    // phone picker, which grants read access only to the one row the user
    // chose — so no READ_CONTACTS permission and no contact-book upload.
    var pickingSlot by remember { mutableStateOf(0) }
    val contactPicker = rememberLauncherForActivityResult(PickPhoneContact()) { uri ->
        val picked = uri?.let { readPickedContact(context, it) }
        if (picked != null) {
            val (nm, ph) = picked
            val c = Profile.Contact(InputRules.itemText(nm), InputRules.phoneText(ph))
            when (pickingSlot) { 1 -> c1 = c; 2 -> c2 = c; 3 -> c3 = c }
        }
    }
    fun pickContact(slot: Int) { pickingSlot = slot; contactPicker.launch(Unit) }

    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.setTollDetection(granted) }
    // Saved places use the same picker as journey planning: search, Google
    // Maps (share or copy), current location or a dropped pin — then a name.
    var addingPlace by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<com.trippulse.app.data.routing.PlaceSearch.Place?>(null) }
    var placeNote by remember { mutableStateOf<String?>(null) }
    val placeScope = androidx.compose.runtime.rememberCoroutineScope()

    SectionHeader("More")

    val missing = Profile.missing(context, places.size)
    if (missing.isNotEmpty()) {
        KoodeCard(accent = colors.warn) {
            Text(
                "Complete your profile to start using Koode",
                color = colors.warn, style = MaterialTheme.typography.titleMedium
            )
            missing.forEach {
                Text("• $it", color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // ---- profile ----
    var photoVer by remember { mutableStateOf(Profile.photoVersion(context)) }
    var avatar by remember { mutableStateOf(Profile.avatarStyle(context)) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && Profile.savePhoto(context, uri)) photoVer = Profile.photoVersion(context)
    }

    KoodeCard(title = "Profile") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(Profile.photoPath(context), avatar, 64.dp, version = photoVer)
            Spacer(Modifier.width(Spacing.md))
            Column {
                SecondaryButton(
                    if (Profile.hasPhoto(context)) "Change photo" else "Add a photo (optional)",
                    { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    height = 40.dp
                )
                if (Profile.hasPhoto(context)) {
                    TextButton(onClick = { Profile.clearPhoto(context); photoVer = Profile.photoVersion(context) }) {
                        Text("Remove", color = colors.textLow, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "Shown on your journey and its PDF. With no photo, this avatar stands in:",
            color = colors.textLow, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(
                Profile.AvatarStyle.NEUTRAL to "Neutral",
                Profile.AvatarStyle.MALE to "Male",
                Profile.AvatarStyle.FEMALE to "Female"
            ).forEach { (style, label) ->
                KoodeChip(label, avatar == style, { avatar = style; Profile.setAvatarStyle(context, style) })
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = name,
            onValueChange = { name = InputRules.itemText(it) },
            label = { Text("Your full name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // ---- how often we take a location fix ----
    KoodeCard(title = "Location updates") {
        Text(
            "How often your phone records where you are during a journey. " +
                "Koode already eases off automatically on trains, buses and flights, and when your battery is low.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(Spacing.md))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            LocationCadence.entries.forEach { c ->
                KoodeChip(c.label, settings.locationCadence == c, { vm.setLocationCadence(c) })
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            settings.locationCadence.summary,
            color = colors.textLow, style = MaterialTheme.typography.bodySmall
        )
    }

    // ---- how often we check on someone we follow ----
    KoodeCard(title = "When you're following someone") {
        Text(
            "How often your phone checks for news about journeys you follow.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(Spacing.md))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ViewerRefresh.entries.forEach { r ->
                KoodeChip(r.label, settings.viewerRefresh == r, { vm.setViewerRefresh(r) })
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(settings.viewerRefresh.summary, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
    }

    // ---- battery + feel ----
    KoodeCard(title = "Battery and feel") {
        Text(
            "Switch to battery saver below",
            color = colors.textHigh, style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(Spacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(10, 15, 20, 30).forEach { pct ->
                KoodeChip("$pct%", settings.batterySaverBelowPct == pct, { vm.setBatterySaverThreshold(pct) })
            }
        }
        Spacer(Modifier.height(Spacing.md))
        ToggleRow(
            "Keep the screen on during a journey",
            settings.keepScreenOnDuringJourney
        ) { vm.setKeepScreenOn(it) }
        ToggleRow("Vibrate on important taps", settings.hapticFeedback) { vm.setHaptics(it) }
    }

    // ---- measurements ----
    // Worked out from where the phone actually is, and overridable. Nobody
    // should have to configure this, and nobody should be stuck with the
    // wrong one either.
    KoodeCard(title = "Distance, speed and money") {
        Text(
            vm.detectedRegionSummary(),
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(Spacing.md))
        Text("Distances", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(Spacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            UnitPreference.entries.forEach { p ->
                KoodeChip(p.label, settings.unitPreference == p, { vm.setUnitPreference(p) })
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Text("Currency", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(Spacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            KoodeChip("Match my region", settings.currencyCode.isBlank(), { vm.setCurrencyCode("") })
            MoneyFormat.COMMON_CODES.forEach { code ->
                KoodeChip(code, settings.currencyCode == code, { vm.setCurrencyCode(code) })
            }
        }
    }

    // ---- sharing the timeline when a journey ends ----
    KoodeCard(title = "When a journey ends") {
        ToggleRow(
            "Send my timeline to my circle on WhatsApp",
            settings.shareTimelineOnWhatsApp
        ) { vm.setShareTimelineOnWhatsApp(it) }
        Text(
            buildString {
                append("The moment you mark a journey complete, Koode prepares the timeline PDF ")
                append("addressed to each of your ${vm.circleSize()} emergency contacts and opens ")
                append("WhatsApp so you can send it. ")
                append("Costs are never included — the money tracker stays private to you.")
            },
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        if (settings.shareTimelineOnWhatsApp && !vm.whatsAppAvailable) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "WhatsApp isn't installed on this phone, so Koode will offer the normal share sheet instead.",
                color = colors.warn, style = MaterialTheme.typography.bodySmall
            )
        }
    }

    // ---- FASTag toll updates ----
    KoodeCard(title = "FASTag toll updates") {
        Text(
            "Recognise toll-plaza SMS on this phone and add each crossing to your journey timeline. " +
                "Messages are read on this device only — the plaza, vehicle and time are kept, " +
                "the message itself is never stored or uploaded.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(Spacing.sm))
        ToggleRow("Detect toll crossings from SMS", settings.tollDetectionEnabled) { on ->
            if (on) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) vm.setTollDetection(true) else smsPermission.launch(Manifest.permission.RECEIVE_SMS)
            } else {
                vm.setTollDetection(false)
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "Add a vehicle below with its FASTag and Koode keeps that vehicle's balance up to date " +
                "as toll SMS come in — matched by number plate.",
            color = colors.textMid, style = MaterialTheme.typography.bodySmall
        )
    }

    // ---- My vehicles ----
    VehiclesCard(
        vehicles = vehicles,
        onSave = { id, kind, nm, reg, mode, crossings, amount ->
            vm.saveVehicle(id, kind, nm, reg, mode, crossings, amount)
        },
        onDelete = { id -> vm.deleteVehicle(id) }
    )

    // ---- appearance ----
    KoodeCard(title = "Appearance") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            KoodeChip("Follow system", settings.themeMode == KoodeSettings.THEME_SYSTEM,
                { vm.setThemeMode(KoodeSettings.THEME_SYSTEM) })
            KoodeChip("Always dark", settings.themeMode == KoodeSettings.THEME_DARK,
                { vm.setThemeMode(KoodeSettings.THEME_DARK) })
            KoodeChip("Always light", settings.themeMode == KoodeSettings.THEME_LIGHT,
                { vm.setThemeMode(KoodeSettings.THEME_LIGHT) })
        }
    }

    // ---- saved locations ----
    KoodeCard(title = "Saved places") {
        Text(
            "One-tap From / To when you plan a journey.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        places.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name, color = colors.textHigh,
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
                )
                Text(
                    "%.3f, %.3f".format(p.lat, p.lng),
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { vm.deletePlace(p.name) }) {
                    Text("✕", color = colors.textLow, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton("Add a place", { placeNote = null; addingPlace = true }, leading = "＋", height = 44.dp)
        placeNote?.let {
            Spacer(Modifier.height(Spacing.xs))
            Text(it, color = colors.accent, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (addingPlace) {
        com.trippulse.app.ui.components.PlacePicker(
            asStart = false,
            title = "Add a saved place",
            results = results,
            searching = searching,
            saved = places,
            recent = emptyList(),
            here = null,
            pinStart = null,
            onQuery = { vm.searchPlaces(it) },
            onPick = { place -> addingPlace = false; vm.searchPlaces(""); pendingSave = place },
            offerCurrentLocation = true,
            onOpenGoogleMaps = { query -> com.trippulse.app.ui.components.openGoogleMaps(context, query) },
            onSharedText = { text ->
                placeScope.launch {
                    val place = vm.resolveShared(text)
                    addingPlace = false
                    if (place != null) pendingSave = place
                    else placeNote = "Couldn't read a location from that Google Maps link. Try again, or search by name."
                }
            },
            onSavePlace = { name, point -> vm.addPlace(name, point, name); placeNote = "Saved \u201C$name\u201D." },
            onDeleteSaved = { vm.deletePlace(it) },
            onDismiss = { addingPlace = false; vm.searchPlaces("") }
        )
    }

    pendingSave?.let { place ->
        var label by remember(place) {
            mutableStateOf(if (place.name.startsWith("Current location")) "" else place.name.substringBefore(",").trim())
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingSave = null },
            title = { Text("Name this place") },
            text = {
                Column {
                    Text(
                        place.name + if (place.detail.isNotBlank() && !place.name.contains(place.detail)) " · ${place.detail}" else "",
                        color = colors.textMid, style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = label, onValueChange = { label = InputRules.itemText(it) },
                        label = { Text("Home, Office, Amma's house…") }, singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addPlace(label, place.point, place.name)
                    placeNote = "Saved \u201C${label.ifBlank { place.name }}\u201D."
                    pendingSave = null
                }, enabled = label.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pendingSave = null }) { Text("Cancel") } }
        )
    }

    // ---- emergency contacts ----
    KoodeCard(title = "Emergency contacts (at least ${Profile.MIN_CONTACTS})") {
        Text(
            "These people are your circle: they're approved automatically when they ask to follow one of your journeys.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(Spacing.sm))
        ContactRow("Contact 1", c1, { pickContact(1) }) { c1 = it }
        ContactRow("Contact 2", c2, { pickContact(2) }) { c2 = it }
        ContactRow("Contact 3", c3, { pickContact(3) }) { c3 = it }
    }

    if (message != null) {
        Text(message!!, color = colors.accent, style = MaterialTheme.typography.bodyMedium)
    }

    PrimaryButton("Save profile", {
        vm.saveProfile(name, listOf(c1, c2, c3))
        onProfileChanged()
    }, height = 50.dp)

    // ---- updates ----
    KoodeCard(title = "App version") {
        Text(
            "Koode ${vm.installedVersion}",
            color = colors.textHigh, style = MaterialTheme.typography.titleMedium
        )
        Text(
            if (update != null) "Koode ${update!!.versionName} is available."
            else "Updating never affects a journey in progress — yours or one you're watching. " +
                "Your history, places and contacts are carried across untouched.",
            color = if (update != null) colors.traveller else colors.textMid,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(Spacing.md))
        ToggleRow("Tell me when an update is out", settings.checkForUpdates) { vm.setCheckForUpdates(it) }
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.weight(1f)) {
                SecondaryButton(
                    if (checking) "Checking…" else "Check now",
                    { vm.checkForUpdateNow() }, enabled = !checking, height = 44.dp
                )
            }
            if (update != null) {
                Box(Modifier.weight(1f)) {
                    SecondaryButton(
                        "Download", { uriHandler.openUri(update!!.downloadUrl) },
                        accent = colors.traveller, height = 44.dp
                    )
                }
            }
        }
    }

    // ---- privacy & legal ----
    KoodeCard(title = "Privacy and legal") {
        Text(
            "Your location is shared only during a journey you started, only with people you approve, " +
                "and the shared copy self-destructs shortly after you end the journey. Your profile, " +
                "emergency contacts, history and expenses never leave this phone. No ads, no analytics, no accounts.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        Row {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$REPO_URL/blob/main/docs/PRIVACY.md")))
            }) { Text("Privacy policy", color = colors.accent, fontSize = 13.sp) }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$REPO_URL/blob/main/docs/TERMS.md")))
            }) { Text("Terms of use", color = colors.accent, fontSize = 13.sp) }
        }
    }

    KoodeCard(title = "About") {
        Text(
            "Koode ${BuildConfig.VERSION_NAME} — Always with you.",
            color = colors.textHigh, style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "A journey companion that keeps the people you love informed about your journey, wellbeing and safety — without you having to call or message them.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
    }
    Spacer(Modifier.height(Spacing.lg))
}

/**
 * The traveller's garage — cars and bikes, each with an optional registration
 * and optional FASTag. Nothing here is required: a vehicle can be a bare
 * "Bike", and a person who never opens this card is unaffected everywhere else.
 * When a vehicle does carry a plate and a FASTag, toll SMS matched to that
 * plate keep its balance current.
 */
@Composable
private fun VehiclesCard(
    vehicles: List<VehicleEntity>,
    onSave: (String?, VehicleKind, String, String, FastagMode, Int?, Double?) -> Unit,
    onDelete: (String) -> Unit
) {
    val colors = KoodeTheme.colors
    // null = editor closed; "" = adding a new vehicle; otherwise the id being edited.
    var editorFor by remember { mutableStateOf<String?>(null) }

    KoodeCard(title = "My vehicles") {
        Text(
            "Add your cars and bikes. Registration and FASTag are optional — fill in only what you want.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )

        vehicles.forEach { v ->
            Spacer(Modifier.height(Spacing.sm))
            if (editorFor == v.id) {
                VehicleEditor(
                    existing = v,
                    onCancel = { editorFor = null },
                    onSave = { kind, name, reg, mode, crossings, amount ->
                        onSave(v.id, kind, name, reg, mode, crossings, amount); editorFor = null
                    }
                )
            } else {
                VehicleRow(
                    v,
                    onEdit = { editorFor = v.id },
                    onDelete = { onDelete(v.id); if (editorFor == v.id) editorFor = null }
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        if (editorFor == "") {
            VehicleEditor(
                existing = null,
                onCancel = { editorFor = null },
                onSave = { kind, name, reg, mode, crossings, amount ->
                    onSave(null, kind, name, reg, mode, crossings, amount); editorFor = null
                }
            )
        } else {
            SecondaryButton("Add a vehicle", { editorFor = "" }, height = 46.dp)
        }
    }
}

/** One saved vehicle, with its FASTag balance when it tracks one. */
@Composable
private fun VehicleRow(v: VehicleEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = KoodeTheme.colors
    val emoji = if (VehicleKind.fromKey(v.kind) == VehicleKind.BIKE) "🏍" else "🚗"
    val title = v.name.ifBlank { if (VehicleKind.fromKey(v.kind) == VehicleKind.BIKE) "Bike" else "Car" }
    val balance = when (FastagMode.fromKey(v.fastagMode)) {
        FastagMode.ANNUAL_PASS -> v.passCrossingsLeft
            ?.let { "Annual pass · $it crossing${if (it == 1) "" else "s"} left" }
        FastagMode.AMOUNT -> v.amountLeft?.let { "FASTag · ${rupees(it)} left" }
        FastagMode.NONE -> null
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$emoji  ", style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
            val sub = listOfNotNull(v.registration.takeIf { it.isNotBlank() }, balance).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = onEdit) { Text("Edit") }
        TextButton(onClick = onDelete) { Text("✕", color = colors.textLow, fontSize = 13.sp) }
    }
}

/** Add / edit form for one vehicle. Kind is the only required choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleEditor(
    existing: VehicleEntity?,
    onCancel: () -> Unit,
    onSave: (VehicleKind, String, String, FastagMode, Int?, Double?) -> Unit
) {
    val colors = KoodeTheme.colors
    val key = existing?.id
    var kind by remember(key) { mutableStateOf(existing?.let { VehicleKind.fromKey(it.kind) } ?: VehicleKind.CAR) }
    var name by remember(key) { mutableStateOf(existing?.name ?: "") }
    var reg by remember(key) { mutableStateOf(existing?.registration ?: "") }
    var mode by remember(key) { mutableStateOf(existing?.let { FastagMode.fromKey(it.fastagMode) } ?: FastagMode.NONE) }
    var crossings by remember(key) { mutableStateOf(existing?.passCrossingsLeft?.toString() ?: "") }
    var amount by remember(key) {
        mutableStateOf(existing?.amountLeft?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "")
    }

    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            KoodeChip("Car", kind == VehicleKind.CAR, { kind = VehicleKind.CAR })
            KoodeChip("Bike", kind == VehicleKind.BIKE, { kind = VehicleKind.BIKE })
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(40) },
            label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = reg,
            onValueChange = { reg = it.uppercase().filter { c -> c.isLetterOrDigit() || c == ' ' || c == '-' }.take(15) },
            label = { Text("Registration (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.sm))
        Text("FASTag (optional)", color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            KoodeChip("None", mode == FastagMode.NONE, { mode = FastagMode.NONE })
            KoodeChip("Annual pass", mode == FastagMode.ANNUAL_PASS, { mode = FastagMode.ANNUAL_PASS })
            KoodeChip("Amount", mode == FastagMode.AMOUNT, { mode = FastagMode.AMOUNT })
        }
        when (mode) {
            FastagMode.ANNUAL_PASS -> {
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = crossings, onValueChange = { crossings = it.filter(Char::isDigit).take(5) },
                    label = { Text("Crossings left") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
            }
            FastagMode.AMOUNT -> {
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { s -> amount = s.filter { it.isDigit() || it == '.' }.take(8) },
                    label = { Text("Amount left (₹)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
            }
            FastagMode.NONE -> {}
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.weight(1f)) {
                PrimaryButton(
                    if (existing == null) "Add" else "Save",
                    {
                        onSave(
                            kind, name, reg, mode,
                            crossings.toIntOrNull(),
                            amount.toDoubleOrNull()
                        )
                    },
                    height = 46.dp
                )
            }
            Box(Modifier.weight(1f)) {
                SecondaryButton("Cancel", onCancel, height = 46.dp)
            }
        }
    }
}

/** Whole rupees plainly, paise only when the balance carries them. */
private fun rupees(amount: Double): String {
    val n = if (amount % 1.0 == 0.0) "%,.0f".format(amount) else "%,.2f".format(amount)
    return "₹$n"
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = KoodeTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, color = colors.textHigh,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.accent,
                uncheckedTrackColor = colors.surfaceRaised
            )
        )
    }
}

@Composable
private fun ContactRow(
    label: String,
    contact: Profile.Contact,
    onPick: () -> Unit,
    onChange: (Profile.Contact) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = KoodeTheme.colors.textMid, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onPick) { Text("Pick from contacts") }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        OutlinedTextField(
            value = contact.name,
            onValueChange = { onChange(contact.copy(name = InputRules.itemText(it))) },
            label = { Text("$label — name") }, singleLine = true, modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = contact.phone,
            onValueChange = { onChange(contact.copy(phone = InputRules.phoneText(it))) },
            label = { Text("Phone") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone)
        )
    }
}

/**
 * Picks a single phone number from the system contacts app. Using ACTION_PICK
 * on the phone-number URI means the OS returns a URI to just the row the user
 * chose and grants read access only to it — so Koode needs no READ_CONTACTS
 * permission and never sees the rest of the address book.
 */
private class PickPhoneContact : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Reads the display name and number from a picked phone-contact URI. */
private fun readPickedContact(context: Context, uri: Uri): Pair<String, String>? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        ),
        null, null, null
    )?.use { c ->
        if (c.moveToFirst()) {
            val name = c.getString(0).orEmpty()
            val number = c.getString(1).orEmpty()
            if (number.isNotBlank()) return@runCatching name to number
        }
    }
    null
}.getOrNull()
