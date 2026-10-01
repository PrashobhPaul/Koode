package com.trippulse.app.ui.screens

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.BuildConfig
import com.trippulse.app.core.InputRules
import com.trippulse.app.core.KoodeSettings
import com.trippulse.app.core.LocationCadence
import com.trippulse.app.core.Profile
import com.trippulse.app.core.ViewerRefresh
import com.trippulse.app.domain.MoneyFormat
import com.trippulse.app.domain.UnitPreference
import com.trippulse.app.notifications.Notifier
import com.trippulse.app.ui.HomeTabs
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.SettingsVm
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.ProfileAvatar
import com.trippulse.app.ui.components.BackButton
import com.trippulse.app.ui.components.KoodeChip
import com.trippulse.app.ui.components.KoodeIcons
import com.trippulse.app.ui.components.LocalDims
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.SecondaryButton
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/** The focused pages behind More. Route: `settings/{page}`. */
object SettingsPage {
    const val PROFILE = "profile"
    const val RECORDING = "recording"
    const val JOURNEY = "journey"
    const val DETECTION = "detection"
    const val UNITS = "units"
    const val CURRENCY = "currency"
    const val VEHICLES = "vehicles"
    const val PLACES = "places"
    const val SAFETY = "safety"
    const val CONTACTS = "contacts"
    const val SHARING = "sharing"
    const val SOS = "sos"
    const val NOTIFICATIONS = "notifications"
    const val PLACES_CONTACTS = "places-contacts"
    const val PRIVACY = "privacy"
    const val SHARED_DATA = "shared-data"
    const val STORAGE = "storage"
    const val APPEARANCE = "appearance"
    const val ABOUT = "about"
    const val LICENSES = "licenses"
}

/** One focused settings page: back, a title, and only what belongs here. */
@Composable
fun SettingsPageScreen(nav: NavHostController, page: String) {
    val vm: SettingsVm = viewModel(factory = SettingsVm.Factory)
    val colors = KoodeTheme.colors
    val title = when (page) {
        SettingsPage.PROFILE -> "Profile"
        SettingsPage.RECORDING -> "Journey recording"
        SettingsPage.JOURNEY -> "Journey settings"
        SettingsPage.DETECTION -> "Travel detection"
        SettingsPage.UNITS -> "Distance & speed units"
        SettingsPage.CURRENCY -> "Currency"
        SettingsPage.VEHICLES -> "Vehicles"
        SettingsPage.PLACES -> "Saved places"
        SettingsPage.SAFETY -> "Safety & sharing"
        SettingsPage.CONTACTS -> "Emergency contacts"
        SettingsPage.SHARING -> "Journey sharing"
        SettingsPage.SOS -> "SOS"
        SettingsPage.NOTIFICATIONS -> "Notifications"
        SettingsPage.PLACES_CONTACTS -> "Places & contacts"
        SettingsPage.PRIVACY -> "Privacy & data"
        SettingsPage.SHARED_DATA -> "Shared journey data"
        SettingsPage.STORAGE -> "Data & storage"
        SettingsPage.APPEARANCE -> "Appearance & feel"
        SettingsPage.ABOUT -> "About Koode"
        SettingsPage.LICENSES -> "Open source licenses"
        else -> "Settings"
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Spacer(Modifier.height(Spacing.xs))
        AdaptiveContainer {
            BackButton({ nav.popBackStack() })
            Text(title, color = colors.textHigh, style = MaterialTheme.typography.headlineMedium)
            when (page) {
                SettingsPage.PROFILE -> ProfilePage(vm) { nav.popBackStack() }
                SettingsPage.RECORDING -> RecordingPage(vm)
                SettingsPage.JOURNEY -> JourneySettingsPage(nav)
                SettingsPage.DETECTION -> DetectionPage(vm)
                SettingsPage.UNITS -> UnitsPage(vm)
                SettingsPage.CURRENCY -> CurrencyPage(vm)
                SettingsPage.VEHICLES -> VehiclesPage(vm)
                SettingsPage.PLACES -> SavedPlacesPage(vm)
                SettingsPage.SAFETY -> SafetyPage(nav)
                SettingsPage.CONTACTS -> ContactsPage(vm)
                SettingsPage.SHARING -> SharingPage(vm)
                SettingsPage.SOS -> SosPage(nav)
                SettingsPage.NOTIFICATIONS -> NotificationsPage(nav)
                SettingsPage.PLACES_CONTACTS -> PlacesContactsPage(nav)
                SettingsPage.PRIVACY -> PrivacyPage(nav)
                SettingsPage.SHARED_DATA -> SharedDataPage()
                SettingsPage.STORAGE -> StoragePage(nav)
                SettingsPage.APPEARANCE -> AppearancePage(vm)
                SettingsPage.ABOUT -> AboutKoodePage(nav, vm)
                SettingsPage.LICENSES -> LicensesPage()
            }
        }
        Spacer(Modifier.height(Spacing.section))
    }
}

