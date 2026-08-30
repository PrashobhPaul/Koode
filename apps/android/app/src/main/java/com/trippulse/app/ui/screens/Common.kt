package com.trippulse.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Freshness
import com.trippulse.app.domain.Nourishment
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.PulsingDot
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Spacing

/**
 * Shared presentation pieces: how an event becomes a line of English, and how
 * a list of them becomes a timeline. Both the traveller's screen and the
 * follower's screen render from here, which is what guarantees they agree.
 */

// ---------------------------------------------------------------------------
// Freshness
// ---------------------------------------------------------------------------

data class FreshnessStyle(val color: Color, val label: String, val pulsing: Boolean)

@Composable
fun freshnessStyle(f: Freshness): FreshnessStyle {
    val colors = KoodeTheme.colors
    return when (f) {
        Freshness.LIVE -> FreshnessStyle(colors.accent, "LIVE", true)
        Freshness.RECENT -> FreshnessStyle(colors.accent, "RECENT", false)
        Freshness.STALE -> FreshnessStyle(colors.warn, "CATCHING UP", false)
        // Deliberately not "OFFLINE": to the person watching, that reads as a
        // verdict on the traveller. It is a statement about the signal.
        Freshness.OFFLINE -> FreshnessStyle(colors.warn, "NO SIGNAL YET", false)
        Freshness.COMPLETED -> FreshnessStyle(colors.accent, "ENDED", false)
        Freshness.UNKNOWN -> FreshnessStyle(colors.textLow, "CONNECTING", false)
    }
}

