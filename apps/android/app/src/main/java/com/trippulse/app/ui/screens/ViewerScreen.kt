package com.trippulse.app.ui.screens

import android.location.Geocoder
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.TripPulseApp
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.domain.Darkness
import com.trippulse.app.domain.EtaMode
import com.trippulse.app.domain.Freshness
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.JourneyHealth
import com.trippulse.app.domain.JourneyStatus
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.ui.ViewerVm
import com.trippulse.app.data.export.JourneyPdf
import com.trippulse.app.ui.components.DetailRow
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.KoodeHeroCard
import com.trippulse.app.ui.components.LocalWindowClass
import com.trippulse.app.ui.components.PulsingDot
import com.trippulse.app.ui.components.SectionHeader
import com.trippulse.app.ui.components.StatusPill
import com.trippulse.app.ui.map.JourneyMap
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun Map<String, Any?>.d(k: String): Double? = (this[k] as? Number)?.toDouble()
private fun Map<String, Any?>.l(k: String): Long? = (this[k] as? Number)?.toLong()
private fun Map<String, Any?>.str(k: String): String? = this[k] as? String
private fun Map<String, Any?>.bool(k: String): Boolean = this[k] as? Boolean ?: false

/**
 * The family-side experience: not a tracking console but a reassurance
 * channel. One glance answers the only real question — "are they okay?"
 *
 * Two deliberate absences here. There is no **Leave** button: nobody watching
 * a journey wants a control that ends their view of it, and removing a journey
 * you follow belongs in the People list, not one tap from the map. And there is
 * no **Replay** button: the map replays itself with its own ▶ control.
 */