/** Short explanatory copy under a group. */
@Composable
private fun Note(text: String) {
    Text(
        text, color = KoodeTheme.colors.textMid, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

/** A padded block inside a group, for chips and fields. */
@Composable
private fun GroupBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(LocalDims.current.cardPadding), content = content)
}

private fun openRepoDoc(context: Context, path: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$REPO_URL/$path"))) }
}

/** Opens this app's page in system settings (permissions, battery, storage). */
private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Jumps back to Home's pager on [tab] (0 Home, 1 Journeys, 2 People, 3 More). */
private fun openTab(nav: NavHostController, tab: Int) {
    HomeTabs.request(tab)
    nav.popBackStack(Routes.HOME, inclusive = false)
}

// ---------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilePage(vm: SettingsVm, onSaved: () -> Unit) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    var name by remember { mutableStateOf(Profile.name(context)) }
    var gender by remember { mutableStateOf(Profile.avatarStyle(context)) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) Profile.savePhoto(context, uri)
    }
    // Canonical state: the photo shown here is the one every avatar shows.
    val revision by Profile.revision.collectAsStateWithLifecycle()
    val hasPhoto = remember(revision) { Profile.hasPhoto(context) }

    SettingsGroup {
        GroupBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(72.dp)
                Spacer(Modifier.width(Spacing.lg))
                Column(Modifier.weight(1f)) {
                    Text("Profile photo", color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                    Row {
                        TextButton(onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(if (hasPhoto) "Change photo" else "Add photo", color = colors.accent) }
                        if (hasPhoto) {
                            TextButton(onClick = { Profile.clearPhoto(context) }) {
                                Text("Remove", color = colors.textLow)
                            }
                        }
                    }
                }
            }
        }
    }

    SettingsGroup {
        GroupBody {
            OutlinedTextField(
                value = name,
                onValueChange = { name = InputRules.itemText(it) },
                label = { Text("Full name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.md))
            Text("Gender", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(Spacing.sm))
            // Exactly two choices. A profile saved earlier as "neutral" simply
            // shows neither selected until one is picked.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                KoodeChip("Male", gender == Profile.AvatarStyle.MALE, { gender = Profile.AvatarStyle.MALE })
                KoodeChip("Female", gender == Profile.AvatarStyle.FEMALE, { gender = Profile.AvatarStyle.FEMALE })
            }
        }
    }

    PrimaryButton("Save", {
        if (gender != Profile.AvatarStyle.NEUTRAL) Profile.setAvatarStyle(context, gender)
        vm.saveProfile(name, Profile.contacts(context))
        onSaved()
    }, enabled = name.isNotBlank(), height = 50.dp)
}

// ---------------------------------------------------------------------------
// Recording
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingPage(vm: SettingsVm) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()

    GroupLabel("Koode mode")
    SettingsGroup {
        GroupBody {
            RecordingModePicker(settings.locationCadence) { vm.setLocationCadence(it) }
            Spacer(Modifier.height(Spacing.md))
            LocationCadence.entries.forEach { c ->
                val on = c == settings.locationCadence
                Text(
                    modeShortLabel(c) + if (c == LocationCadence.DEFAULT) " (recommended)" else "",
                    color = if (on) colors.textHigh else colors.textMid,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "A location every ${seconds(c.movingS)} while moving, every ${seconds(c.stationaryS)} while stopped.",
                    color = colors.textMid, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(Spacing.sm))
            }
        }
    }
    Note(
        "SOS, journey start and end, arrival detection and important journey changes work in every mode. " +
            "Koode also eases off by itself on trains, buses and flights."
    )

    GroupLabel("Battery")
    SettingsGroup {
        GroupBody {
            Text("Switch to Battery Saver below", color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(Spacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(10, 15, 20, 30).forEach { pct ->
                    KoodeChip("$pct%", settings.batterySaverBelowPct == pct, { vm.setBatterySaverThreshold(pct) })
                }
            }
        }
        RowDivider()
        SettingsRow(
            "Battery optimisation",
            subtitle = "Let Koode keep recording with the screen off",
            onClick = { openBatterySettings(context) }
        )
        RowDivider()
        SettingsRow(
            "Location permission",
            subtitle = "“Allow all the time” keeps a journey recording in the background",
            onClick = { openAppSettings(context) }
        )
    }

    GroupLabel("When you follow someone")
    SettingsGroup {
        GroupBody {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ViewerRefresh.entries.forEach { r ->
                    KoodeChip(r.label, settings.viewerRefresh == r, { vm.setViewerRefresh(r) })
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(settings.viewerRefresh.summary, color = colors.textMid, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun seconds(s: Long): String = if (s % 60 == 0L) "${s / 60} min" else "$s s"

private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { openAppSettings(context) }
}

// ---------------------------------------------------------------------------
// Journey settings
// ---------------------------------------------------------------------------

@Composable
private fun JourneySettingsPage(nav: NavHostController) {
    SettingsGroup {
        SettingsRow("Journey recording", subtitle = "Koode mode, battery, background", onClick = { nav.navigate(Routes.settings(SettingsPage.RECORDING)) })
        RowDivider()
        SettingsRow("Travel detection", subtitle = "Toll crossings", onClick = { nav.navigate(Routes.settings(SettingsPage.DETECTION)) })
        RowDivider()
        SettingsRow("Distance & speed units", onClick = { nav.navigate(Routes.settings(SettingsPage.UNITS)) })
        RowDivider()
        SettingsRow("Currency", onClick = { nav.navigate(Routes.settings(SettingsPage.CURRENCY)) })
        RowDivider()
        SettingsRow("Vehicles", subtitle = "Cars, bikes and FASTag", onClick = { nav.navigate(Routes.settings(SettingsPage.VEHICLES)) })
        RowDivider()
        SettingsRow("Saved places", onClick = { nav.navigate(Routes.settings(SettingsPage.PLACES)) })
    }
}

@Composable
private fun DetectionPage(vm: SettingsVm) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    SettingsGroup {
        SettingsToggle(
            "Count toll crossings automatically",
            settings.tollDetectionEnabled,
            subtitle = "Car and bike journeys, from your location alone — no SMS is read"
        ) { vm.setTollDetection(it) }
    }
    Note(
        "Missed one? Tap “Toll crossed” in Add a note on your journey. With a FASTag annual pass on " +
            "your vehicle, each crossing is counted off its remaining trips; toll amounts are asked about " +
            "privately, after the crossing."
    )
    Note("Toll plaza locations © OpenStreetMap contributors (ODbL).")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitsPage(vm: SettingsVm) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    SettingsGroup {
        GroupBody {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                UnitPreference.entries.forEach { p ->
                    KoodeChip(p.label, settings.unitPreference == p, { vm.setUnitPreference(p) })
                }
            }
        }
    }
    Note(vm.detectedRegionSummary())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrencyPage(vm: SettingsVm) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    SettingsGroup {
        GroupBody {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                KoodeChip("Match my region", settings.currencyCode.isBlank(), { vm.setCurrencyCode("") })
                MoneyFormat.COMMON_CODES.forEach { code ->
                    KoodeChip(code, settings.currencyCode == code, { vm.setCurrencyCode(code) })
                }
            }
        }
    }
    Note("Used for your private trip spending. It is never shared with the people following you.")
}

@Composable
private fun VehiclesPage(vm: SettingsVm) {
    val vehicles by vm.vehicles.collectAsStateWithLifecycle()
    VehiclesCard(
        vehicles = vehicles,
        onSave = { id, kind, nm, reg, mode, crossings, amount -> vm.saveVehicle(id, kind, nm, reg, mode, crossings, amount) },
        onDelete = { id -> vm.deleteVehicle(id) }
    )
}

/** Saved places: names only — never raw coordinates. */
@Composable
private fun SavedPlacesPage(vm: SettingsVm) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    val places by vm.savedPlaces.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<com.trippulse.app.data.routing.PlaceSearch.Place?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (places.isEmpty()) {
        SettingsGroup {
            GroupBody {
                Text("No saved places yet", color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Save Home, Office or a relative's house for one-tap From / To when you plan a journey.",
                    color = colors.textMid, style = MaterialTheme.typography.bodySmall
                )
            }
        }
    } else {
        SettingsGroup {
            places.forEachIndexed { i, p ->
                if (i > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.xs, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(KoodeIcons.Pin, contentDescription = null, tint = colors.textMid, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.md))
                    Text(p.name, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.deletePlace(p.name) }) {
                        Icon(KoodeIcons.Trash, contentDescription = "Delete ${p.name}", tint = colors.textLow, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
    SecondaryButton("Add a place", { note = null; adding = true }, leading = "＋", height = 48.dp)
    note?.let { Text(it, color = colors.accent, style = MaterialTheme.typography.bodyMedium) }

    if (adding) {
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
            onPick = { place -> adding = false; vm.searchPlaces(""); pendingSave = place },
            offerCurrentLocation = true,
            onOpenGoogleMaps = { query -> com.trippulse.app.ui.components.openGoogleMaps(context, query) },
            onSharedText = { text ->
                scope.launch {
                    val place = vm.resolveShared(text)
                    adding = false
                    if (place != null) pendingSave = place
                    else note = "Couldn't read a location from that Google Maps link. Try again, or search by name."
                }
            },
            onSavePlace = { name, point -> vm.addPlace(name, point, name); note = "Saved “$name”." },
            onDeleteSaved = { vm.deletePlace(it) },
            onDismiss = { adding = false; vm.searchPlaces("") }
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
                    note = "Saved “${label.ifBlank { place.name }}”."
                    pendingSave = null
                }, enabled = label.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pendingSave = null }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------------
// Safety & sharing
// ---------------------------------------------------------------------------

@Composable
private fun SafetyPage(nav: NavHostController) {
    SettingsGroup {
        SettingsRow("Emergency contacts", onClick = { nav.navigate(Routes.settings(SettingsPage.CONTACTS)) })
        RowDivider()
        SettingsRow("Journey followers", subtitle = "Who you can share a journey with", onClick = { openTab(nav, 2) })
        RowDivider()
        SettingsRow("Journey sharing preferences", onClick = { nav.navigate(Routes.settings(SettingsPage.SHARING)) })
        RowDivider()
        SettingsRow("SOS settings", onClick = { nav.navigate(Routes.settings(SettingsPage.SOS)) })
    }
}

/** The one place phone numbers are shown: managing the contacts themselves. */
@Composable
private fun ContactsPage(vm: SettingsVm) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    val message by vm.message.collectAsStateWithLifecycle()
    var c1 by remember { mutableStateOf(Profile.contact(context, 1)) }
    var c2 by remember { mutableStateOf(Profile.contact(context, 2)) }
    var c3 by remember { mutableStateOf(Profile.contact(context, 3)) }
    var pickingSlot by remember { mutableIntStateOf(0) }
    val contactPicker = rememberLauncherForActivityResult(PickPhoneContact()) { uri ->
        val picked = uri?.let { readPickedContact(context, it) }
        if (picked != null) {
            val (nm, ph) = picked
            val c = Profile.Contact(InputRules.itemText(nm), InputRules.phoneText(ph))
            when (pickingSlot) { 1 -> c1 = c; 2 -> c2 = c; 3 -> c3 = c }
        }
    }
    fun pick(slot: Int) { pickingSlot = slot; contactPicker.launch(Unit) }

    Note(
        "At least ${Profile.MIN_CONTACTS}. They are your trusted journey followers: approved automatically when they " +
            "ask to follow one of your journeys. Picking from your contacts shares only the one you choose — " +
            "Koode never reads your address book."
    )
    SettingsGroup {
        GroupBody {
            ContactRow("Contact 1", c1, { pick(1) }) { c1 = it }
            Spacer(Modifier.height(Spacing.sm))
            ContactRow("Contact 2", c2, { pick(2) }) { c2 = it }
            Spacer(Modifier.height(Spacing.sm))
            ContactRow("Contact 3", c3, { pick(3) }) { c3 = it }
        }
    }
    message?.let { Text(it, color = colors.accent, style = MaterialTheme.typography.bodyMedium) }
    PrimaryButton("Save", { vm.saveProfile(Profile.name(context), listOf(c1, c2, c3)) }, height = 50.dp)
}

@Composable
private fun SharingPage(vm: SettingsVm) {
    val colors = KoodeTheme.colors
    val settings by vm.settings.collectAsStateWithLifecycle()
    SettingsGroup {
        SettingsToggle(
            "Offer to send my timeline on WhatsApp",
            settings.shareTimelineOnWhatsApp,
            subtitle = "When a journey ends, to your emergency contacts"
        ) { vm.setShareTimelineOnWhatsApp(it) }
    }
    Note(
        "Koode prepares the timeline PDF for each of your ${vm.circleSize()} emergency contacts and opens " +
            "WhatsApp so you can send it yourself. Costs are never included — your spending stays private."
    )
    if (settings.shareTimelineOnWhatsApp && !vm.whatsAppAvailable) {
        Text(
            "WhatsApp isn't installed on this phone, so Koode will offer the normal share sheet instead.",
            color = colors.warn, style = MaterialTheme.typography.bodySmall
        )
    }
    Note(
        "Each journey is shared only while it lasts, only with the people you approve. Followers see " +
            "meaningful moments — never your coaching, and nothing after it ends."
    )
}

@Composable
private fun SosPage(nav: NavHostController) {
    val colors = KoodeTheme.colors
    SettingsGroup {
        GroupBody {
            Text("How SOS works", color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Hold SOS on your journey screen. After a short countdown you can cancel, everyone following " +
                    "the journey is alerted at once, with your last location.",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "SOS works in every recording mode and is never held back by quiet hours or battery saving.",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
        }
        RowDivider()
        SettingsRow("Emergency contacts", onClick = { nav.navigate(Routes.settings(SettingsPage.CONTACTS)) })
    }
}

// ---------------------------------------------------------------------------
// Notifications — each category is an Android notification channel, so the
// system is the single source of truth and each can be silenced on its own.
// ---------------------------------------------------------------------------

@Composable
private fun NotificationsPage(nav: NavHostController) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val appOn = remember(version) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    fun state(channel: String): String =
        if (!appOn) "Off" else if (channelOn(context, channel)) "On" else "Off"

    SettingsGroup {
        SettingsRow("Journey updates", subtitle = "Starts, stops, halts, plan changes", value = remember(version) { state(Notifier.CH_EVENTS) },
            onClick = { openChannel(context, Notifier.CH_EVENTS) })
        RowDivider()
        SettingsRow("Wellbeing updates", subtitle = "Water, food and break suggestions", value = remember(version) { state(Notifier.CH_COACH) },
            onClick = { openChannel(context, Notifier.CH_COACH) })
        RowDivider()
        SettingsRow("Journey completion", subtitle = "Arrival, closing, review and reports", value = remember(version) { state(Notifier.CH_COMPLETION) },
            onClick = { openChannel(context, Notifier.CH_COMPLETION) })
        RowDivider()
        SettingsRow("Expense reminders", subtitle = "Private, never while you drive", value = remember(version) { state(Notifier.CH_EXPENSE) },
            onClick = { openChannel(context, Notifier.CH_EXPENSE) })
    }
    Note("SOS and safety alerts always come through.")

    GroupLabel("Communication")
    SettingsGroup {
        SettingsRow("Push notifications", value = if (appOn) "On" else "Off", onClick = { openAppNotifications(context) })
        RowDivider()
        SettingsRow("WhatsApp", subtitle = "Share your timeline when a journey ends", onClick = { nav.navigate(Routes.settings(SettingsPage.SHARING)) })
    }
}

private fun channelOn(context: Context, id: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
    val ch = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(id) ?: return true
    return ch.importance != NotificationManager.IMPORTANCE_NONE
}

private fun openChannel(context: Context, id: String) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { openAppNotifications(context) }
}

private fun openAppNotifications(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { openAppSettings(context) }
}

// ---------------------------------------------------------------------------
// Places & contacts, privacy, appearance, about
// ---------------------------------------------------------------------------

@Composable
private fun PlacesContactsPage(nav: NavHostController) {
    SettingsGroup {
        SettingsRow("Saved places", onClick = { nav.navigate(Routes.settings(SettingsPage.PLACES)) })
        RowDivider()
        SettingsRow("Journey followers", onClick = { openTab(nav, 2) })
        RowDivider()
        SettingsRow("Manage contacts", subtitle = "Your emergency contacts", onClick = { nav.navigate(Routes.settings(SettingsPage.CONTACTS)) })
    }
}

@Composable
private fun PrivacyPage(nav: NavHostController) {
    val context = LocalContext.current
    SettingsGroup {
        SettingsRow("Location permissions", onClick = { openAppSettings(context) })
        RowDivider()
        SettingsRow("Journey history", subtitle = "Kept on this phone until you delete it", onClick = { openTab(nav, 1) })
        RowDivider()
        SettingsRow("Shared journey data", onClick = { nav.navigate(Routes.settings(SettingsPage.SHARED_DATA)) })
        RowDivider()
        SettingsRow("Data & storage", onClick = { nav.navigate(Routes.settings(SettingsPage.STORAGE)) })
        RowDivider()
        SettingsRow("Privacy policy", onClick = { openRepoDoc(context, "blob/main/docs/PRIVACY.md") })
    }
}

@Composable
private fun SharedDataPage() {
    val colors = KoodeTheme.colors
    SettingsGroup {
        GroupBody {
            listOf(
                "Your location is shared only during a journey you started.",
                "Only with the people you approve.",
                "The shared copy self-destructs shortly after you end the journey.",
                "Followers receive the journey report only after you review and approve it.",
                "Your spending is never shared — not in notifications, not in the report."
            ).forEach {
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("•", color = colors.accent, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(it, color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun StoragePage(nav: NavHostController) {
    val context = LocalContext.current
    SettingsGroup {
        GroupBody {
            Text(
                "Your profile, emergency contacts, journey history, wellbeing and spending live on this phone. " +
                    "No ads, no analytics, no accounts.",
                color = KoodeTheme.colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
        }
        RowDivider()
        SettingsRow("Journey history", subtitle = "Open or delete past journeys", onClick = { openTab(nav, 1) })
        RowDivider()
        SettingsRow("Storage used by Koode", onClick = { openAppSettings(context) })
    }
}

@Composable
private fun AppearancePage(vm: SettingsVm) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    GroupLabel("Theme")
    SettingsGroup {
        listOf(
            KoodeSettings.THEME_SYSTEM to "Follow system",
            KoodeSettings.THEME_DARK to "Always dark",
            KoodeSettings.THEME_LIGHT to "Always light"
        ).forEachIndexed { i, (mode, label) ->
            if (i > 0) RowDivider()
            SettingsRow(
                label,
                value = if (settings.themeMode == mode) "✓" else null,
                showChevron = false,
                onClick = { vm.setThemeMode(mode) }
            )
        }
    }
    GroupLabel("Feel")
    SettingsGroup {
        SettingsToggle("Vibrate on important taps", settings.hapticFeedback) { vm.setHaptics(it) }
        RowDivider()
        SettingsToggle("Keep the screen on during a journey", settings.keepScreenOnDuringJourney) { vm.setKeepScreenOn(it) }
    }
}

@Composable
private fun AboutKoodePage(nav: NavHostController, vm: SettingsVm) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val checking by vm.checkingUpdate.collectAsStateWithLifecycle()

    SettingsGroup {
        GroupBody {
            Text("Koode", color = colors.textHigh, style = MaterialTheme.typography.titleLarge)
            Text("Version ${BuildConfig.VERSION_NAME}", color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
            update?.let {
                Spacer(Modifier.height(Spacing.sm))
                Text("Koode ${it.versionName} is available.", color = colors.traveller, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.weight(1f)) {
                    SecondaryButton(if (checking) "Checking…" else "Check for updates", { vm.checkForUpdateNow() }, enabled = !checking, height = 44.dp)
                }
                update?.let { u ->
                    Box(Modifier.weight(1f)) {
                        SecondaryButton("Download", { uriHandler.openUri(u.downloadUrl) }, accent = colors.traveller, height = 44.dp)
                    }
                }
            }
        }
        RowDivider()
        SettingsToggle("Tell me when an update is out", settings.checkForUpdates) { vm.setCheckForUpdates(it) }
    }
    SettingsGroup {
        SettingsRow("What's new", onClick = { openRepoDoc(context, "releases") })
        RowDivider()
        SettingsRow("Our story", onClick = { nav.navigate(Routes.ABOUT) })
        RowDivider()
        SettingsRow("Privacy policy", onClick = { openRepoDoc(context, "blob/main/docs/PRIVACY.md") })
        RowDivider()
        SettingsRow("Terms", onClick = { openRepoDoc(context, "blob/main/docs/TERMS.md") })
        RowDivider()
        SettingsRow("Open source licenses", onClick = { nav.navigate(Routes.settings(SettingsPage.LICENSES)) })
    }
}

@Composable
private fun LicensesPage() {
    val colors = KoodeTheme.colors
    val items = listOf(
        "Kotlin, kotlinx.coroutines" to "Apache License 2.0",
        "AndroidX, Jetpack Compose, Room, WorkManager" to "Apache License 2.0",
        "OkHttp" to "Apache License 2.0",
        "MapLibre Native for Android" to "BSD 2-Clause License",
        "ZXing" to "Apache License 2.0",
        "Firebase Cloud Messaging, Google Play services location" to "Android SDK License",
        "Sora, DM Sans typefaces" to "SIL Open Font License 1.1",
        "Toll plaza locations: OpenStreetMap contributors" to "Open Database License (ODbL)"
    )
    SettingsGroup {
        items.forEachIndexed { i, (what, license) ->
            if (i > 0) RowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                Text(what, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
                Text(license, color = colors.textMid, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Note("Koode's own source is published at github.com/PrashobhPaul/Koode.")
}
