package com.trippulse.app.data.export.report

/** An illustration (see domain.Pictures) or, where the app has none, a line glyph (see [Glyphs]). */
data class Icon(val picture: String? = null, val glyph: String? = null, val mirrored: Boolean = false) {
    val isEmpty: Boolean get() = picture == null && glyph == null
}

/** Draws [icon] fitted into the square at (l, t). Pictures fall back to their glyph. */
fun Surface.icon(icon: Icon, l: Float, t: Float, size: Float, glyphColor: Int = Ink.TEAL_TEXT) {
    val drawn = icon.picture?.let { picture(it, l, t, size, size, icon.mirrored) } == true
    if (!drawn) Glyphs.draw(this, icon.glyph ?: "dot", l + size / 2, t + size / 2, size * 0.86f, glyphColor)
}

/**
 * One piece of a page. Blocks measure themselves for a width and draw at a
 * position; the [Paginator] decides which page each lands on.
 */
abstract class Block {
    abstract fun height(s: Surface, w: Float): Float
    abstract fun draw(s: Surface, x: Float, y: Float, w: Float)
    /** Never leave this block alone at the foot of a page. */
    open val keepWithNext: Boolean = false
    /** Starts a fresh page. */
    open val newPage: Boolean = false
    /** Drawn across the whole page width, edge to edge. */
    open val fullBleed: Boolean = false
    /** Drawn again at the top of a page when the rows under it continue there. */
    open val repeatHeader: Block? = null
    /** Vertical space only: dropped at the top of a page. */
    open val isSpace: Boolean = false
}

class Space(private val h: Float) : Block() {
    override val isSpace = true
    override fun height(s: Surface, w: Float) = h
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {}
}

/** The band across the top of the first page. */
class Hero(
    private val eyebrow: String,
    private val title: String,
    private val lines: List<String>,
    private val pill: String? = null,
    private val picture: Icon? = null,
    private val figure: String? = null,
    private val figureLabel: String? = null,
    private val tone: Tone = Tone.NIGHT,
    private val showAvatar: Boolean = true
) : Block() {
    enum class Tone(val bg: Int, val accent: Int, val glow: Int) {
        NIGHT(Ink.NIGHT, Ink.TEAL, Ink.TEAL),
        ALERT(Ink.RED_DARK, Ink.argb("#FCA5A5"), Ink.argb("#F87171"))
    }

    override val fullBleed = true
    private val pad = Paginator.MARGIN
    private val titleStyle get() = Type.heroTitle
    private val figureStyle = TextStyle(Face.HEAD, 34f, Ink.WHITE, 700)

    private fun textWidth(w: Float) = w - pad * 2 - (if (picture != null) 150f else 40f)

    override fun height(s: Surface, w: Float): Float {
        var y = 78f
        y += 14f                                              // eyebrow
        y += wrap(s, title, titleStyle, textWidth(w)).size * titleStyle.leading
        if (figure != null) y += 44f
        y += 6f + lines.sumOf { wrap(s, it, Type.heroSub, textWidth(w)).size.toDouble() }.toFloat() * Type.heroSub.leading
        if (pill != null) y += 30f
        return maxOf(y + 26f, if (picture != null) 214f else 180f)
    }

    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        val h = height(s, w)
        s.rect(0f, y, w, y + h, tone.bg)
        // Quiet rings off the right edge: depth without decoration.
        for ((i, r) in listOf(170f, 120f, 74f).withIndex()) {
            s.strokeCircle(w - 40f, y + 46f, r, Ink.alpha(tone.glow, 0.10f + i * 0.03f), 1.1f)
        }
        s.rect(0f, y + h - 3f, w, y + h, tone.accent)

        // Brand row
        s.mark(pad, y + 26f, 20f)
        s.text("Koode", pad + 26f, y + 41f, TextStyle(Face.HEAD, 13f, Ink.WHITE, 700))
        s.text("Always with you", pad + 26f, y + 52f, TextStyle(Face.BODY, 7.2f, Ink.ON_DARK, 500, 0.04f))
        if (showAvatar) {
            val cx = w - pad - 18f; val cy = y + 40f
            s.circle(cx, cy, 19.5f, Ink.alpha(Ink.WHITE, 0.18f))
            s.avatar(cx, cy, 17.5f)
        }

        var ty = y + 86f
        s.text(eyebrow.uppercase(), pad, ty, Type.eyebrow.with(tone.accent))
        ty += 8f
        for (line in wrap(s, title, titleStyle, textWidth(w))) {
            ty += titleStyle.leading
            s.text(line, pad, ty - 5f, titleStyle)
        }
        if (figure != null) {
            ty += 40f
            s.text(figure, pad, ty, figureStyle)
            figureLabel?.let { s.text(it, pad + s.measure(figure, figureStyle) + 10f, ty, Type.heroSub) }
        }
        ty += 8f
        for (l in lines) for (line in wrap(s, l, Type.heroSub, textWidth(w))) {
            ty += Type.heroSub.leading
            s.text(line, pad, ty, Type.heroSub)
        }
        if (pill != null) {
            ty += 12f
            val ps = TextStyle(Face.BODY, 8.4f, tone.bg, 700, 0.06f)
            val pw = s.measure(pill.uppercase(), ps) + 22f
            s.rect(pad, ty, pad + pw, ty + 19f, tone.accent, 9.5f)
            s.text(pill.uppercase(), pad + 11f, ty + 13f, ps)
        }
        picture?.let {
            val size = 132f
            s.icon(it, w - pad - size + 6f, y + h - size - 14f, size)
        }
    }
}

