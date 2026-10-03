package com.trippulse.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.domain.report.JourneyStory
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId

/**
 * The charts the reports draw, as Compose: a day as a strip of time, a ring
 * of shares, a row of bars. Same colours and the same meaning as on paper,
 * so what the traveller sees on the phone is what the PDF says.
 */
object ChartColors {
    val driving = Color(0xFF2DD4BF)
    val stopped = Color(0xFFF6C66B)
    val halt = Color(0xFF38BDF8)
    val offline = Color(0xFF6F8A99)
    val series = listOf(Color(0xFF2DD4BF), Color(0xFF38BDF8), Color(0xFFF59E0B), Color(0xFF34D399), Color(0xFFA78BFA), Color(0xFFF472B6), Color(0xFF94A3B8))
}

/** One span of time as a strip: moving, stopped, halted, out of contact. */
@Composable
fun DayStripChart(
    segments: List<JourneyStory.Segment>,
    fromMs: Long,
    toMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    nowMs: Long? = null,
    modifier: Modifier = Modifier
) {
    val colors = KoodeTheme.colors
    val span = (toMs - fromMs).coerceAtLeast(1L).toFloat()
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val w = size.width; val h = size.height
            val barTop = 2f; val barH = h - 10f
            fun px(t: Long) = ((t - fromMs) / span * w).coerceIn(0f, w)
            drawRoundRect(colors.backgroundElevated, Offset(0f, barTop), Size(w, barH), androidx.compose.ui.geometry.CornerRadius(barH / 2))
            for (seg in segments) {
                val a = px(maxOf(seg.fromMs, fromMs)); val b = px(minOf(seg.toMs, toMs))
                if (b - a < 0.5f) continue
                val c = when (seg.phase) {
                    JourneyStory.Phase.DRIVING -> ChartColors.driving
                    JourneyStory.Phase.STOPPED -> ChartColors.stopped
                    JourneyStory.Phase.HALT -> ChartColors.halt
                    JourneyStory.Phase.OFFLINE -> ChartColors.offline
                }
                drawRect(c, Offset(a, barTop), Size(b - a, barH))
                if (seg.phase == JourneyStory.Phase.OFFLINE) {
                    var hx = a + 3f
                    while (hx < b) { drawLine(colors.background, Offset(hx, barTop + barH - 1f), Offset(minOf(hx + barH - 2f, b), barTop + 1f), 2f); hx += 8f }
                }
            }
            // hour ticks
            val hours = ((toMs - fromMs) / 3_600_000.0).toInt()
            val step = when { hours <= 8 -> 1; hours <= 14 -> 2; else -> 3 }
            var t = Instant.ofEpochMilli(fromMs).atZone(zone).withMinute(0).withSecond(0).withNano(0)
            while (t.toInstant().toEpochMilli() <= toMs) {
                val ms = t.toInstant().toEpochMilli()
                if (ms >= fromMs && t.hour % step == 0) drawLine(colors.textLow, Offset(px(ms), barTop + barH + 2f), Offset(px(ms), barTop + barH + 6f), 1.5f)
                t = t.plusHours(1)
            }
            nowMs?.takeIf { it in fromMs..toMs }?.let { n ->
                drawLine(colors.warn, Offset(px(n), 0f), Offset(px(n), h), 3f, cap = StrokeCap.Round)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(TimeFmt.clock(fromMs), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
            Text(TimeFmt.clock(toMs), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            LegendKey(ChartColors.driving, "Moving")
            LegendKey(ChartColors.stopped, "Stopped")
            if (segments.any { it.phase == JourneyStory.Phase.HALT }) LegendKey(ChartColors.halt, "Halt")
            if (segments.any { it.phase == JourneyStory.Phase.OFFLINE }) LegendKey(ChartColors.offline, "Out of contact")
        }
    }
}

@Composable
private fun LegendKey(color: Color, label: String) {
    val colors = KoodeTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, color = colors.textLow, style = MaterialTheme.typography.labelSmall)
    }
}

