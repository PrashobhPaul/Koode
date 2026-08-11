package com.trippulse.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.ViewerVm
import com.trippulse.app.ui.theme.Amber
import com.trippulse.app.ui.theme.Danger
import com.trippulse.app.ui.theme.Surface2
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextHigh
import com.trippulse.app.ui.theme.TextMid

private fun Map<String, Any?>.d(k: String): Double? = (this[k] as? Number)?.toDouble()
private fun Map<String, Any?>.l(k: String): Long? = (this[k] as? Number)?.toLong()
private fun Map<String, Any?>.str(k: String): String? = this[k] as? String
private fun Map<String, Any?>.bool(k: String): Boolean = this[k] as? Boolean ?: false

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

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(meta?.str("destination") ?: "Trip", color = TextHigh, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("from ${meta?.str("origin") ?: "—"}", color = TextMid, fontSize = 12.sp)
            }
            val lastAt = state?.l("lastLocationAt") ?: state?.l("updatedAt")
            FreshnessBadge(ui.freshness, lastAt?.let { TimeFmt.ago(now, it) })
        }

        // SOS banner takes priority
        if (state?.bool("sosActive") == true) {
            SectionCard {
                Text("🚨 SOS active", color = Danger, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("The driver has raised an emergency alert.", color = TextHigh, fontSize = 13.sp)
                val la = current
                if (la != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Last known location: %.5f, %.5f".format(la.lat, la.lng), color = TextMid, fontSize = 12.sp)
                }
            }
        }

        MapPanel(
            current = current,
            origin = origin,
            destination = dest,
            route = emptyList(),
            heightDp = 240
        )

        // ETA + progress
        SectionCard {
            val mode = state?.str("etaMode")
            when (mode) {
                EtaMode.OVERNIGHT_PENDING.name -> {
                    Text("Resting overnight", color = Amber, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    val type = state?.str("overnightType")
                    if (type != null) Text(overnightText(type), color = TextMid, fontSize = 13.sp)
                }
                EtaMode.ARRIVED.name -> Text("Arrived safely", color = Teal, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                else -> {
                    Text("Estimated arrival", color = TextMid, fontSize = 12.sp)
                    Text(
                        etaText(state?.l("etaLikely"), state?.l("etaLow"), state?.l("etaHigh")),
                        color = TextHigh, fontSize = 22.sp, fontWeight = FontWeight.Bold
                    )
                    if (state?.get("etaBreakdown") != null) {
                        TextButton(onClick = { showEta = true }) { Text("Why this estimate?", color = Teal, fontSize = 13.sp) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (state?.d("progress") ?: 0.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Teal, trackColor = Surface2
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${TimeFmt.km(state?.d("distanceCoveredM") ?: 0.0)} done", color = TextMid, fontSize = 12.sp)
                Text("${TimeFmt.km(state?.d("distanceRemainingM") ?: 0.0)} left", color = TextMid, fontSize = 12.sp)
            }
        }

        // wellbeing
        SectionCard("WELLBEING") {
            WellbeingRow("💧", "Water", state?.l("waterAt"), now)
            WellbeingRow("🍛", "Food", state?.l("foodAt"), now)
            WellbeingRow("🚻", "Toilet", state?.l("toiletAt"), now)
            WellbeingRow("😴", "Rest", state?.l("restAt"), now)
            WellbeingRow("⛽", "Fuel", state?.l("fuelAt"), now)
        }

        // timeline
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
                    Text("The window narrows as the driver gets closer.", color = TextMid, fontSize = 12.sp)
                }
            }
        )
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