/** Headline numbers in rounded cards. */
class Tiles(private val tiles: List<Tile>, private val columns: Int = 3) : Block() {
    data class Tile(val value: String, val label: String, val icon: Icon? = null, val accent: Boolean = false)

    private val rowH = 60f
    private val gap = 9f
    override fun height(s: Surface, w: Float): Float {
        val rows = (tiles.size + columns - 1) / columns
        return rows * rowH + (rows - 1).coerceAtLeast(0) * gap + 4f
    }

    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        val cw = (w - gap * (columns - 1)) / columns
        tiles.forEachIndexed { i, t ->
            val l = x + (i % columns) * (cw + gap)
            val top = y + (i / columns) * (rowH + gap)
            s.rect(l, top, l + cw, top + rowH, if (t.accent) Ink.SOFT_TEAL else Ink.SOFT, 11f)
            val iconSpace = if (t.icon != null) 34f else 0f
            t.icon?.let { s.icon(it, l + cw - 38f, top + 13f, 30f) }
            s.text(fit(s, t.value, Type.figure, cw - 26f - iconSpace), l + 13f, top + 30f, Type.figure)
            s.text(fit(s, t.label.uppercase(), Type.label, cw - 26f - iconSpace), l + 13f, top + 47f, Type.label)
        }
    }
}

/** A section title with a short teal rule under it. */
class Heading(private val title: String, private val kicker: String? = null) : Block() {
    override val keepWithNext = true
    override fun height(s: Surface, w: Float) = (if (kicker != null) 13f else 0f) + 32f
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        var ty = y
        kicker?.let { ty += 9f; s.text(it.uppercase(), x, ty, Type.eyebrow); ty += 4f }
        ty += 17f
        s.text(fit(s, title, Type.h2, w), x, ty, Type.h2)
        s.rect(x, ty + 7f, x + 24f, ty + 9.6f, Ink.TEAL_LINE, 1.3f)
    }
}

class Paragraph(
    private val text: String,
    private val style: TextStyle = Type.body,
    private val after: Float = 9f,
    private val indent: Float = 0f
) : Block() {
    override fun height(s: Surface, w: Float) = wrap(s, text, style, w - indent).size * style.leading + after
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        var ty = y
        for (line in wrap(s, text, style, w - indent)) {
            ty += style.leading
            s.text(line, x + indent, ty - style.size * 0.32f, style)
        }
    }
}

