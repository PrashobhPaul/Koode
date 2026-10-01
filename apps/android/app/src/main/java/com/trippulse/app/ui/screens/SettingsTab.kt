package com.trippulse.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.components.KoodeIcons
import com.trippulse.app.ui.components.LocalDims
import com.trippulse.app.ui.components.rememberHaptics
import com.trippulse.app.ui.theme.Motion
import com.trippulse.app.ui.theme.Radii
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
import com.trippulse.app.ui.components.ProfileAvatar
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.KoodeChip
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.SecondaryButton
import com.trippulse.app.ui.components.SectionHeader
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Spacing

internal const val REPO_URL = "https://github.com/PrashobhPaul/Koode"

/**
 * More — a settings hub, not a settings dump.
 *
 * The landing page holds only what is worth seeing at a glance: who you are,
 * how Koode records a journey (three plain modes, each a real recording
 * profile) and the categories. Every detailed control is one or two taps
 * away on a focused page ([SettingsPageScreen]), each reading and writing the
 * same settings store as before — nothing duplicated, nothing reset.
 */
@Composable
fun SettingsTab(nav: NavHostController) {
    val vm: SettingsVm = viewModel(factory = SettingsVm.Factory)
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val places by vm.savedPlaces.collectAsStateWithLifecycle()

    // Re-read the profile whenever this page comes back into view: it is
    // edited on its own page.
    var profileVersion by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { profileVersion++ }
    val revision by Profile.revision.collectAsStateWithLifecycle()
    val name = remember(profileVersion, revision) { Profile.name(context) }
    val missing = remember(profileVersion, places.size) { Profile.missing(context, places.size) }

    SectionHeader("More")

    // ---- profile ----
    SettingsGroup {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Edit profile") { nav.navigate(Routes.settings(SettingsPage.PROFILE)) }
                .padding(LocalDims.current.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatar(52.dp)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    name.ifBlank { "Add your name" },
                    color = colors.textHigh, style = MaterialTheme.typography.titleMedium
                )
                Text("Edit profile", color = colors.accent, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(KoodeIcons.Chevron, contentDescription = null, tint = colors.textLow, modifier = Modifier.size(18.dp))
        }
    }

    if (missing.isNotEmpty()) {
        SettingsGroup {
            SettingsRow(
                title = "Finish setting up Koode",
                subtitle = missing.joinToString(" · "),
                titleColor = colors.warn,
                onClick = {
                    nav.navigate(
                        Routes.settings(if (name.isBlank()) SettingsPage.PROFILE else SettingsPage.CONTACTS)
                    )
                }
            )
        }
    }

    // ---- Koode mode ----
    GroupLabel("Koode mode")
    SettingsGroup {
        Column(Modifier.padding(LocalDims.current.cardPadding)) {
            Text("Journey recording", color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.sm))
            RecordingModePicker(settings.locationCadence) { vm.setLocationCadence(it) }
            Spacer(Modifier.height(Spacing.sm))
            Text(settings.locationCadence.label, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
            Text(modeTagline(settings.locationCadence), color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
        }
        RowDivider()
        SettingsRow("Advanced recording settings", onClick = { nav.navigate(Routes.settings(SettingsPage.RECORDING)) })
    }

    // ---- categories ----
    GroupLabel("Settings")
    SettingsGroup {
        SettingsRow("Journey settings", icon = KoodeIcons.Journeys, onClick = { nav.navigate(Routes.settings(SettingsPage.JOURNEY)) })
        RowDivider()
        SettingsRow("Safety & sharing", icon = KoodeIcons.Shield, onClick = { nav.navigate(Routes.settings(SettingsPage.SAFETY)) })
        RowDivider()
        SettingsRow("Notifications", icon = KoodeIcons.Bell, onClick = { nav.navigate(Routes.settings(SettingsPage.NOTIFICATIONS)) })
        RowDivider()
        SettingsRow("Places & contacts", icon = KoodeIcons.Pin, onClick = { nav.navigate(Routes.settings(SettingsPage.PLACES_CONTACTS)) })
        RowDivider()
        SettingsRow("Privacy & data", icon = KoodeIcons.Lock, onClick = { nav.navigate(Routes.settings(SettingsPage.PRIVACY)) })
        RowDivider()
        SettingsRow("Appearance & feel", icon = KoodeIcons.Sun, onClick = { nav.navigate(Routes.settings(SettingsPage.APPEARANCE)) })
        RowDivider()
        SettingsRow("About Koode", icon = KoodeIcons.Info, onClick = { nav.navigate(Routes.settings(SettingsPage.ABOUT)) })
    }
    Spacer(Modifier.height(Spacing.sm))
}

/** The three recording profiles, as one segmented control. */
@Composable
internal fun RecordingModePicker(selected: LocationCadence, onSelect: (LocationCadence) -> Unit) {
    val colors = KoodeTheme.colors
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(colors.backgroundElevated)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LocationCadence.entries.forEach { c ->
            val on = c == selected
            val bg by animateColorAsState(
                if (on) colors.accent.copy(alpha = if (colors.isDark) 0.22f else 0.16f) else Color.Transparent,
                tween(Motion.normal), label = "modeBg"
            )
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(bg)
                    .selectable(selected = on, role = Role.RadioButton) {
                        if (!on) { haptics.tick(); onSelect(c) }
                    }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    modeShortLabel(c),
                    color = if (on) colors.textHigh else colors.textMid,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

internal fun modeShortLabel(c: LocationCadence): String = when (c) {
    LocationCadence.SAVER -> "Battery Saver"
    LocationCadence.BALANCED -> "Balanced"
    LocationCadence.PRECISE -> "High Precision"
}

internal fun modeTagline(c: LocationCadence): String = when (c) {
    LocationCadence.SAVER -> "Fewer location samples, lighter on battery"
    LocationCadence.BALANCED -> "Optimised for normal journeys"
    LocationCadence.PRECISE -> "More frequent recording, heavier on battery"
}

// ---------------------------------------------------------------------------
// Settings building blocks — compact rows in grouped surfaces
// ---------------------------------------------------------------------------

/** A small uppercase label above a group. */
@Composable
internal fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        color = KoodeTheme.colors.textLow,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 4.dp, top = Spacing.sm)
    )
}

