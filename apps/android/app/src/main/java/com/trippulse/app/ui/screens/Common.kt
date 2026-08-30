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
import com.trippulse.app.domain.EventNarrator
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Freshness
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
 * Screen-facing wrappers over [EventNarrator].
 *
 * The wording itself is domain knowledge, shared with the PDF exporter, so a
 * document someone was sent can never phrase an event differently from the
 * screen they watched it on.
 */
fun eventLabel(type: String): Pair<String, String> = EventNarrator.base(type)

fun eventLine(type: String, payload: Map<String, Any?>): Pair<String, String> =
    EventNarrator.line(type, payload)

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
