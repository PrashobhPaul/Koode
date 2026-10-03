package com.trippulse.app.data.export.report

/**
 * Small line icons for what the app has no illustration of (a toll plaza, a
 * cup of tea, a lost signal). Drawn from primitives so they look the same on
 * every device and in every viewer, and sit quietly beside the illustrations.
 */
object Glyphs {

    /** Draws [name] centred on (cx, cy) inside a square of [size]. Unknown names draw a dot. */
    fun draw(s: Surface, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val u = size / 24f            // the icons are designed on a 24-unit grid
        fun x(v: Float) = cx + (v - 12f) * u
        fun y(v: Float) = cy + (v - 12f) * u
        val w = 1.9f * u
        when (name) {
            "toll" -> {
                // a booth, its barrier arm and the stripes on it
                s.strokeRect(x(3f), y(8f), x(8f), y(20f), color, w, 1.2f * u)
                s.rect(x(4.6f), y(10f), x(6.4f), y(12.5f), color)
                s.line(x(8f), y(11f), x(21f), y(11f), color, 2.6f * u)
                s.line(x(12f), y(9.8f), x(10.6f), y(12.2f), Ink.WHITE, 1.1f * u)
                s.line(x(16.4f), y(9.8f), x(15f), y(12.2f), Ink.WHITE, 1.1f * u)
                s.line(x(2f), y(20.5f), x(22f), y(20.5f), color, w)
            }
            "tea" -> {
                s.strokeRect(x(4f), y(9f), x(16f), y(19f), color, w, 2.6f * u)
                s.strokeCircle(x(17.6f), y(13.2f), 2.6f * u, color, w)
                s.line(x(3f), y(21f), x(17f), y(21f), color, w)
                s.line(x(8f), y(3.5f), x(8f), y(6.5f), color, 1.5f * u)
                s.line(x(12f), y(3.5f), x(12f), y(6.5f), color, 1.5f * u)
            }
            "snack" -> {
                s.strokeCircle(cx, cy, 9f * u, color, w)
                for ((a, b) in listOf(8f to 9f, 14f to 8f, 15f to 14f, 9f to 15f, 12f to 12f)) s.circle(x(a), y(b), 1.3f * u, color)
            }
            "offline" -> {
                s.rect(x(3f), y(16f), x(6f), y(20f), color, 0.6f * u)
                s.rect(x(8f), y(12f), x(11f), y(20f), color, 0.6f * u)
                s.rect(x(13f), y(8f), x(16f), y(20f), Ink.alpha(color, 0.35f), 0.6f * u)
                s.rect(x(18f), y(4f), x(21f), y(20f), Ink.alpha(color, 0.35f), 0.6f * u)
                s.line(x(3f), y(3f), x(21f), y(21f), color, w)
            }
            "phone" -> {
                s.strokeRect(x(7f), y(2.5f), x(17f), y(21.5f), color, w, 2.2f * u)
                s.line(x(10.5f), y(18.6f), x(13.5f), y(18.6f), color, w)
            }
            "sos" -> {
                s.polygon(floatArrayOf(x(12f), y(2.5f), x(22f), y(20.5f), x(2f), y(20.5f)), color)
                s.line(x(12f), y(9f), x(12f), y(14.5f), Ink.WHITE, 2.2f * u)
                s.circle(x(12f), y(17.6f), 1.3f * u, Ink.WHITE)
            }
            "note" -> {
                s.strokeRect(x(5f), y(3f), x(19f), y(21f), color, w, 1.8f * u)
                s.line(x(8.5f), y(8.5f), x(15.5f), y(8.5f), color, 1.5f * u)
                s.line(x(8.5f), y(12.5f), x(15.5f), y(12.5f), color, 1.5f * u)
                s.line(x(8.5f), y(16.5f), x(13f), y(16.5f), color, 1.5f * u)
            }
            "pin" -> {
                s.polygon(floatArrayOf(x(6.2f), y(11f), x(17.8f), y(11f), x(12f), y(22f)), color)
                s.circle(x(12f), y(9f), 6.4f * u, color)
                s.circle(x(12f), y(9f), 2.6f * u, Ink.WHITE)
            }
            "flag" -> {
                s.line(x(5f), y(2.5f), x(5f), y(21.5f), color, w)
                s.polygon(floatArrayOf(x(5.5f), y(3.5f), x(19f), y(3.5f), x(15.5f), y(8f), x(19f), y(12.5f), x(5.5f), y(12.5f)), color)
            }
            "clock" -> {
                s.strokeCircle(cx, cy, 9f * u, color, w)
                s.line(x(12f), y(12f), x(12f), y(6.8f), color, w)
                s.line(x(12f), y(12f), x(16f), y(14f), color, w)
            }
            "stopped" -> {
                s.strokeRect(x(3f), y(3f), x(21f), y(21f), color, w, 4f * u)
                s.text("P", x(8.2f), y(17.4f), TextStyle(Face.HEAD, 12.5f * u, color, 700))
            }
            "people" -> {
                s.circle(x(9f), y(8f), 3.4f * u, color)
                s.strokeRect(x(3.5f), y(13.5f), x(14.5f), y(21f), color, w, 4f * u)
                s.circle(x(17f), y(9f), 2.6f * u, Ink.alpha(color, 0.6f))
            }
            "swap" -> {
                s.line(x(4f), y(8f), x(19f), y(8f), color, w)
                s.polygon(floatArrayOf(x(20f), y(8f), x(15.5f), y(4.5f), x(15.5f), y(11.5f)), color)
                s.line(x(5f), y(16f), x(20f), y(16f), color, w)
                s.polygon(floatArrayOf(x(4f), y(16f), x(8.5f), y(12.5f), x(8.5f), y(19.5f)), color)
            }
            "wallet" -> {
                s.strokeRect(x(3f), y(6f), x(21f), y(20f), color, w, 2.6f * u)
                s.rect(x(14f), y(11f), x(21.5f), y(15.5f), color, 1.4f * u)
                s.circle(x(16.6f), y(13.2f), 1.1f * u, Ink.WHITE)
            }
            "receipt" -> {
                s.strokeRect(x(5f), y(2.5f), x(19f), y(21.5f), color, w, 1.4f * u)
                for (v in listOf(7.5f, 11f, 14.5f)) s.line(x(8.5f), y(v), x(15.5f), y(v), color, 1.4f * u)
            }
            "parking" -> draw(s, "stopped", cx, cy, size, color)
            "road" -> {
                s.line(x(8f), y(3f), x(5f), y(21f), color, w)
                s.line(x(16f), y(3f), x(19f), y(21f), color, w)
                for (v in listOf(5f, 11f, 17f)) s.line(x(12f), y(v), x(12f), y(v + 3f), color, 1.6f * u)
            }
            else -> s.circle(cx, cy, 3f * u, color)
        }
    }
}
