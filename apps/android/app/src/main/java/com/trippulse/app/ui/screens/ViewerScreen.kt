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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.domain.EtaMode
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.JourneyHealth
import com.trippulse.app.domain.JourneyStatus
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.ViewerVm
import com.trippulse.app.ui.theme.Amber
import com.trippulse.app.ui.theme.Danger
import com.trippulse.app.ui.theme.Surface2
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextHigh
import com.trippulse.app.ui.theme.TextMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun Map<String, Any?>.d(k: String): Double? = (this[k] as? Number)?.toDouble()
private fun Map<String, Any?>.l(k: String): Long? = (this[k] as? Number)?.toLong()
private fun Map<String, Any?>.str(k: String): String? = this[k] as? String
private fun Map<String, Any?>.bool(k: String): Boolean = this[k] as? Boolean ?: false

/**
 * The family-side experience: not a tracking console but a reassurance
 * channel. One glance answers the only real question — "are they okay?" —
 * via Journey Health, with wellbeing, ETA, map and timeline below it.
 */
@Composable
fun ViewerScreen(nav: NavHostController, accessKey: String) {
    val vm: ViewerVm = viewModel(factory = ViewerVm.factory(accessKey))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()

    val meta = ui.meta
    val state = ui.state
    var showEta by remember { mutableStateOf(false) }

    val current = state?.let { st -> st.d("lat")?.let { la -> st.d("lng")?.let { lo -> GeoPoint(la, lo) } } }
    val dest = meta?.let { m -> m.d("destLat")?.let { la -> m.d("destLng")?.let { lo -> GeoPoint(la, lo) } } }
    val origin = meta?.let { m -> m.d("originLat")?.let { la -> m.d("originLng")?.let { lo -> GeoPoint(la, lo) } } }

    // "Currently near Vijayawada" — reverse-geocoded on this phone, throttled
    // to ~1 km of movement so the free geocoder is barely touched.
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

    // Journey Health — evaluated fresh on every recomposition tick
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
                localHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                privateVehicle = (meta?.str("transportMode") ?: "CAR") in setOf("CAR", "BIKE")
            )
        )
    }
    val healthColor = when (health.level) {
        JourneyHealth.Level.NORMAL -> Teal
        JourneyHealth.Level.ATTENTION -> Amber
        JourneyHealth.Level.CONCERN -> Danger
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- header: whose journey, where to ----
        val owner = meta?.str("ownerName")
        Text(
            if (!owner.isNullOrBlank()) "$owner's Journey" else "Journey",
            color = TextHigh, fontSize = 22.sp, fontWeight = FontWeight.Bold
        )
        Text(
            "${meta?.str("origin") ?: "—"} → ${meta?.str("destination") ?: "—"}",
            color = TextMid, fontSize = 14.sp
        )

        // ---- SOS always outranks everything ----
        if (state?.bool("sosActive") == true) {
            SectionCard {
                Text("🚨 SOS active", color = Danger, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("The traveller has raised an emergency alert.", color = TextHigh, fontSize = 13.sp)
                current?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Last known location: %.5f, %.5f".format(it.lat, it.lng), color = TextMid, fontSize = 12.sp)
                }
            }
        }

        // ---- Journey Health: the one-glance answer ----
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(healthColor))
                Spacer(Modifier.height(0.dp))
                Text(
                    "  ${health.headline}",
                    color = healthColor, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
            }
            health.reasons.forEach { Text("• $it", color = TextMid, fontSize = 13.sp) }
            val lastAt = state?.l("lastLocationAt") ?: state?.l("updatedAt")
            if (lastAt != null) {
                Spacer(Modifier.height(4.dp))
                Text("Last updated ${TimeFmt.ago(now, lastAt)}", color = TextMid, fontSize = 11.sp)
            }
        }

        // ---- where & when ----
        SectionCard {
            val mode = state?.str("etaMode")
            val journey = state?.str("status")
            if (nearPlace != null) {
                Text("📍 Currently near $nearPlace", color = TextHigh, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(travelModeLine(journey, meta?.str("transportMode")), color = TextMid, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            when (mode) {
                EtaMode.OVERNIGHT_PENDING.name -> {
                    Text("Resting overnight", color = Amber, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    state?.str("overnightType")?.let { Text(overnightText(it), color = TextMid, fontSize = 13.sp) }
                }
                EtaMode.ARRIVED.name -> Text("Arrived safely 🎉", color = Teal, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                else -> {
                    Text("⏱ Expected arrival", color = TextMid, fontSize = 12.sp)
                    Text(
                        etaText(state?.l("etaLikely"), state?.l("etaLow"), state?.l("etaHigh")),
                        color = TextHigh, fontSize = 21.sp, fontWeight = FontWeight.Bold
                    )
                    if (state?.get("etaBreakdown") != null) {
                        TextButton(onClick = { showEta = true }) { Text("Why this estimate?", color = Teal, fontSize = 13.sp) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (state?.d("progress") ?: 0.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Teal, trackColor = Surface2
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("📍 ${TimeFmt.km(state?.d("distanceCoveredM") ?: 0.0)} completed", color = TextMid, fontSize = 12.sp)
                Text("${TimeFmt.km(state?.d("distanceRemainingM") ?: 0.0)} to go", color = TextMid, fontSize = 12.sp)
            }
        }

        // ---- journey wellbeing: factual "last logged", never medical ----
        SectionCard("JOURNEY WELLBEING") {
            LoggedRow("🍛", "Food", state?.l("foodAt"), now)
            LoggedRow("💧", "Water", state?.l("waterAt"), now)
            RestRow(state?.l("lastBreakEndAt"), state?.str("status"), now)
            state?.l("battery")?.let {
                InfoRow("📱", "Phone", "$it%")
            }
            InfoRow("🌙", "Overnight", overnightLine(state?.str("status")))
        }

        // ---- live map ----
        SectionCard("ROUTE") {
            MapPanel(
                current = current,
                origin = origin,
                destination = dest,
                route = emptyList(),
                heightDp = 220
            )
        }

        // ---- timeline ----
        val timeline = remember(ui.events) {
            ui.events
                .filter { (it["type"] as? String) in EventTypes.TIMELINE_TYPES }
                .sortedByDescending { (it["eventTime"] as? Number)?.toLong() ?: 0L }
                .take(40)
                .map { e ->
                    val type = e["type"] as? String ?: "EVENT"
                    val (emoji, label) = eventLabel(type)
                    TimelineItem((e["eventTime"] as? Number)?.toLong() ?: 0L, emoji, label, null)
                }
        }
        SectionCard("TIMELINE") { TimelineList(timeline, now) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { nav.navigate(Routes.replay(meta?.str("tripId") ?: accessKey)) }, modifier = Modifier.weight(1f)) {
                Text("Replay")
            }
            OutlinedButton(
                onClick = { vm.leave(); nav.popBackStack(Routes.HOME, inclusive = false) },
                modifier = Modifier.weight(1f)
            ) { Text("Leave") }
        }
        Spacer(Modifier.height(24.dp))
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
                    Text("Driving time: ${TimeFmt.durationShort(travel)}", color = TextHigh, fontSize = 14.sp)
                    Text("Planned breaks: ${TimeFmt.durationShort(breaks)}", color = TextHigh, fontSize = 14.sp)
                    Text("Buffer: ±${TimeFmt.durationShort(uncertainty)}", color = TextMid, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("The window narrows as the traveller gets closer.", color = TextMid, fontSize = 12.sp)
                }
            }
        )
    }
}

@Composable
private fun LoggedRow(emoji: String, label: String, atMs: Long?, now: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$emoji $label", color = TextHigh, fontSize = 14.sp)
        Text(
            if (atMs == null) "Not logged yet" else "Last logged ${TimeFmt.ago(now, atMs)}",
            color = TextMid, fontSize = 13.sp
        )
    }
}

@Composable
private fun RestRow(lastBreakEndMs: Long?, journey: String?, now: Long) {
    val stopped = journey in setOf(
        JourneyStatus.STOPPED.name, JourneyStatus.LONG_STOP.name,
        JourneyStatus.POSSIBLE_STOP.name, JourneyStatus.OVERNIGHT.name
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("🛑 Rest", color = TextHigh, fontSize = 14.sp)
        Text(
            when {
                stopped -> "Stopped now"
                lastBreakEndMs != null -> "Last stop ${TimeFmt.ago(now, lastBreakEndMs)}"
                else -> "No stop yet"
            },
            color = TextMid, fontSize = 13.sp
        )
    }
}

@Composable
private fun InfoRow(emoji: String, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$emoji $label", color = TextHigh, fontSize = 14.sp)
        Text(value, color = TextMid, fontSize = 13.sp)
    }
}

private fun modeEmoji(mode: String?): String = when (mode) {
    "BIKE" -> "🏍"; "BUS" -> "🚌"; "TRAIN" -> "🚆"; "FLIGHT" -> "✈️"; else -> "🚗"
}

private fun travelModeLine(journey: String?, mode: String?): String = when (journey) {
    JourneyStatus.DRIVING.name -> "${modeEmoji(mode)} Travelling" + when (mode) {
        "BIKE" -> " by bike"; "BUS" -> " by bus"; "TRAIN" -> " by train"; "FLIGHT" -> " by flight"; else -> ""
    }
    JourneyStatus.POSSIBLE_STOP.name -> "${modeEmoji(mode)} Slowing down"
    JourneyStatus.STOPPED.name -> "⏸ Taking a break"
    JourneyStatus.LONG_STOP.name -> "⏸ On a long stop"
    JourneyStatus.OVERNIGHT.name -> "🌙 Resting overnight"
    JourneyStatus.PAUSED.name -> "⏸ Paused"
    JourneyStatus.ARRIVED.name -> "🏁 At the destination"
    JourneyStatus.COMPLETED.name -> "🏁 Journey completed"
    JourneyStatus.READY.name -> "🕐 Getting ready to leave"
    else -> "—"
}

private fun overnightLine(journey: String?): String = when {
    journey == JourneyStatus.OVERNIGHT.name -> "Yes — stopped for the night"
    else -> {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (journey == JourneyStatus.DRIVING.name && (hour >= 23 || hour <= 4)) "Yes — currently travelling"
        else "No"
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