@Composable
fun FreshnessBadge(f: Freshness, lastUpdateText: String?) {
    val colors = KoodeTheme.colors
    val s = freshnessStyle(f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (s.pulsing) {
            PulsingDot(s.color, size = 7.dp)
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(s.color))
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(s.label, color = s.color, style = MaterialTheme.typography.labelSmall)
        if (lastUpdateText != null) {
            Spacer(Modifier.width(Spacing.sm))
            Text(lastUpdateText, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------
// Event → English
// ---------------------------------------------------------------------------

/**
 * The emoji and label for an event type.
 *
 * Public-transport milestones deliberately carry their own vehicle-specific
 * wording, supplied by the event payload ("Boarded the train"), and fall back
 * to a neutral phrase when the payload is missing — a viewer on an old build
 * still reads something sensible.
 */
fun eventLabel(type: String): Pair<String, String> = when (type) {
    EventTypes.TRIP_STARTED -> "🚦" to "Journey started"
    EventTypes.TRIP_PAUSED -> "⏸" to "Journey paused"
    EventTypes.TRIP_RESUMED -> "▶" to "Journey resumed"
    EventTypes.TRIP_COMPLETED -> "🏁" to "Journey ended"
    EventTypes.DESTINATION_CHANGED -> "🧭" to "Destination changed"
    EventTypes.STOP_STARTED -> "🅿" to "Stopped"
    EventTypes.STOP_ENDED -> "▶" to "On the move again"
    EventTypes.LONG_STOP -> "⏳" to "Long stop"
    EventTypes.ROUTE_DEVIATION -> "↩" to "Off the usual route"
    EventTypes.ROUTE_REJOINED -> "↪" to "Back on route"
    EventTypes.ARRIVAL_DETECTED -> "📍" to "Reached the destination"
    EventTypes.BREAK_CHECKPOINT -> "✅" to "Break logged"
    EventTypes.WATER_REPORTED -> "💧" to "Water"
    EventTypes.FOOD_REPORTED -> "🍛" to "Food"
    EventTypes.TEA_COFFEE_REPORTED -> "☕" to "Tea / coffee"
    EventTypes.SNACK_REPORTED -> "🍪" to "Snack"
    EventTypes.TOILET_REPORTED -> "🚻" to "Toilet"
    EventTypes.REST_REPORTED -> "😴" to "Rest"
    EventTypes.FUEL_STOP -> "⛽" to "Refuelled"
    EventTypes.CHARGE_STOP -> "🔌" to "Charged"
    EventTypes.OVERNIGHT_CONFIRMED -> "🌙" to "Overnight stay"
    EventTypes.MORNING_RESUME -> "🌅" to "Back on the road"
    EventTypes.QUICK_NOTE -> "📝" to "Note"
    EventTypes.PASSENGER_JOINED -> "👤" to "Passenger joined"
    EventTypes.PASSENGER_LEFT -> "👋" to "Passenger left"
    EventTypes.MEDICINE -> "💊" to "Medicine recorded"
    EventTypes.VEHICLE_ISSUE -> "🔧" to "Vehicle issue"
    EventTypes.INCIDENT -> "⚠" to "Incident"
    EventTypes.POSSIBLE_INCIDENT -> "⚠" to "Possible incident"
    EventTypes.SOS_ACTIVATED -> "🚨" to "SOS activated"
    EventTypes.SOS_RESOLVED -> "✅" to "SOS resolved"
    EventTypes.BATTERY_LOW -> "🔋" to "Phone battery low"
    EventTypes.BOARDED -> "🎫" to "Boarded"
    EventTypes.TRANSIT_HALTED -> "⏸" to "Halted"
    EventTypes.TRANSIT_RESUMED -> "▶" to "Moving again"
    EventTypes.DEBOARDED -> "🚶" to "Got off"
    EventTypes.LEG_STARTED -> "🧭" to "Next stage started"
    EventTypes.LEG_COMPLETED -> "✅" to "Stage completed"
    else -> "•" to type.lowercase().replace('_', ' ')
}

/**
 * Builds the display line for an event, preferring anything the event itself
 * said. A meal event carries which meal it was; a transport milestone carries
 * its own sentence.
 */
fun eventLine(type: String, payload: Map<String, Any?>): Pair<String, String?> {
    val (emoji, label) = eventLabel(type)
    val text = payload["text"] as? String
    return when (type) {
        EventTypes.FOOD_REPORTED -> {
            val meal = Nourishment.fromKey(payload["meal"] as? String)
            (meal?.emoji ?: emoji) to (meal?.label ?: label)
        }
        EventTypes.BOARDED, EventTypes.TRANSIT_HALTED,
        EventTypes.TRANSIT_RESUMED, EventTypes.DEBOARDED,
        EventTypes.LEG_STARTED -> emoji to (text ?: label)
        else -> emoji to (text?.let { "$label — $it" } ?: label)
    }.let { (e, l) -> e to l }
}

data class TimelineItem(
    val timeMs: Long,
    val emoji: String,
    val label: String,
    val detail: String?
)

/** Builds the timeline model from raw payload maps (shared by both sides). */
fun timelineItems(
    events: List<Pair<String, Pair<Long, Map<String, Any?>>>>,
    limit: Int = 60
): List<TimelineItem> = events
    .filter { it.first in EventTypes.TIMELINE_TYPES }
    .sortedByDescending { it.second.first }
    .take(limit)
    .map { (type, rest) ->
        val (timeMs, payload) = rest
        val (emoji, label) = eventLine(type, payload)
        TimelineItem(timeMs, emoji, label, null)
    }

/**
 * The timeline. A connecting rail runs down the left so a sequence of events
 * reads as one journey rather than a list of unrelated rows.
 */
@Composable
fun TimelineList(items: List<TimelineItem>, nowMs: Long, modifier: Modifier = Modifier) {
    val colors = KoodeTheme.colors
    if (items.isEmpty()) {
        Text(
            "Nothing logged yet.",
            color = colors.textLow,
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    Column(modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
                    Text(item.emoji, fontSize = 15.sp)
                    if (index != items.lastIndex) {
                        Box(
                            Modifier
                                .width(1.5.dp)
                                .height(22.dp)
                                .background(colors.outline.copy(alpha = 0.7f))
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f).padding(bottom = Spacing.sm)) {
                    Text(item.label, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
                    if (!item.detail.isNullOrBlank()) {
                        Text(item.detail, color = colors.textMid, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    TimeFmt.clock(item.timeMs),
                    color = colors.textLow,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** A "last logged" line: factual, never a judgement about the traveller. */
@Composable
fun WellbeingRow(emoji: String, label: String, atMs: Long?, nowMs: Long) {
    val colors = KoodeTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 15.sp)
            Spacer(Modifier.width(Spacing.sm))
            Text(label, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            if (atMs == null) "Not logged yet" else TimeFmt.ago(nowMs, atMs),
            color = colors.textMid,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Slide-and-fade wrapper used for banners that appear mid-screen. */
@Composable
fun AnimatedBanner(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(160))
    ) { content() }
}

/** Retained for screens still calling the old name. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) = KoodeCard(modifier = modifier, title = title, content = content)

val listPad = PaddingValues(Spacing.lg)
