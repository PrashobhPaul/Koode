package com.trippulse.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Koode's own line icons — one stroke weight, round caps, 24-unit grid — so
 * navigation and actions read as one designed set instead of emoji, which
 * render differently on every phone. Tint them with Material3 `Icon(tint=…)`.
 */
object KoodeIcons {

    private fun stroke(name: String, vararg paths: String, width: Float = 1.8f): ImageVector {
        val b = ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        )
        for (d in paths) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return b.build()
    }

    /**
     * The selected-tab weight of an icon: [solid] paths are filled, the rest
     * stroked a little heavier — the outlined/filled pair global apps use so
     * the current tab reads at a glance, even without colour.
     */
    private fun solid(name: String, solid: List<String>, lines: List<String> = emptyList(), width: Float = 2.2f): ImageVector {
        val b = ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        )
        for (d in solid) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        for (d in lines) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return b.build()
    }

    /** A circle as path data (cx, cy, r). */
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    val Home: ImageVector by lazy {
        stroke("home", "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")
    }
    val Journeys: ImageVector by lazy {
        stroke(
            "journeys", circle(6f, 17f, 2f), circle(18f, 7f, 2f),
            "M6 15V9a4 4 0 0 1 4-4h2M18 9v6a4 4 0 0 1-4 4h-2"
        )
    }
    val Circle: ImageVector by lazy {
        stroke(
            "circle", circle(9f, 8f, 3.5f), "M2.5 20a6.5 6.5 0 0 1 13 0",
            "M16 4.5a3.5 3.5 0 0 1 0 7M18 14.5a6.5 6.5 0 0 1 3.5 5.5"
        )
    }
    val More: ImageVector by lazy {
        stroke("more", "M4 7h16M4 12h16M4 17h10")
    }
    val Plus: ImageVector by lazy { stroke("plus", "M12 5v14M5 12h14", width = 2.2f) }
    val Back: ImageVector by lazy { stroke("back", "M15 6l-6 6 6 6") }
    val Chevron: ImageVector by lazy { stroke("chevron", "M9 6l6 6-6 6") }
    val Shield: ImageVector by lazy {
        stroke("shield", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z", "M8.5 12l2.5 2.5 4.5-5")
    }
    val Pin: ImageVector by lazy {
        stroke("pin", "M12 21s-7-6.2-7-12a7 7 0 0 1 14 0c0 5.8-7 12-7 12z", circle(12f, 9f, 2.5f))
    }
    val Alert: ImageVector by lazy {
        stroke("alert", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z", "M12 8v5M12 16h.01")
    }
    val Signal: ImageVector by lazy {
        stroke("signal", "M5 12a10 10 0 0 1 14 0M8.5 15.5a5 5 0 0 1 7 0", "M12 19h.01")
    }
    val Share: ImageVector by lazy {
        stroke("share", "M12 3v12M7 8l5-5 5 5", "M5 13v7h14v-7")
    }

    // ---- selected-tab variants ----
    val HomeSelected: ImageVector by lazy {
        solid("home_selected", listOf("M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"))
    }
    val JourneysSelected: ImageVector by lazy {
        solid(
            "journeys_selected", listOf(circle(6f, 17f, 2.3f), circle(18f, 7f, 2.3f)),
            listOf("M6 15V9a4 4 0 0 1 4-4h2M18 9v6a4 4 0 0 1-4 4h-2")
        )
    }
    val CircleSelected: ImageVector by lazy {
        solid(
            "circle_selected", listOf(circle(9f, 8f, 3.5f), "M2.5 20a6.5 6.5 0 0 1 13 0z"),
            listOf("M16 4.5a3.5 3.5 0 0 1 0 7M18 14.5a6.5 6.5 0 0 1 3.5 5.5")
        )
    }
    val MoreSelected: ImageVector by lazy {
        solid("more_selected", emptyList(), listOf("M4 7h16M4 12h16M4 17h10"), width = 2.5f)
    }

    // ---- top bar actions ----
    /** Activity: what needs a look. */
    val Bell: ImageVector by lazy {
        stroke("bell", "M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20.5a2.2 2.2 0 0 0 4 0")
    }
    /** Follow a journey: a link being joined. */
    val Follow: ImageVector by lazy {
        stroke(
            "follow",
            "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1.2 1.2",
            "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1.2-1.2"
        )
    }
    val Close: ImageVector by lazy { stroke("close", "M6 6l12 12M18 6L6 18") }

    // ---- settings ----
    val Lock: ImageVector by lazy {
        stroke("lock", "M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 0 1 7 0v3")
    }
    val Sun: ImageVector by lazy {
        stroke(
            "sun", circle(12f, 12f, 4f),
            "M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"
        )
    }
    val Info: ImageVector by lazy { stroke("info", circle(12f, 12f, 9f), "M12 11v6M12 7.5h.01") }
    val Trash: ImageVector by lazy {
        stroke("trash", "M4 7h16M10 7V4.5h4V7", "M6.5 7l1 13h9l1-13", "M10 11v5M14 11v5")
    }
    val Calendar: ImageVector by lazy {
        stroke("calendar", "M4 6h16v14H4z", "M4 10h16M8 3.5v4M16 3.5v4")
    }
    val Arrow: ImageVector by lazy { stroke("arrow_down", "M12 5v14M7 14l5 5 5-5") }
}