/** The recorded route, with each stop pinned on it by its picture. */
class RouteMap(
    private val path: List<Point>,
    private val markers: List<Marker>,
    private val startLabel: String?,
    private val endLabel: String?,
    private val endIcon: Icon? = null,
    private val h: Float = 214f,
    private val endIsCurrent: Boolean = false
) : Block() {
    data class Point(val tMs: Long, val lat: Double, val lng: Double)
    data class Marker(val lat: Double, val lng: Double, val icon: Icon)

    override fun height(s: Surface, w: Float) = h + 10f

    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        s.rect(x, y, x + w, y + h, Ink.SOFT, 14f)
        if (path.size < 2) {
            s.text("No route was recorded.", x + 16f, y + h / 2, Type.small)
            return
        }
        // light grid, like a paper map
        var gx = x + 24f
        while (gx < x + w) { s.line(gx, y + 1f, gx, y + h - 1f, Ink.alpha(Ink.RULE, 0.55f), 0.5f); gx += 30f }
        var gy = y + 18f
        while (gy < y + h) { s.line(x + 1f, gy, x + w - 1f, gy, Ink.alpha(Ink.RULE, 0.55f), 0.5f); gy += 30f }

        val lats = path.map { it.lat } + markers.map { it.lat }
        val lngs = path.map { it.lng } + markers.map { it.lng }
        val minLat = lats.min(); val maxLat = lats.max()
        val minLng = lngs.min(); val maxLng = lngs.max()
        val midLat = Math.toRadians((minLat + maxLat) / 2)
        val spanX = ((maxLng - minLng) * Math.cos(midLat)).coerceAtLeast(1e-4)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-4)
        val padX = 64f; val padY = 30f
        val scale = minOf((w - padX * 2) / spanX, (h - padY * 2) / spanY)
        val offX = x + (w - spanX * scale).toFloat() / 2
        val offY = y + (h - spanY * scale).toFloat() / 2
        fun px(lng: Double) = (offX + (lng - minLng) * Math.cos(midLat) * scale).toFloat()
        fun py(lat: Double) = (offY + (maxLat - lat) * scale).toFloat()

        // Draw in runs; a silence (no fixes for 20+ minutes) is a dashed straight line.
        var run = ArrayList<Float>()
        fun flush() { if (run.size >= 4) { s.polyline(run.toFloatArray(), Ink.alpha(Ink.TEAL_LINE, 0.28f), 7f); s.polyline(run.toFloatArray(), Ink.TEAL_TEXT, 2.6f) }; run = ArrayList() }
        for (i in path.indices) {
            val p = path[i]
            if (i > 0 && p.tMs - path[i - 1].tMs > 20 * 60_000L) {
                flush()
                s.line(px(path[i - 1].lng), py(path[i - 1].lat), px(p.lng), py(p.lat), Ink.MUTED, 1.6f, dash = 5f)
            }
            run.add(px(p.lng)); run.add(py(p.lat))
        }
        flush()

        for (m in markers) {
            val cx = px(m.lng); val cy = py(m.lat)
            s.circle(cx, cy + 0.8f, 10.5f, Ink.alpha(Ink.NIGHT, 0.12f))
            s.circle(cx, cy, 10f, Ink.WHITE)
            s.strokeCircle(cx, cy, 10f, Ink.TEAL_LINE, 1.1f)
            s.icon(m.icon, cx - 7.5f, cy - 7.5f, 15f)
        }
        val a = path.first(); val b = path.last()
        dot(s, px(a.lng), py(a.lat), Ink.GREEN, startLabel, x, w, leftSide = true)
        endIcon?.let {
            val cx = px(b.lng); val cy = py(b.lat)
            s.circle(cx, cy, 15f, Ink.WHITE); s.strokeCircle(cx, cy, 15f, if (endIsCurrent) Ink.AMBER else Ink.SKY, 1.6f)
            s.icon(it, cx - 11f, cy - 11f, 22f)
            endLabel?.let { l -> label(s, cx, cy - 22f, l, x, w) }
        } ?: dot(s, px(b.lng), py(b.lat), if (endIsCurrent) Ink.AMBER else Ink.SKY, endLabel, x, w, leftSide = false)
    }

    private fun dot(s: Surface, cx: Float, cy: Float, color: Int, text: String?, x: Float, w: Float, leftSide: Boolean) {
        s.circle(cx, cy, 7.5f, Ink.WHITE)
        s.circle(cx, cy, 5f, color)
        text?.let { label(s, cx, cy + 20f, it, x, w) }
    }

    private fun label(s: Surface, cx: Float, base: Float, text: String, x: Float, w: Float) {
        val st = TextStyle(Face.BODY, 8.2f, Ink.NIGHT, 700)
        val t = fit(s, text, st, 150f)
        val tw = s.measure(t, st) + 14f
        val l = (cx - tw / 2).coerceIn(x + 6f, x + w - tw - 6f)
        s.rect(l, base - 11f, l + tw, base + 4f, Ink.alpha(Ink.WHITE, 0.94f), 7.5f)
        s.text(t, l + 7f, base, st)
    }
}

