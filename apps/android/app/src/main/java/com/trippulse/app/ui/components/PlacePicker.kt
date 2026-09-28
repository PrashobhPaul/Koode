package com.trippulse.app.ui.components

import android.location.Geocoder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.trippulse.app.ui.SharedPlaceInbox
import com.trippulse.app.core.LocationFix
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trippulse.app.core.InputRules
import com.trippulse.app.data.local.SavedPlaceEntity
import com.trippulse.app.data.routing.PlaceSearch
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.ui.PlaceSuggestion
import com.trippulse.app.ui.map.PinDropMap
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Choosing a place for one field — the start or the destination of a stage.
 *
 * Everything that used to be spread across the planning screen ("Find a
 * place", a search button, long-press pinning) lives here, behind a tap on the
 * field itself, so the traveller is never unsure which field a result will
 * land in.
 *
 * Search runs as you type (debounced). The same box accepts a pasted Google
 * Maps link or raw coordinates. "Choose in Google Maps" opens the Maps app;
 * sharing a place back to Koode fills this field (see SharedPlaceInbox).
 */
@Composable
fun PlacePicker(
    asStart: Boolean,
    results: List<PlaceSearch.Place>,
    searching: Boolean,
    saved: List<SavedPlaceEntity>,
    recent: List<PlaceSuggestion>,
    here: GeoPoint?,
    pinStart: GeoPoint?,
    onQuery: (String) -> Unit,
    onPick: (PlaceSearch.Place) -> Unit,
    /** Offer "Current location" (resolved to a real, named point). */
    offerCurrentLocation: Boolean,
    onOpenGoogleMaps: (String) -> Unit,
    /** A Google Maps link that came back — shared to Koode, or copied and returned with. */
    onSharedText: (String) -> Unit,
    onSavePlace: (String, GeoPoint) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onDismiss: () -> Unit,
    onLocated: (GeoPoint) -> Unit = {},
    title: String? = null
) {
    val colors = KoodeTheme.colors
    var query by remember { mutableStateOf("") }

    // ---- current location: permission, "turn on location", a real fix, a name ----
    var locError by remember { mutableStateOf<String?>(null) }
    val locator = rememberCurrentLocation { r, name ->
        if (r is LocationFix.Result.Found) {
            locError = null
            onLocated(r.point)
            onPick(PlaceSearch.Place(name?.let { "Current location · $it" } ?: "Current location", r.point, name.orEmpty()))
        } else locError = LocationFix.explain(r)
    }

    // ---- places coming back from Google Maps ----
    val clipboard = LocalClipboardManager.current
    val shared by SharedPlaceInbox.pending.collectAsState()
    DisposableEffect(Unit) {
        SharedPlaceInbox.pickerOpen = true
        onDispose { SharedPlaceInbox.pickerOpen = false }
    }
    // Shared to Koode from Maps: this open picker takes it for its own field.
    LaunchedEffect(shared) {
        SharedPlaceInbox.take()?.let { (text, _) -> onSharedText(text) }
    }
    var tab by remember { mutableStateOf(0) } // 0 = search, 1 = pin
    var savePrompt by remember { mutableStateOf<GeoPoint?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(query) {
        delay(350)
        onQuery(query)
    }
    LaunchedEffect(tab) { if (tab == 0) runCatching { focus.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(windowInfo) {
            snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
                if (!focused || !SharedPlaceInbox.isAwaiting()) return@collect
                // Only a link copied *after* leaving for Maps — never an old clipboard.
                // Accepted clips go through the inbox, so they're applied exactly once.
                SharedPlaceInbox.acceptReturnedClip(runCatching { clipboard.getText()?.text }.getOrNull())
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .systemBarsPadding()
                .imePadding()
        ) {
            // ---- top bar ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("✕", color = colors.textMid, fontSize = 18.sp) }
                Text(
                    title ?: if (asStart) "Where are you starting?" else "Where to?",
                    color = colors.textHigh, style = MaterialTheme.typography.titleLarge
                )
            }

            // ---- search / pin switch ----
            Row(
                Modifier
                    .padding(horizontal = Spacing.lg)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.pill))
                    .background(colors.surface)
                    .padding(4.dp)
            ) {
                listOf("🔎  Search", "📍  Pin on map").forEachIndexed { i, label ->
                    val on = tab == i
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Radii.pill))
                            .background(if (on) colors.accent else Color.Transparent)
                            .clickable { tab = i }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (on) colors.background else colors.textMid,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))

            if (tab == 0) {
                SearchTab(
                    query = query,
                    onQueryChange = { query = it },
                    focus = focus,
                    asStart = asStart,
                    results = results,
                    searching = searching,
                    saved = saved,
                    recent = recent,
                    onPick = onPick,
                    onUseCurrentLocation = if (offerCurrentLocation) ({ locError = null; locator.request() }) else null,
                    locating = locator.locating.value,
                    locError = locError,
                    onOpenGoogleMaps = {
                        SharedPlaceInbox.beginGoogleHandoff(runCatching { clipboard.getText()?.text }.getOrNull())
                        onOpenGoogleMaps(query.trim())
                    },
                    onAskSave = { savePrompt = it },
                    onDeleteSaved = onDeleteSaved
                )
            } else {
                PinTab(
                    start = pinStart ?: here,
                    asStart = asStart,
                    onPick = onPick,
                    onAskSave = { savePrompt = it }
                )
            }
        }
    }

    savePrompt?.let { point ->
        var name by remember(point) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { savePrompt = null },
            title = { Text("Save this place") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = InputRules.itemText(it) },
                    label = { Text("Name it (Home, Office, Amma's house…)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onSavePlace(name, point); savePrompt = null },
                    enabled = name.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { savePrompt = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SearchTab(
    query: String,
    onQueryChange: (String) -> Unit,
    focus: FocusRequester,
    asStart: Boolean,
    results: List<PlaceSearch.Place>,
    searching: Boolean,
    saved: List<SavedPlaceEntity>,
    recent: List<PlaceSuggestion>,
    onPick: (PlaceSearch.Place) -> Unit,
    onUseCurrentLocation: (() -> Unit)?,
    locating: Boolean,
    locError: String?,
    onOpenGoogleMaps: () -> Unit,
    onAskSave: (GeoPoint) -> Unit,
    onDeleteSaved: (String) -> Unit
) {
    val colors = KoodeTheme.colors
    Column(Modifier.padding(horizontal = Spacing.lg)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search a place, or paste a Google Maps link") },
            singleLine = true,
            trailingIcon = {
                when {
                    searching -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                    query.isNotEmpty() -> TextButton(onClick = { onQueryChange("") }) { Text("✕", color = colors.textLow) }
                }
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (onUseCurrentLocation != null) {
                ActionTile(
                    "🎯", if (locating) "Finding you…" else "Current location",
                    if (locating) "Getting a fresh position" else "Uses where you are now",
                    Modifier.weight(1f), onUseCurrentLocation
                )
            }
            ActionTile(
                "🗺", "Choose in Google Maps",
                if (query.isBlank()) "Find it, then Share → Koode or Copy link and come back"
                else "Opens Maps for \u201C${query.trim()}\u201D",
                Modifier.weight(1f), onOpenGoogleMaps
            )
        }
        if (locError != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(locError, color = colors.warn, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(Spacing.sm))

    val showSuggestions = query.isBlank()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.xxl)) {
        if (showSuggestions) {
            if (saved.isNotEmpty()) {
                item { ListHeader("Saved places") }
                items(saved, key = { "s-" + it.name }) { p ->
                    PlaceRow(
                        icon = "★", name = p.name, detail = null,
                        onClick = { onPick(PlaceSearch.Place(p.name, GeoPoint(p.lat, p.lng))) },
                        trailing = "Remove", onTrailing = { onDeleteSaved(p.name) }
                    )
                }
            }
            val recentOnly = recent.filter { !it.saved }
            if (recentOnly.isNotEmpty()) {
                item { ListHeader("Recent") }
                items(recentOnly, key = { "r-" + it.name }) { p ->
                    PlaceRow(
                        icon = "🕓", name = p.name, detail = null,
                        onClick = { onPick(PlaceSearch.Place(p.name, p.point)) },
                        trailing = "☆", onTrailing = { onAskSave(p.point) }
                    )
                }
            }
            if (saved.isEmpty() && recentOnly.isEmpty()) {
                item {
                    Text(
                        "Start typing a town, landmark or address. Places you go to will show up here next time.",
                        color = colors.textLow, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.lg)
                    )
                }
            }
        } else {
            items(results, key = { "${it.name}|${it.point.lat}|${it.point.lng}" }) { r ->
                PlaceRow(
                    icon = if (r.detail.startsWith("From Google")) "🗺" else "📍",
                    name = r.name, detail = r.detail.ifBlank { null },
                    onClick = { onPick(r) },
                    trailing = "☆", onTrailing = { onAskSave(r.point) }
                )
            }
            if (!searching && results.isEmpty() && query.trim().length >= 3) {
                item {
                    Text(
                        "No matches yet. Try adding the town or district, paste a Google Maps link, " +
                            "or switch to Pin on map.",
                        color = colors.textLow, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.lg)
                    )
                }
            }
        }
        if (!asStart) item { Spacer(Modifier.height(Spacing.sm)) }
    }
}

@Composable
private fun PinTab(
    start: GeoPoint?,
    asStart: Boolean,
    onPick: (PlaceSearch.Place) -> Unit,
    onAskSave: (GeoPoint) -> Unit
) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    var center by remember { mutableStateOf(start) }
    var label by remember { mutableStateOf<String?>(null) }
    var mapStart by remember { mutableStateOf(start) }
    var locError by remember { mutableStateOf<String?>(null) }
    val locator = rememberCurrentLocation { r, _ ->
        if (r is LocationFix.Result.Found) { locError = null; mapStart = r.point; center = r.point }
        else locError = LocationFix.explain(r)
    }

    // Name the spot under the pin with the phone's own geocoder once the map
    // settles; nothing leaves the device beyond what Android itself does.
    LaunchedEffect(center) {
        val c = center ?: return@LaunchedEffect
        delay(400)
        label = withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                Geocoder(context).getFromLocation(c.lat, c.lng, 1)?.firstOrNull()?.let { a ->
                    listOfNotNull(a.featureName?.takeIf { it.any(Char::isLetter) }, a.subLocality, a.locality)
                        .distinct().take(2).joinToString(", ").ifBlank { null }
                }
            } catch (_: Exception) { null }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            key(mapStart) {
                PinDropMap(start = mapStart, onCenter = { center = it }, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.md)
                    .clip(RoundedCornerShape(Radii.pill))
                    .background(colors.background.copy(alpha = 0.88f))
                    .clickable { locError = null; locator.request() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    if (locator.locating.value) "Finding you…" else "🎯  My location",
                    color = colors.textHigh, style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                label ?: "Drag the map to put the pin on the spot",
                color = colors.textHigh, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            center?.let {
                Text("%.5f, %.5f".format(it.lat, it.lng), color = colors.textLow, style = MaterialTheme.typography.bodySmall)
            }
            locError?.let { Text(it, color = colors.warn, style = MaterialTheme.typography.bodySmall) }
            PrimaryButton(
                "Use this spot",
                {
                    val c = center ?: return@PrimaryButton
                    onPick(PlaceSearch.Place(label ?: if (asStart) "Pinned start" else "Pinned destination", c))
                },
                enabled = center != null,
                leading = "📍"
            )
            SecondaryButton("Save this spot as a place", { center?.let(onAskSave) }, enabled = center != null, height = 44.dp)
        }
    }
}

@Composable
private fun ActionTile(icon: String, title: String, caption: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = KoodeTheme.colors
    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(Spacing.md)
    ) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.height(Spacing.xs))
        Text(title, color = colors.textHigh, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(caption, color = colors.textLow, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ListHeader(text: String) {
    Text(
        text.uppercase(),
        color = KoodeTheme.colors.textLow,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = Spacing.lg, top = Spacing.md, bottom = Spacing.xs)
    )
}

@Composable
private fun PlaceRow(
    icon: String,
    name: String,
    detail: String?,
    onClick: () -> Unit,
    trailing: String?,
    onTrailing: (() -> Unit)?
) {
    val colors = KoodeTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(Radii.pill)).background(colors.surfaceRaised),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 15.sp) }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, color = colors.textLow, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null && onTrailing != null) {
            TextButton(onClick = onTrailing) { Text(trailing, color = colors.textLow, fontSize = 13.sp) }
        }
    }
}

/**
 * Opens the Google Maps app (or maps.google.com if it isn't installed) on a
 * search for [query]. The traveller finds the place and either shares it to
 * Koode or copies its link and comes back — both fill the waiting field.
 */
fun openGoogleMaps(context: android.content.Context, query: String) {
    val url = if (query.isBlank()) "https://www.google.com/maps"
    else "https://www.google.com/maps/search/?api=1&query=" + android.net.Uri.encode(query)
    val app = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(app)
    } catch (_: android.content.ActivityNotFoundException) {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }
}
