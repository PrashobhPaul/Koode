package com.trippulse.app.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.trippulse.app.TripPulseApp
import com.trippulse.app.core.TripCredentials
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.service.TripTrackingService
import com.trippulse.app.ui.Links
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.BackButton
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.KoodeHeroCard
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.QrImage
import com.trippulse.app.ui.components.SecondaryButton
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The hand-off screen: the two numbers that let someone follow this journey,
 * and the button that starts it.
 *
 * Both credentials are digits, shown large and grouped for reading aloud, with
 * their own copy buttons. The share message includes the browser link, so a
 * parent who will never install an app can still watch from a text message.
 */
@Composable
fun CredentialsScreen(nav: NavHostController, tripId: String) {
    val context = LocalContext.current
    val colors = KoodeTheme.colors
    val graph = (context.applicationContext as TripPulseApp).graph
    val scope = rememberCoroutineScope()
    val clipboard: ClipboardManager = LocalClipboardManager.current

    var trip by remember { mutableStateOf<ActiveTripEntity?>(null) }
    var starting by remember { mutableStateOf(false) }
    var permMessage by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tripId) { trip = graph.db.tripDao().byId(tripId) }

    var lateStartHere by remember { mutableStateOf<com.trippulse.app.domain.GeoPoint?>(null) }

    fun beginTrip(actualStartMs: Long? = null, here: com.trippulse.app.domain.GeoPoint? = null) {
        starting = true
        scope.launch {
            graph.tripManager.startTrip(tripId, actualStartMs, here)
            TripTrackingService.start(context)
            starting = false
            nav.navigate(Routes.driver(tripId)) { popUpTo(Routes.HOME) }
        }
    }

    /**
     * Pressed Start well away from the planned start, heading for the
     * destination? Then they set off earlier — ask when, so the journey's
     * times and distance cover the whole trip, not just from here.
     */
    fun checkThenBegin() {
        starting = true
        scope.launch {
            val here = currentFix(context)
            val t = trip
            val alreadyOnTheWay = t != null && here != null && run {
                val origin = com.trippulse.app.domain.GeoPoint(t.originLat, t.originLng)
                val dest = com.trippulse.app.domain.GeoPoint(t.destLat, t.destLng)
                com.trippulse.app.core.Geo.haversineM(origin, here) > LATE_START_METRES &&
                    com.trippulse.app.core.Geo.haversineM(here, dest) < com.trippulse.app.core.Geo.haversineM(origin, dest)
            }
            starting = false
            if (alreadyOnTheWay) lateStartHere = here else beginTrip()
        }
    }

    lateStartHere?.let { here ->
        val t = trip
        LateStartDialog(
            originName = t?.originName ?: "the start",
            kmAway = t?.let { com.trippulse.app.core.Geo.haversineM(com.trippulse.app.domain.GeoPoint(it.originLat, it.originLng), here) / 1000.0 } ?: 0.0,
            onStartedAgo = { minutes -> lateStartHere = null; beginTrip(System.currentTimeMillis() - minutes * 60_000L, here) },
            onStartingNow = { lateStartHere = null; beginTrip() },
            onDismiss = { lateStartHere = null }
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) checkThenBegin()
        else permMessage = "Koode needs location permission to follow your journey. Allow it to continue."
    }

    fun requestPermissionsAndStart() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }.toTypedArray()
        permLauncher.launch(perms)
    }

    val t = trip
    val code = t?.let { TripCredentials.digitsOf(it.tripId) }.orEmpty()

    fun shareText(includePasscode: Boolean): String = buildString {
        appendLine("🚗 I'm starting a journey — follow along on Koode.")
        appendLine("Koode will keep you informed along the way and when I arrive, without you having to call.")
        appendLine()
        appendLine("Journey number: ${t?.tripId ?: ""}")
        if (includePasscode) appendLine("Passcode: ${t?.secret ?: ""}")
        appendLine()
        if (includePasscode) {
            // One tap: the link carries the number and passcode in its fragment,
            // so it opens live tracking straight away — nothing to type.
            appendLine("Tap to watch live — nothing to install or type:")
            appendLine(Links.follow(code, t?.secret ?: ""))
        } else {
            appendLine("Watch in any web browser — nothing to install:")
            appendLine(Links.WEB_VIEWER)
        }
        appendLine()
        appendLine("Or get the Koode app (free):")
        appendLine(Links.APK)
        if (!includePasscode) {
            appendLine()
            append("Open Koode → People → Follow a journey → enter the number and your name. I'll approve you.")
        }
    }

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
            Text("Journey ready", color = colors.textHigh, style = MaterialTheme.typography.displaySmall)
            Text(
                "Share these two numbers with whoever should be able to follow you.",
                color = colors.textMid, style = MaterialTheme.typography.bodyLarge
            )

            KoodeHeroCard(accent = colors.accent) {
                Text("JOURNEY NUMBER", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    if (code.isEmpty()) "…" else TripCredentials.pretty(t!!.tripId),
                    color = colors.accent,
                    fontSize = 34.sp,
                    style = MaterialTheme.typography.displaySmall
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "Only the digits matter — the TP- is added by the app.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(Spacing.md))
                SecondaryButton(
                    if (copied == "id") "Copied ✓" else "Copy number",
                    {
                        clipboard.setText(AnnotatedString(code))
                        copied = "id"
                    },
                    height = 42.dp
                )
            }

            KoodeHeroCard(accent = colors.warn) {
                Text("PASSCODE", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    t?.secret ?: "…",
                    color = colors.warn,
                    fontSize = 34.sp,
                    style = MaterialTheme.typography.displaySmall
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "Six digits. Anyone with both numbers goes straight in; anyone with just the number has to be approved by you.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(Spacing.md))
                SecondaryButton(
                    if (copied == "pass") "Copied ✓" else "Copy passcode",
                    {
                        clipboard.setText(AnnotatedString(t?.secret.orEmpty()))
                        copied = "pass"
                    },
                    accent = colors.warn,
                    height = 42.dp
                )
            }

            KoodeCard(title = "Share") {
                Text(
                    "The message includes a one-tap link, so someone can follow you without installing anything or typing a number.",
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )

                // A QR of the same one-tap link, for someone sitting next to you.
                val followLink = if (code.isNotBlank() && !t?.secret.isNullOrBlank())
                    Links.follow(code, t?.secret ?: "") else null
                if (followLink != null) {
                    Spacer(Modifier.height(Spacing.md))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(Radii.md))
                                .background(Color.White)
                                .padding(Spacing.sm),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            QrImage(followLink, sizeDp = 176.dp)
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "Scan to follow this journey",
                            color = colors.textLow, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.weight(1f)) {
                        SecondaryButton("Share with passcode", {
                            if (t != null) context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText(includePasscode = true))
                                    },
                                    "Share journey"
                                )
                            )
                        }, height = 44.dp)
                    }
                    Box(Modifier.weight(1f)) {
                        SecondaryButton("Number only", {
                            if (t != null) context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText(includePasscode = false))
                                    },
                                    "Share journey"
                                )
                            )
                        }, accent = colors.textMid, height = 44.dp)
                    }
                }
            }

            if (!graph.cloudEnabledByDefault()) {
                Text(
                    "This build is in local mode, so remote followers can't connect until the cloud backend is configured. Tracking still works fully on this phone.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }

            if (permMessage != null) {
                KoodeCard(accent = colors.warn) {
                    Text(permMessage!!, color = colors.warn, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            PrimaryButton(
                text = if (starting) "Starting…" else "Start journey",
                onClick = { requestPermissionsAndStart() },
                enabled = t != null && !starting,
                leading = "🚦"
            )
            SecondaryButton("Back", { nav.popBackStack() }, accent = colors.textMid, height = 44.dp)
            Spacer(Modifier.height(Spacing.scrollBottom))
        }
    }
}


/** How far from the planned start counts as "already on the way". */
private const val LATE_START_METRES = 3_000.0

@android.annotation.SuppressLint("MissingPermission")
private suspend fun currentFix(context: android.content.Context): com.trippulse.app.domain.GeoPoint? =
    kotlinx.coroutines.withTimeoutOrNull(6_000) {
        try {
            val client = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
            val loc = client.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                com.google.android.gms.tasks.CancellationTokenSource().token
            ).await() ?: client.lastLocation.await()
            loc?.let { com.trippulse.app.domain.GeoPoint(it.latitude, it.longitude) }
        } catch (_: Exception) { null }
    }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LateStartDialog(
    originName: String,
    kmAway: Double,
    onStartedAgo: (Int) -> Unit,
    onStartingNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = com.trippulse.app.ui.theme.KoodeTheme.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Already on your way?") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "You're about %.0f km from %s. When did you actually set off? ".format(kmAway, originName) +
                        "Koode will time the journey from then and count the distance already covered.",
                    color = colors.textMid
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(com.trippulse.app.ui.theme.Spacing.md))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(com.trippulse.app.ui.theme.Spacing.sm)
                ) {
                    listOf(15 to "15 min ago", 30 to "30 min ago", 60 to "1 h ago", 90 to "1½ h ago",
                        120 to "2 h ago", 180 to "3 h ago", 240 to "4 h ago", 360 to "6 h ago")
                        .forEach { (m, label) -> com.trippulse.app.ui.components.KoodeChip(label, false, { onStartedAgo(m) }) }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onStartingNow) { Text("I'm starting here, now") } }
    )
}