/** Two cards side by side: an illustration, a headline, a line of detail. */
class HighlightRow(private val left: Item, private val right: Item?) : Block() {
    data class Item(val icon: Icon, val title: String, val detail: String)
    private val h = 54f
    override fun height(s: Surface, w: Float) = h + 8f
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        val cw = (w - 9f) / 2
        card(s, left, x, y, cw)
        right?.let { card(s, it, x + cw + 9f, y, cw) }
    }

    private fun card(s: Surface, it: Item, l: Float, t: Float, cw: Float) {
        s.rect(l, t, l + cw, t + h, Ink.SOFT, 11f)
        s.circle(l + 27f, t + h / 2, 19f, Ink.WHITE)
        s.icon(it.icon, l + 12f, t + h / 2 - 15f, 30f)
        val tx = l + 54f; val tw = cw - 64f
        s.text(fit(s, it.title, Type.bodyStrong, tw), tx, t + 23f, Type.bodyStrong)
        val lines = wrap(s, it.detail, Type.small, tw).take(2)
        lines.forEachIndexed { i, line -> s.text(if (i == 1 && lines.size == 2) fit(s, line, Type.small, tw) else line, tx, t + 36f + i * 11f, Type.small) }
    }
}

/** "DAY 1 · Friday, 2 October" */
class DayHeader(private val number: Int, private val title: String, private val note: String? = null) : Block() {
    override val keepWithNext = true
    override fun height(s: Surface, w: Float) = 34f
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        val ps = TextStyle(Face.BODY, 7.8f, Ink.WHITE, 700, 0.1f)
        val label = "DAY $number"
        val pw = s.measure(label, ps) + 16f
        s.rect(x, y + 8f, x + pw, y + 24f, Ink.NIGHT, 8f)
        s.text(label, x + 8f, y + 19.2f, ps)
        s.text(title, x + pw + 10f, y + 20.5f, Type.h3)
        note?.let { s.text(it, x + w - s.measure(it, Type.small), y + 20f, Type.small) }
    }
}

/** One stop on the journey: time, a pinned picture on the rail, what happened. */
class TimelineNode(
    private val time: String,
    private val timeSub: String?,
    private val icon: Icon,
    private val title: String,
    private val details: List<String>,
    private val chips: List<Pair<Icon, String>> = emptyList(),
    private val railAbove: Boolean = true,
    private val railBelow: Boolean = true,
    private val ring: Int = Ink.TEAL_LINE,
    private val strong: Boolean = false
) : Block() {
    private val railX = 76f
    private val textX = 104f
    private fun tw(w: Float) = w - textX
    private val titleStyle get() = if (strong) Type.h3 else Type.bodyStrong

    override fun height(s: Surface, w: Float): Float {
        var h = 6f + wrap(s, title, titleStyle, tw(w)).size * titleStyle.leading
        h += details.sumOf { wrap(s, it, Type.small, tw(w)).size.toDouble() }.toFloat() * Type.small.leading
        if (chips.isNotEmpty()) h += chipRows(s, w) * 20f + 4f
        return maxOf(h, 38f) + 10f
    }

    private fun chipRows(s: Surface, w: Float): Int {
        var rows = 1; var cx = 0f
        for ((_, t) in chips) {
            val cw = s.measure(t, chipStyle) + 30f
            if (cx + cw > tw(w) && cx > 0f) { rows++; cx = 0f }
            cx += cw + 6f
        }
        return rows
    }

    private val chipStyle = TextStyle(Face.BODY, 8f, Ink.TEXT, 600)

    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        val h = height(s, w)
        val rx = x + railX
        if (railAbove) s.line(rx, y, rx, y + 17f, Ink.RULE, 1.6f)
        if (railBelow) s.line(rx, y + 17f, rx, y + h, Ink.RULE, 1.6f)
        // time column
        s.text(time, x + 58f - s.measure(time, Type.smallStrong), y + 18f, Type.smallStrong)
        timeSub?.let { s.text(it, x + 58f - s.measure(it, Type.tiny), y + 29f, Type.tiny) }
        // node
        s.circle(rx, y + 17f, 16f, Ink.WHITE)
        s.strokeCircle(rx, y + 17f, 16f, ring, 1.4f)
        s.icon(icon, rx - 11.5f, y + 5.5f, 23f)
        // text
        var ty = y + 6f
        for (line in wrap(s, title, titleStyle, tw(w))) { ty += titleStyle.leading; s.text(line, x + textX, ty - 3f, titleStyle) }
        for (d in details) for (line in wrap(s, d, Type.small, tw(w))) { ty += Type.small.leading; s.text(line, x + textX, ty - 1f, Type.small) }
        if (chips.isNotEmpty()) {
            ty += 6f
            var cx = x + textX
            for ((ic, t) in chips) {
                val cw = s.measure(t, chipStyle) + 30f
                if (cx + cw > x + w && cx > x + textX) { cx = x + textX; ty += 20f }
                s.rect(cx, ty, cx + cw, ty + 16f, Ink.SOFT_TEAL, 8f)
                s.icon(ic, cx + 4f, ty + 1.5f, 13f)
                s.text(t, cx + 21f, ty + 11.2f, chipStyle)
                cx += cw + 6f
            }
        }
    }
}

