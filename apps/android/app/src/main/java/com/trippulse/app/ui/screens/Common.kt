package com.trippulse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Freshness
import com.trippulse.app.ui.theme.Amber
import com.trippulse.app.ui.theme.Danger
import com.trippulse.app.ui.theme.Surface1
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextMid

/** A titled surface card used throughout the dashboards. */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

data class FreshnessStyle(val color: Color, val dot: String, val label: String)

fun freshnessStyle(f: Freshness): FreshnessStyle = when (f) {
    Freshness.LIVE -> FreshnessStyle(Teal, "●", "LIVE")
    Freshness.RECENT -> FreshnessStyle(Amber, "●", "RECENT")
    Freshness.STALE -> FreshnessStyle(Amber, "◐", "STALE")
    Freshness.OFFLINE -> FreshnessStyle(Danger, "○", "OFFLINE")
    Freshness.COMPLETED -> FreshnessStyle(Teal, "✓", "COMPLETED")
    Freshness.UNKNOWN -> FreshnessStyle(TextMid, "○", "CONNECTING")
}

@Composable
fun FreshnessBadge(f: Freshness, lastUpdateText: String?) {
    val s = freshnessStyle(f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(s.color)
        )
        Spacer(Modifier.size(8.dp))
        Text(s.label, color = s.color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (lastUpdateText != null) {
            Spacer(Modifier.size(8.dp))
            Text(lastUpdateText, color = TextMid, fontSize = 12.sp)
        }
    }
}

/** Emoji + human label for an event type (docs/spec/42, 133). */
fun eventLabel(type: String): Pair<String, String> = when (type) {
    EventTypes.TRIP_STARTED -> "🚗" to "Trip started"
    EventTypes.TRIP_PAUSED -> "⏸" to "Trip paused"
    EventTypes.TRIP_RESUMED -> "▶" to "Trip resumed"
    EventTypes.TRIP_COMPLETED -> "🏁" to "Arrived"
    EventTypes.DESTINATION_CHANGED -> "🧭" to "Destination changed"
    EventTypes.STOP_STARTED -> "🅿" to "Vehicle stopped"
    EventTypes.STOP_ENDED -> "▶" to "Resumed"
    EventTypes.LONG_STOP -> "⏳" to "Long stop"
    EventTypes.ROUTE_DEVIATION -> "↩" to "Route deviation"
    EventTypes.ROUTE_REJOINED -> "↪" to "Back on route"
    EventTypes.ARRIVAL_DETECTED -> "📍" to "Arrival detected"
    EventTypes.BREAK_CHECKPOINT -> "✅" to "Break completed"
    EventTypes.WATER_REPORTED -> "💧" to "Water"
    EventTypes.FOOD_REPORTED -> "🍛" to "Food"
    EventTypes.TOILET_REPORTED -> "🚻" to "Toilet"
    EventTypes.REST_REPORTED -> "😴" to "Rest"
    EventTypes.FUEL_STOP -> "⛽" to "Fuel"
    EventTypes.CHARGE_STOP -> "🔌" to "Charging"
    EventTypes.OVERNIGHT_CONFIRMED -> "🌙" to "Overnight stay"
    EventTypes.MORNING_RESUME -> "🌅" to "Morning resume"
    EventTypes.QUICK_NOTE -> "📝" to "Note"
    EventTypes.PASSENGER_JOINED -> "👤" to "Passenger joined"
    EventTypes.PASSENGER_LEFT -> "👋" to "Passenger left"
    EventTypes.MEDICINE -> "💊" to "Medicine recorded"
    EventTypes.VEHICLE_ISSUE -> "🔧" to "Vehicle issue"
    EventTypes.INCIDENT -> "⚠" to "Incident"
    EventTypes.POSSIBLE_INCIDENT -> "⚠" to "Possible incident"
    EventTypes.SOS_ACTIVATED -> "🚨" to "SOS activated"
    EventTypes.SOS_RESOLVED -> "✅" to "SOS resolved"
    EventTypes.BATTERY_LOW -> "🔋" to "Battery low"
    else -> "•" to type.lowercase().replace('_', ' ')
}

data class TimelineItem(val timeMs: Long, val emoji: String, val label: String, val detail: String?)

@Composable
fun TimelineList(items: List<TimelineItem>, nowMs: Long) {
    if (items.isEmpty()) {
        Text("No events yet.", color = TextMid, fontSize = 13.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { it ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(it.emoji, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text(it.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (!it.detail.isNullOrBlank()) {
                        Text(it.detail, color = TextMid, fontSize = 12.sp)
                    }
                }
                Text(TimeFmt.clock(it.timeMs), color = TextMid, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun WellbeingRow(emoji: String, label: String, ageMs: Long?, nowMs: Long) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }
        Text(
            if (ageMs == null) "—" else TimeFmt.ago(nowMs, ageMs),
            color = TextMid, fontSize = 13.sp
        )
    }
}

val listPad = PaddingValues(16.dp)
