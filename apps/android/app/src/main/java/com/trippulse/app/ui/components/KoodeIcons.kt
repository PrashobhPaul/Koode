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
}
