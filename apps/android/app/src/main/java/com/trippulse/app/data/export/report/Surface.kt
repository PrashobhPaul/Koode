package com.trippulse.app.data.export.report

/**
 * What a report page needs from whatever draws it.
 *
 * Reports are laid out once, in plain Kotlin, against this interface. On the
 * phone it is backed by Android's PdfDocument canvas (AndroidSurface); in
 * development the very same layout is drawn to an image so a page can be
 * looked at before it ships. Colours are ARGB ints; units are PDF points.
 */
interface Surface {
    fun measure(text: String, style: TextStyle): Float
    fun text(text: String, x: Float, baseline: Float, style: TextStyle)
    fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 0f)
    fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float = 0f)
    fun circle(cx: Float, cy: Float, r: Float, color: Int)
    fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, dash: Float = 0f)
    /** A smooth open polyline (round joins and caps). [pts] is x0,y0,x1,y1… */
    fun polyline(pts: FloatArray, color: Int, width: Float)
    /** A filled closed polygon. [pts] is x0,y0,x1,y1… */
    fun polygon(pts: FloatArray, color: Int)
    /** One of the app's illustrations (see domain.Pictures), fitted into the box. False if unavailable. */
    fun picture(name: String, l: Float, t: Float, w: Float, h: Float, mirrored: Boolean = false): Boolean
    /** The traveller's photo or initial, clipped to a circle. False if unavailable. */
    fun avatar(cx: Float, cy: Float, r: Float): Boolean
    /** The Koode mark. */
    fun mark(l: Float, t: Float, size: Float, alpha: Float = 1f): Boolean
}

enum class Face { HEAD, BODY }

/** [weight] 400 regular … 700 bold; [tracking] in em, as Android's letterSpacing. */
data class TextStyle(
    val face: Face,
    val size: Float,
    val color: Int,
    val weight: Int = 400,
    val tracking: Float = 0f
) {
    fun with(color: Int) = copy(color = color)
    /**
     * Sora, the heading face, has no rupee sign or arrows; text that needs
     * them is set in DM Sans, which does, rather than showing a blank box.
     */
    fun forText(text: String): TextStyle =
        if (face == Face.HEAD && text.any { it.code in 0x20A0..0x20CF || it.code in 0x2190..0x21FF }) copy(face = Face.BODY) else this

    /** Baseline-to-baseline distance for wrapped text. */
    val leading: Float get() = size * if (face == Face.HEAD) 1.25f else 1.45f
}

/** The Koode palette, for paper. */
object Ink {
    fun argb(hex: String): Int {
        val h = hex.removePrefix("#")
        val v = h.toLong(16)
        return if (h.length == 6) (0xFF000000 or v).toInt() else v.toInt()
    }
    fun alpha(color: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0x00FFFFFF)

    val NIGHT = argb("#0B1E2D")      // hero band, headings
    val NIGHT_2 = argb("#12293B")    // hero band, lower tone
    val TEXT = argb("#1C3242")
    val MUTED = argb("#5E7584")
    val FAINT = argb("#8EA2AE")
    val RULE = argb("#DCE6EB")
    val SOFT = argb("#F2F7F8")       // cards
    val SOFT_TEAL = argb("#E3F6F3")
    val TEAL = argb("#2DD4BF")       // on dark
    val TEAL_TEXT = argb("#0F766E")  // on white
    val TEAL_LINE = argb("#14B8A6")
    val SKY = argb("#0EA5E9")
    val GREEN = argb("#0E9F6E")
    val AMBER = argb("#B45309")
    val AMBER_SOFT = argb("#FDF3E3")
    val RED = argb("#B42318")
    val RED_DARK = argb("#4A1414")
    val RED_SOFT = argb("#FDECEA")
    val WHITE = argb("#FFFFFF")
    val ON_DARK = argb("#C7D6DE")
}

/** The type scale. Sora for headings, DM Sans for reading. */
object Type {
    val eyebrow = TextStyle(Face.BODY, 8.2f, Ink.TEAL_TEXT, 700, 0.12f)
    val heroTitle = TextStyle(Face.HEAD, 23f, Ink.WHITE, 700)
    val heroSub = TextStyle(Face.BODY, 10.5f, Ink.ON_DARK, 500)
    val h2 = TextStyle(Face.HEAD, 14.5f, Ink.NIGHT, 600)
    val h3 = TextStyle(Face.HEAD, 11.5f, Ink.NIGHT, 600)
    val body = TextStyle(Face.BODY, 10.4f, Ink.TEXT, 400)
    val bodyStrong = TextStyle(Face.BODY, 10.4f, Ink.NIGHT, 600)
    val small = TextStyle(Face.BODY, 8.6f, Ink.MUTED, 400)
    val smallStrong = TextStyle(Face.BODY, 8.6f, Ink.TEXT, 600)
    val tiny = TextStyle(Face.BODY, 7.4f, Ink.FAINT, 500, 0.06f)
    val figure = TextStyle(Face.HEAD, 17f, Ink.NIGHT, 700)
    val label = TextStyle(Face.BODY, 7.4f, Ink.MUTED, 600, 0.1f)
}

/** Wraps [text] to [width]; never splits a word unless the word alone is too wide. */
fun wrap(s: Surface, text: String, style: TextStyle, width: Float): List<String> {
    val out = ArrayList<String>()
    for (para in text.split('\n')) {
        var line = ""
        for (word in para.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (s.measure(candidate, style) <= width || line.isEmpty()) {
                line = candidate
            } else {
                out += line
                line = word
            }
        }
        out += line
    }
    return out.map { fit(s, it, style, width) }
}

/** Ellipsises a single line that cannot fit. */
fun fit(s: Surface, text: String, style: TextStyle, width: Float): String {
    if (s.measure(text, style) <= width) return text
    var t = text
    while (t.length > 1 && s.measure("$t…", style) > width) t = t.dropLast(1)
    return "${t.trimEnd()}…"
}