@Composable
fun ViewerScreen(nav: NavHostController, accessKey: String) {
    val vm: ViewerVm = viewModel(factory = ViewerVm.factory(accessKey))
    val colors = KoodeTheme.colors
    val windowClass = LocalWindowClass.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val breadcrumb by vm.breadcrumb.collectAsStateWithLifecycle()
    val dark by vm.darkness.collectAsStateWithLifecycle()
    val reportBusy by vm.reportBusy.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    // A follower reads distances in THEIR units, not the traveller's: a parent
    // in Kerala watching a child drive across Texas still wants kilometres.
    val measures = (LocalContext.current.applicationContext as TripPulseApp).graph.measures()

    val meta = ui.meta
    val state = ui.state
    var showEta by remember { mutableStateOf(false) }

    val current = state?.let { st -> st.d("lat")?.let { la -> st.d("lng")?.let { lo -> GeoPoint(la, lo) } } }
    val dest = meta?.let { m -> m.d("destLat")?.let { la -> m.d("destLng")?.let { lo -> GeoPoint(la, lo) } } }
    val origin = meta?.let { m -> m.d("originLat")?.let { la -> m.d("originLng")?.let { lo -> GeoPoint(la, lo) } } }

    // "Currently near Vijayawada" — reverse-geocoded on this phone, throttled
    // to roughly a kilometre of movement so the free geocoder is barely touched.
    val context = LocalContext.current
    var nearPlace by remember { mutableStateOf<String?>(null) }
    val geoKey = current?.let { "%.2f,%.2f".format(it.lat, it.lng) }
    LaunchedEffect(geoKey) {
        val c = current ?: return@LaunchedEffect
        nearPlace = withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                Geocoder(context).getFromLocation(c.lat, c.lng, 1)
                    ?.firstOrNull()
                    ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            } catch (_: Exception) { null }
        }
    }

    val transportMode = meta?.str("transportMode")
    val profile = TransportCatalog.profile(transportMode)
    val plannedDep = meta?.l("plannedDeparture")
    // Flight rule: offline during the expected flying window is normal.
    val offlineExpected = profile.expectsOfflineStretches && transportMode == "FLIGHT" &&
        plannedDep != null && now >= plannedDep - 30 * 60_000L && now <= plannedDep + 9 * 3_600_000L

    val health = remember(state, ui.freshness, now / 30_000) {
        JourneyHealth.evaluate(
            JourneyHealth.Inputs(
                nowMs = now,
                journey = state?.str("status"),
                freshness = ui.freshness,
                sosActive = state?.bool("sosActive") ?: false,
                deviationActive = state?.bool("deviationActive") ?: false,
                batteryPct = state?.l("battery")?.toInt(),
                foodAtMs = state?.l("foodAt"),
                waterAtMs = state?.l("waterAt"),
                lastBreakEndAtMs = state?.l("lastBreakEndAt"),
                stopStartedAtMs = null,
                drivingSinceMs = state?.l("drivingSince"),
                overnightType = state?.str("overnightType"),
                startedAtMs = meta?.l("startedAt"),
                localHour = TimeFmt.hourOfDay(now),
                privateVehicle = profile.isPrivateVehicle,
                offlineExpected = offlineExpected
            )
        )
    }
    val healthColor = when (health.level) {
        JourneyHealth.Level.NORMAL -> colors.accent
        JourneyHealth.Level.ATTENTION -> colors.warn
        JourneyHealth.Level.CONCERN -> colors.danger
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
            // ---- whose journey ----
            val owner = meta?.str("ownerName")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (!owner.isNullOrBlank()) "$owner's journey" else "Journey",
                        color = colors.textHigh, style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        "${meta?.str("origin") ?: "—"} → ${meta?.str("destination") ?: "—"}",
                        color = colors.textMid, style = MaterialTheme.typography.bodyLarge
                    )
                }
                FreshnessBadge(ui.freshness, null)
            }

            // ---- SOS outranks everything ----
            if (state?.bool("sosActive") == true) {
                KoodeHeroCard(accent = colors.danger) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(colors.danger, size = 9.dp)
                        Text("SOS active", color = colors.danger, style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        "The traveller has raised an emergency alert.",
                        color = colors.textHigh, style = MaterialTheme.typography.bodyLarge
                    )
                    current?.let {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "Last known location: %.5f, %.5f".format(it.lat, it.lng),
                            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // ---- the one-glance answer ----
            KoodeHeroCard(accent = healthColor) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(healthColor, size = 9.dp, active = ui.freshness == Freshness.LIVE)
                    Text(
                        when {
                            ui.endedByOwner -> "Journey ended safely"
                            ui.awaitingFirstRead -> "Getting the first update…"
                            else -> health.headline
                        },
                        color = healthColor, style = MaterialTheme.typography.headlineSmall
                    )
                }
                if (!ui.endedByOwner && !ui.awaitingFirstRead) {
                    health.reasons.forEach {
                        Text("• $it", color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val lastAt = state?.l("lastLocationAt") ?: state?.l("updatedAt")
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    when {
                        ui.endedByOwner -> "The traveller ended this journey."
                        lastAt != null -> "Last updated ${TimeFmt.ago(now, lastAt)}"
                        // Never "the journey ended": we simply haven't heard yet.
                        else -> "Waiting for the first update — this is about the signal, not about them."
                    },
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }

            // ---- the map: source, destination and the path so far ----
            JourneyMap(
                current = current,
                origin = origin,
                destination = dest,
                breadcrumb = breadcrumb,
                live = ui.freshness == Freshness.LIVE || ui.freshness == Freshness.RECENT,
                height = windowClass.mapHeight,
                showPlayControl = true
            )
            Text(
                "The line shows how far they'd got. Press ▶ to watch the journey play out.",
                color = colors.textLow, style = MaterialTheme.typography.bodySmall
            )

            // ---- where & when ----
            KoodeCard {
                val mode = state?.str("etaMode")
                val journey = state?.str("status")
                if (nearPlace != null) {
                    Text(
                        "📍 Currently near $nearPlace",
                        color = colors.textHigh, style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    if (offlineExpected && ui.freshness == Freshness.OFFLINE)
                        "✈️ In the air — offline as expected"
                    else travelModeLine(journey, transportMode),
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.sm))
                when {
                    ui.endedByOwner ->
                        Text("Arrived safely 🎉", color = colors.accent, style = MaterialTheme.typography.headlineSmall)
                    mode == EtaMode.OVERNIGHT_PENDING.name -> {
                        Text("Resting overnight", color = colors.warn, style = MaterialTheme.typography.titleMedium)
                        state?.str("overnightType")?.let {
                            Text(overnightText(it), color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> {
                        Text("EXPECTED ARRIVAL", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            etaText(state?.l("etaLikely"), state?.l("etaLow"), state?.l("etaHigh")),
                            color = colors.textHigh, style = MaterialTheme.typography.headlineMedium
                        )
                        if (state?.get("etaBreakdown") != null) {
                            TextButton(onClick = { showEta = true }) {
                                Text("Why this estimate?", color = colors.accent, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                LinearProgressIndicator(
                    progress = { (state?.d("progress") ?: 0.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(Radii.pill)),
                    color = colors.accent, trackColor = colors.surfaceRaised
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${measures.distance(state?.d("distanceCoveredM") ?: 0.0)} completed",
                        color = colors.textMid, style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${measures.distance(state?.d("distanceRemainingM") ?: 0.0)} to go",
                        color = colors.textMid, style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---- wellbeing: factual "last logged", never medical ----
            KoodeCard(title = "How they're doing") {
                WellbeingRow("🍛", "Food", state?.l("foodAt"), now)
                WellbeingRow("💧", "Water", state?.l("waterAt"), now)
                RestRow(state?.l("lastBreakEndAt"), state?.str("status"), profile.wellbeingIsBreak, now)
                state?.l("battery")?.let {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("📱 Phone", color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
                        Text("$it%", color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ---- the phone has gone quiet ----
            //
            // Placed above the timeline rather than below it: when this card
            // applies it is the only thing on the screen anybody is reading.
            if (dark.dark) {
                val label = (ui.meta?.get("label") as? String) ?: "They"
                KoodeCard(
                    title = Darkness.headline(dark, label),
                    accent = if (dark.concerning) colors.danger else colors.warn
                ) {
                    Text(
                        Darkness.detail(dark),
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    dark.sinceMs?.let {
                        Spacer(Modifier.height(Spacing.sm))
                        DetailRow("Last heard from", TimeFmt.ago(now, it))
                    }
                    if (dark.concerning) {
                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            "Koode is still watching, and will tell you the moment " +
                                "anything arrives. This journey stays open until they " +
                                "close it themselves.",
                            color = colors.textLow, style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(Spacing.md))
                        PrimaryButton(
                            if (reportBusy) "Preparing…" else "Save last known position",
                            {
                                vm.buildLastKnownReport { file ->
                                    if (file != null) {
                                        context.startActivity(
                                            JourneyPdf.shareIntent(
                                                context, file, "Last known position"
                                            )
                                        )
                                    }
                                }
                            },
                            enabled = !reportBusy,
                            leading = "📄"
                        )
                        Text(
                            "A PDF with the exact coordinates, the time, how accurate " +
                                "the fix was and what happened just before — the details " +
                                "a police report asks for.",
                            color = colors.textLow, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---- timeline ----
            SectionHeader("Timeline")
            KoodeCard {
                val timeline = remember(ui.events) {
                    timelineItems(
                        ui.events.map { e ->
                            @Suppress("UNCHECKED_CAST")
                            val payload = (e["payload"] as? Map<String, Any?>) ?: emptyMap()
                            (e["type"] as? String ?: "EVENT") to
                                (((e["eventTime"] as? Number)?.toLong() ?: 0L) to payload)
                        }
                    )
                }
                TimelineList(timeline, now)
            }
            Spacer(Modifier.height(Spacing.scrollBottom))
        }
    }

    if (showEta) {
        @Suppress("UNCHECKED_CAST")
        val bd = state?.get("etaBreakdown") as? Map<String, Any?>
        AlertDialog(
            onDismissRequest = { showEta = false },
            confirmButton = { TextButton(onClick = { showEta = false }) { Text("Got it") } },
            title = { Text("How the estimate is built") },
            text = {
                Column {
                    val travel = (bd?.get("travelSeconds") as? Number)?.toLong() ?: 0
                    val breaks = (bd?.get("breakBudgetSeconds") as? Number)?.toLong() ?: 0
                    val uncertainty = (bd?.get("uncertaintySeconds") as? Number)?.toLong() ?: 0
                    Text("Travel time: ${TimeFmt.durationShort(travel)}", color = colors.textHigh)
                    Text("Expected breaks: ${TimeFmt.durationShort(breaks)}", color = colors.textHigh)
                    Text("Buffer: ±${TimeFmt.durationShort(uncertainty)}", color = colors.textMid)
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "The window narrows as they get closer.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }
}

@Composable
private fun RestRow(lastBreakEndMs: Long?, journey: String?, breaksApply: Boolean, now: Long) {
    val colors = KoodeTheme.colors
    val stopped = journey in setOf(
        JourneyStatus.STOPPED.name, JourneyStatus.LONG_STOP.name,
        JourneyStatus.POSSIBLE_STOP.name, JourneyStatus.OVERNIGHT.name
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("🛑 Rest", color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                // On public transport a halt is the timetable, not a decision
                // the traveller made — so it is never reported as "a break".
                !breaksApply -> "Not applicable on this leg"
                stopped -> "Stopped now"
                lastBreakEndMs != null -> "Last break ${TimeFmt.ago(now, lastBreakEndMs)}"
                else -> "No break yet"
            },
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun travelModeLine(journey: String?, mode: String?): String {
    val profile = TransportCatalog.profile(mode)
    return when (journey) {
        JourneyStatus.DRIVING.name -> "${profile.emoji} Travelling${profile.travellingSuffix}"
        JourneyStatus.POSSIBLE_STOP.name -> "${profile.emoji} Slowing down"
        JourneyStatus.STOPPED.name -> if (profile.wellbeingIsBreak) "⏸ Taking a break" else "⏸ Halted"
        JourneyStatus.LONG_STOP.name -> "⏸ Stopped a while"
        JourneyStatus.OVERNIGHT.name -> "🌙 Resting overnight"
        JourneyStatus.PAUSED.name -> "⏸ Paused"
        JourneyStatus.ARRIVED.name -> "🏁 At the destination"
        JourneyStatus.COMPLETED.name -> "🏁 Journey ended"
        JourneyStatus.READY.name -> "🕐 Getting ready to leave"
        else -> "—"
    }
}

private fun overnightText(type: String): String = when (type) {
    "HOTEL" -> "Staying at a hotel or lodge."
    "FAMILY" -> "Staying with family or friends."
    "VEHICLE" -> "Resting in the vehicle."
    "HOME" -> "Resting at home."
    else -> "Stopped for the night."
}

private fun etaText(likely: Long?, low: Long?, high: Long?): String {
    if (likely == null) return "Calculating…"
    val l = low ?: likely
    val h = high ?: likely
    return "${TimeFmt.clockWithDay(l, System.currentTimeMillis())} – ${TimeFmt.clock(h)}"
}