/** A ring of shares with a legend beside it. */
@Composable
fun DonutChart(
    slices: List<Triple<String, Double, Int?>>,
    centreValue: String,
    centreLabel: String,
    valueText: (Double) -> String,
    modifier: Modifier = Modifier
) {
    val colors = KoodeTheme.colors
    val total = slices.sumOf { it.second }.coerceAtLeast(1e-9)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(132.dp)) {
                val stroke = 18.dp.toPx()
                val r = (size.minDimension - stroke) / 2
                val topLeft = Offset(size.width / 2 - r, size.height / 2 - r)
                val sz = Size(r * 2, r * 2)
                drawArc(colors.backgroundElevated, 0f, 360f, false, topLeft, sz, style = Stroke(stroke))
                var start = -90f
                slices.forEachIndexed { i, (_, v, _) ->
                    val sweep = (v / total * 360).toFloat()
                    if (sweep > 0.5f) drawArc(ChartColors.series[i % ChartColors.series.size], start, (sweep - 1.5f).coerceAtLeast(0.5f), false, topLeft, sz, style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centreValue, color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
                Text(centreLabel.uppercase(), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEachIndexed { i, (label, v, art) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(ChartColors.series[i % ChartColors.series.size]))
                    Spacer(Modifier.width(6.dp))
                    art?.let { ArtImage(it, 22.dp); Spacer(Modifier.width(4.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(label, color = colors.textHigh, style = MaterialTheme.typography.bodyMedium)
                        Text("${Math.round(v / total * 100).let { if (it == 0L && v > 0) "<1" else it }}%", color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(valueText(v), color = colors.textHigh, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** One bar: [estimated] is the part of [value] worked out rather than measured, drawn in the out-of-contact grey. */
data class ChartBar(val label: String, val value: Double, val estimated: Double = 0.0)

/** Vertical bars with a label under each. */
@Composable
fun BarsChart(
    bars: List<ChartBar>,
    valueText: (Double) -> String,
    color: Color = ChartColors.driving,
    modifier: Modifier = Modifier
) {
    val colors = KoodeTheme.colors
    if (bars.isEmpty()) return
    val max = bars.maxOf { it.value }.coerceAtLeast(1e-9)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            bars.forEach { b ->
                val v = b.value
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bars.size <= 10 && v > 0) Text(valueText(v), color = colors.textMid, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    val total = (80 * v / max).dp.coerceAtLeast(if (v > 0) 3.dp else 0.dp)
                    val est = if (v > 0) total * (b.estimated / v).toFloat().coerceIn(0f, 1f) else 0.dp
                    Column(Modifier.fillMaxWidth().height(total).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))) {
                        if (est > 0.dp) Box(Modifier.fillMaxWidth().height(est).background(ChartColors.offline))
                        Box(Modifier.fillMaxWidth().weight(1f, fill = true).background(color))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            bars.forEachIndexed { i, (label, _) ->
                Text(if (bars.size <= 12 || i % 2 == 0) label else "", color = colors.textLow, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** The story's highlight cards, as chips with their pictures. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HighlightChips(highlights: List<JourneyStory.Highlight>) {
    val colors = KoodeTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        highlights.forEach { h ->
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(colors.backgroundElevated).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KoodeArt.file(h.picture)?.let { ArtImage(it, 26.dp); Spacer(Modifier.width(6.dp)) }
                    ?: run { Text(glyphEmoji(h.glyph), style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.width(6.dp)) }
                Column {
                    Text(h.title, color = colors.textHigh, style = MaterialTheme.typography.bodyMedium)
                    if (h.detail.isNotBlank()) Text(h.detail, color = colors.textLow, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** What stands in for a picture the app does not have. */
fun glyphEmoji(glyph: String?): String = when (glyph) {
    "toll" -> "🛣"; "tea" -> "☕"; "snack" -> "🍪"; "offline" -> "📵"; "phone" -> "📱"; "sos" -> "🆘"
    "note" -> "💬"; "pin" -> "📍"; "flag" -> "🏁"; "clock" -> "🕒"; "stopped", "parking" -> "🅿"; "people" -> "👥"
    "swap" -> "🔁"; "wallet" -> "👛"; "receipt" -> "🧾"; "road" -> "🛣"; else -> "•"
}