/** One rounded surface holding a group of rows. */
@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    val colors = KoodeTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(colors.surface)
            .border(1.dp, colors.outline.copy(alpha = 0.5f), RoundedCornerShape(Radii.lg)),
        content = content
    )
}

@Composable
internal fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg)
            .height(1.dp)
            .background(KoodeTheme.colors.outline.copy(alpha = 0.45f))
    )
}

/**
 * One settings row: an optional icon, a title, an optional one-line
 * subtitle, and a trailing value or chevron. 56 dp minimum — a full touch
 * target whatever the font scale.
 */
@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    titleColor: Color? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val colors = KoodeTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = title, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.textMid, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.md))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor ?: colors.textHigh, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, color = colors.textMid, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(Spacing.sm))
            Text(value, color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
        }
        if (onClick != null && showChevron) {
            Spacer(Modifier.width(Spacing.xs))
            Icon(KoodeIcons.Chevron, contentDescription = null, tint = colors.textLow, modifier = Modifier.size(18.dp))
        }
    }
}

/** A switch row inside a group. */
@Composable
internal fun SettingsToggle(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    val colors = KoodeTheme.colors
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch) { haptics.tick(); onChange(it) }
            .padding(horizontal = Spacing.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = colors.textMid, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(Spacing.sm))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.accent,
                uncheckedTrackColor = colors.surfaceRaised
            )
        )
    }
}

/**
 * The traveller's garage — cars and bikes, each with an optional registration
 * and optional FASTag. Nothing here is required: a vehicle can be a bare
 * "Bike", and a person who never opens this card is unaffected everywhere else.
 * When the journey's vehicle is on a FASTag annual pass, each toll crossing
 * Koode counts on that journey comes off its remaining trips.
 */
@Composable
internal fun VehiclesCard(
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
internal fun VehicleRow(v: VehicleEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
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
internal fun VehicleEditor(
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
internal fun rupees(amount: Double): String {
    val n = if (amount % 1.0 == 0.0) "%,.0f".format(amount) else "%,.2f".format(amount)
    return "₹$n"
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun ContactRow(
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
internal class PickPhoneContact : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Reads the display name and number from a picked phone-contact URI. */
internal fun readPickedContact(context: Context, uri: Uri): Pair<String, String>? = runCatching {
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