/** The road between two stops, told quietly on the rail. */
class TimelineLeg(private val text: String, private val sub: String? = null, private val dashed: Boolean = false, private val glyph: String = "road") : Block() {
    private val railX = 76f
    override fun height(s: Surface, w: Float) = if (sub != null) 34f else 24f
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        val h = height(s, w)
        val rx = x + railX
        s.line(rx, y, rx, y + h, if (dashed) Ink.FAINT else Ink.alpha(Ink.TEAL_LINE, 0.55f), if (dashed) 1.4f else 2.2f, dash = if (dashed) 3.5f else 0f)
        Glyphs.draw(s, glyph, x + 104f + 6f, y + h / 2 - (if (sub != null) 4f else 0f), 12f, Ink.MUTED)
        val st = TextStyle(Face.BODY, 8.8f, Ink.MUTED, 500)
        s.text(fit(s, text, st, w - 124f), x + 120f, y + h / 2 + 3f - (if (sub != null) 5f else 0f), st)
        sub?.let { s.text(fit(s, it, Type.tiny, w - 124f), x + 120f, y + h / 2 + 9f, Type.tiny) }
    }
}

/** Labelled facts in a card. */
class Facts(
    private val rows: List<Pair<String, String>>,
    private val title: String? = null,
    private val background: Int = Ink.SOFT,
    private val labelWidth: Float = 150f
) : Block() {
    private val pad = 14f
    private fun valueLines(s: Surface, w: Float, v: String) = wrap(s, v, Type.smallStrong, w - pad * 2 - labelWidth)
    override fun height(s: Surface, w: Float): Float =
        pad * 2 + (if (title != null) 18f else 0f) + rows.sumOf { (_, v) -> valueLines(s, w, v).size * 13.0 + 6.0 }.toFloat() + 8f

    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        val h = height(s, w) - 8f
        s.rect(x, y, x + w, y + h, background, 12f)
        var ty = y + pad
        title?.let { ty += 10f; s.text(it.uppercase(), x + pad, ty, Type.label); ty += 8f }
        rows.forEachIndexed { i, (k, v) ->
            val lines = valueLines(s, w, v)
            s.text(fit(s, k, Type.small, labelWidth - 10f), x + pad, ty + 11f, Type.small)
            lines.forEachIndexed { j, l -> s.text(l, x + pad + labelWidth, ty + 11f + j * 13f, Type.smallStrong) }
            ty += lines.size * 13f + 6f
            if (i != rows.lastIndex) s.line(x + pad, ty - 1f, x + w - pad, ty - 1f, Ink.alpha(Ink.RULE, 0.8f), 0.6f)
        }
    }
}

/** A highlighted note: information, a caution or an alert. */
class Callout(private val text: String, private val glyph: String, private val tone: Tone = Tone.INFO) : Block() {
    enum class Tone(val bg: Int, val fg: Int) { INFO(Ink.SOFT_TEAL, Ink.TEAL_TEXT), WARN(Ink.AMBER_SOFT, Ink.AMBER), ALERT(Ink.RED_SOFT, Ink.RED) }
    override fun height(s: Surface, w: Float) = maxOf(40f, 22f + wrap(s, text, Type.body, w - 62f).size * Type.body.leading) + 10f
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        val h = height(s, w) - 10f
        s.rect(x, y, x + w, y + h, tone.bg, 12f)
        s.rect(x, y, x + 4f, y + h, tone.fg, 2f)
        Glyphs.draw(s, glyph, x + 28f, y + 22f, 18f, tone.fg)
        var ty = y + 9f
        for (line in wrap(s, text, Type.body, w - 62f)) { ty += Type.body.leading; s.text(line, x + 50f, ty, Type.body) }
    }
}

