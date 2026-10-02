package com.trippulse.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val detail: String?,
    /** A bundled illustration for stops that have one (fuel, food, toilet, stay). */
    val art: Int? = null,
    /** The event behind the line, so the traveller's own entries can be corrected. */
    val eventId: String? = null,
    val type: String? = null,
    val payload: Map<String, Any?> = emptyMap()
) {
    /** The traveller may re-time or remove this entry. */
    val editable: Boolean get() = eventId != null && type != null && type in EventTypes.USER_EDITABLE
    val breakId: String? get() = (payload["breakId"] as? String)?.takeIf { type == EventTypes.BREAK_CHECKPOINT }
}

/** One event as every timeline takes it, whatever store it came from. */
data class TimelineEvent(val id: String?, val type: String, val timeMs: Long, val payload: Map<String, Any?>)

/** Builds the timeline model from raw payload maps (shared by both sides). */
fun timelineItems(events: List<TimelineEvent>, limit: Int = 60): List<TimelineItem> {
    val edits = com.trippulse.app.domain.TimelineEdits
    val corrected = edits.apply(events, { it.id }, { it.type }, { it.timeMs }, { it.payload }, { e, p -> e.copy(payload = p) })
    return com.trippulse.app.domain.BreakTimeline
        .forTimeline(corrected, { it.type }, { it.timeMs }, { it.payload })
        .filter { EventTypes.inTimeline(it.type, it.payload) }
        // Shown and ordered by when it happened, not when it was logged: a
        // dinner logged after the night's halt still sits at dinner time.
        .sortedByDescending { edits.shownTime(it.type, it.payload, it.timeMs) }
        .take(limit)
        .map { e ->
            val (emoji, label) = eventLine(e.type, e.payload)
            TimelineItem(
                edits.shownTime(e.type, e.payload, e.timeMs), emoji, label, null,
                com.trippulse.app.ui.components.KoodeArt.event(e.type, e.payload),
                eventId = e.id, type = e.type, payload = e.payload
            )
        }
}

/**
 * The timeline. A connecting rail runs down the left so a sequence of events
 * reads as one journey rather than a list of unrelated rows.
 */
@Composable
fun TimelineList(
    items: List<TimelineItem>, nowMs: Long, modifier: Modifier = Modifier,
    /** When given, the traveller's own entries become tappable (to correct them). */
    onEdit: ((TimelineItem) -> Unit)? = null
) {
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
            val tappable = onEdit != null && item.editable
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (tappable) Modifier.clickable { onEdit!!(item) } else Modifier),
                verticalAlignment = Alignment.Top
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
                    if (item.art != null) com.trippulse.app.ui.components.ArtImage(item.art, 26.dp)
                    else Text(item.emoji, fontSize = 15.sp)
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
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        TimeFmt.clock(item.timeMs),
                        color = colors.textLow,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (tappable) Text("edit", color = colors.textLow.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** A "last logged" line: factual, never a judgement about the traveller. */
@Composable
fun WellbeingRow(
    emoji: String, label: String, atMs: Long?, nowMs: Long,
    @androidx.annotation.DrawableRes art: Int? = null
) {
    val colors = KoodeTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (art != null) com.trippulse.app.ui.components.ArtImage(art, 36.dp)
            else Text(emoji, fontSize = 15.sp)
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
