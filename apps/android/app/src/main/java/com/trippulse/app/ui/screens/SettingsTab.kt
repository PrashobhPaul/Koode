package com.trippulse.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import com.trippulse.app.ui.SettingsVm
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

    var name by remember { mutableStateOf(Profile.name(context)) }
    var c1 by remember { mutableStateOf(Profile.contact(context, 1)) }
    var c2 by remember { mutableStateOf(Profile.contact(context, 2)) }
    var c3 by remember { mutableStateOf(Profile.contact(context, 3)) }
    var placeLabel by remember { mutableStateOf("") }
    var placeQuery by remember { mutableStateOf("") }

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
    KoodeCard(title = "Profile") {
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
        OutlinedTextField(
            value = placeLabel,
            onValueChange = { placeLabel = InputRules.itemText(it) },
            label = { Text("Name it (Home / Office / …)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton("Use my current location", { vm.addCurrentLocation(placeLabel) }, height = 44.dp)
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = placeQuery, onValueChange = { placeQuery = it },
                label = { Text("…or search a place") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            Box(Modifier.width(96.dp)) {
                SecondaryButton(
                    if (searching) "…" else "Search",
                    { vm.searchPlaces(placeQuery) }, enabled = !searching, height = 48.dp
                )
            }
        }
        results.forEach { r ->
            Spacer(Modifier.height(Spacing.sm))
            Text(r.name, color = colors.textHigh, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                vm.addPlace(placeLabel, r.point, r.name); placeLabel = ""; placeQuery = ""
            }) { Text("Save this place", color = colors.accent, fontSize = 13.sp) }
        }
    }

    // ---- emergency contacts ----
    KoodeCard(title = "Emergency contacts (at least ${Profile.MIN_CONTACTS})") {
        Text(
            "These people are your circle: they're approved automatically when they ask to follow one of your journeys.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(Spacing.sm))
        ContactRow("Contact 1", c1) { c1 = it }
        ContactRow("Contact 2", c2) { c2 = it }
        ContactRow("Contact 3", c3) { c3 = it }
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
private fun ContactRow(label: String, contact: Profile.Contact, onChange: (Profile.Contact) -> Unit) {
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