/** A share of a total: picture, label, a bar, the amount. */
class ShareBar(
    private val icon: Icon,
    private val label: String,
    private val sub: String?,
    private val value: String,
    private val share: Double,
    private val color: Int = Ink.TEAL_LINE
) : Block() {
    override fun height(s: Surface, w: Float) = 44f
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        s.circle(x + 17f, y + 18f, 17f, Ink.SOFT)
        s.icon(icon, x + 4f, y + 5f, 26f)
        val vx = x + w - s.measure(value, Type.bodyStrong)
        s.text(value, vx, y + 15f, Type.bodyStrong)
        val pct = when { share <= 0.0 -> ""; share < 0.005 -> "<1%"; else -> "${Math.round(share * 100)}%" }
        s.text(pct, x + w - s.measure(pct, Type.small), y + 28f, Type.small)
        s.text(fit(s, label, Type.bodyStrong, vx - x - 60f), x + 44f, y + 13f, Type.bodyStrong)
        sub?.let { s.text(fit(s, it, Type.small, vx - x - 60f), x + 44f, y + 24.5f, Type.small) }
        val bl = x + 44f; val br = x + w - 46f
        s.rect(bl, y + 31f, br, y + 36f, Ink.RULE, 2.5f)
        if (share > 0) s.rect(bl, y + 31f, bl + ((br - bl) * share.coerceIn(0.0, 1.0)).toFloat().coerceAtLeast(5f), y + 36f, color, 2.5f)
    }
}

/** A row of a ledger: picture, what, where and when, how much. */
class LedgerRow(
    private val icon: Icon,
    private val title: String,
    private val sub: String,
    private val amount: String,
    private val amountSub: String? = null,
    override val repeatHeader: Block? = null,
    private val muted: Boolean = false
) : Block() {
    override fun height(s: Surface, w: Float) = 38f
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        s.icon(icon, x + 2f, y + 6f, 24f)
        val aw = maxOf(s.measure(amount, Type.bodyStrong), amountSub?.let { s.measure(it, Type.tiny) } ?: 0f)
        val tw = w - 46f - aw - 14f
        s.text(fit(s, title, Type.bodyStrong.with(if (muted) Ink.MUTED else Ink.NIGHT), tw), x + 38f, y + 16f, Type.bodyStrong.with(if (muted) Ink.MUTED else Ink.NIGHT))
        s.text(fit(s, sub, Type.small, tw), x + 38f, y + 28f, Type.small)
        s.text(amount, x + w - s.measure(amount, Type.bodyStrong), y + 16f, Type.bodyStrong.with(if (muted) Ink.MUTED else Ink.NIGHT))
        amountSub?.let { s.text(it, x + w - s.measure(it, Type.tiny), y + 28f, Type.tiny) }
        s.line(x + 38f, y + 37f, x + w, y + 37f, Ink.alpha(Ink.RULE, 0.8f), 0.6f)
    }
}

/** A label over a ledger: "Friday, 2 October · ₹2,140". */
class LedgerHeader(private val left: String, private val right: String? = null) : Block() {
    override val keepWithNext = true
    override fun height(s: Surface, w: Float) = 24f
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        s.text(left.uppercase(), x, y + 15f, Type.label)
        right?.let { s.text(it, x + w - s.measure(it, Type.smallStrong), y + 15f, Type.smallStrong) }
        s.line(x, y + 21f, x + w, y + 21f, Ink.RULE, 0.8f)
    }
}

/** The total line under a ledger. */
class TotalRow(private val label: String, private val value: String, private val sub: String? = null) : Block() {
    override fun height(s: Surface, w: Float) = if (sub != null) 46f else 36f
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        s.rect(x, y + 4f, x + w, y + height(s, w) - 4f, Ink.NIGHT, 10f)
        val vs = TextStyle(Face.HEAD, 13f, Ink.WHITE, 700)
        s.text(label.uppercase(), x + 14f, y + 22f, TextStyle(Face.BODY, 8.2f, Ink.TEAL, 700, 0.1f))
        s.text(value, x + w - 14f - s.measure(value, vs), y + 24f, vs)
        sub?.let { s.text(it, x + 14f, y + 35f, TextStyle(Face.BODY, 7.8f, Ink.ON_DARK, 500)) }
    }
}

class Footnote(private val text: String) : Block() {
    override fun height(s: Surface, w: Float) = wrap(s, text, Type.small, w).size * Type.small.leading + 6f
    override fun draw(s: Surface, x: Float, y: Float, w: Float) {
        var ty = y
        for (l in wrap(s, text, Type.small, w)) { ty += Type.small.leading; s.text(l, x, ty, Type.small) }
    }
}
