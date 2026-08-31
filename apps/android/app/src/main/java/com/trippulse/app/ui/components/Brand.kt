package com.trippulse.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.R
import com.trippulse.app.ui.theme.KoodeTheme

/**
 * The Koode brand, in Compose.
 *
 * The launcher icon, the PDF and the splash all render the same vector mark
 * (`ic_koode_*`), and these composables are the in-app half of that: one glyph,
 * one wordmark, one night backdrop, used by the splash, the About screen and
 * the home header so the brand looks like one thing wherever it appears. Change
 * the vector once and every surface — icon, document, splash, header — follows.
 */

/** The plate-less mark, for the app's own dark surfaces. */
@Composable
fun KoodeGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    contentDescription: String? = "Koode"
) {
    Image(
        painter = painterResource(R.drawable.ic_koode_glyph),
        contentDescription = contentDescription,
        modifier = modifier.size(size)
    )
}

/** The plated mark — the app-icon chip, for a header or a list row. */
@Composable
fun KoodeMarkTile(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = "Koode"
) {
    Image(
        painter = painterResource(R.drawable.ic_koode_mark),
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
    )
}

/** The wordmark. Heavy and tightly tracked, so it reads as a logotype. */
@Composable
fun KoodeWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 34.sp,
    color: Color = KoodeTheme.colors.textHigh
) {
    Text(
        text = "Koode",
        modifier = modifier,
        color = color,
        style = TextStyle(
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
            letterSpacing = (fontSize.value * -0.02f).sp
        )
    )
}

/**
 * The Koode lockup: glyph, wordmark and (optionally) the tagline, stacked and
 * centred — the arrangement the splash and the About hero both use.
 */
@Composable
fun KoodeLockup(
    modifier: Modifier = Modifier,
    glyphSize: Dp = 108.dp,
    wordmarkSize: TextUnit = 40.sp,
    showTagline: Boolean = true
) {
    val colors = KoodeTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KoodeGlyph(size = glyphSize)
        Spacer(Modifier.height(14.dp))
        KoodeWordmark(fontSize = wordmarkSize, color = Color(0xFFF2FAFC))
        if (showTagline) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Know they're okay, without having to ask.",
                color = colors.accent,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "— Always with you —",
                color = colors.textLow,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * A subtle night backdrop that echoes the brand artwork: a navy gradient with a
 * faint city skyline, a few pines, a winding path and a scatter of stars. Kept
 * deliberately low-contrast — it sits *behind* the lockup and must never
 * compete with it. Drawn, not rastered, so it is crisp at any size and adds no
 * weight to the APK.
 */
@Composable
fun KoodeNightSky(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // sky
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(0xFF0E2740),
                0.55f to Color(0xFF091A29),
                1f to Color(0xFF06121D)
            )
        )

        // stars
        val starColor = Color(0xFFBFE0EC)
        listOf(
            0.16f to 0.12f, 0.82f to 0.10f, 0.68f to 0.20f,
            0.30f to 0.08f, 0.90f to 0.24f, 0.10f to 0.26f
        ).forEach { (fx, fy) ->
            drawCircle(starColor.copy(alpha = 0.35f), radius = w * 0.006f, center = Offset(w * fx, h * fy))
        }

        // winding path up the middle
        val path = Path().apply {
            moveTo(w * 0.46f, h)
            cubicTo(w * 0.40f, h * 0.86f, w * 0.60f, h * 0.80f, w * 0.52f, h * 0.66f)
            cubicTo(w * 0.46f, h * 0.56f, w * 0.56f, h * 0.50f, w * 0.50f, h * 0.40f)
        }
        drawPath(path, Color(0xFF2E5A72).copy(alpha = 0.30f), style = Stroke(width = w * 0.02f, cap = StrokeCap.Round))

        // city skyline, lower-left
        val sky = Color(0xFF12314A).copy(alpha = 0.55f)
        val base = h * 0.92f
        val buildings = listOf(
            0.02f to 0.14f, 0.09f to 0.20f, 0.15f to 0.11f, 0.20f to 0.24f, 0.27f to 0.16f
        )
        buildings.forEach { (fx, fht) ->
            val bx = w * fx
            val bh = h * fht
            drawRect(sky, topLeft = Offset(bx, base - bh), size = androidx.compose.ui.geometry.Size(w * 0.05f, bh))
        }

        // pines, lower-right
        val pine = Color(0xFF123A3A).copy(alpha = 0.55f)
        listOf(0.74f to 0.14f, 0.82f to 0.20f, 0.90f to 0.12f).forEach { (fx, fht) ->
            val cx = w * fx
            val ph = h * fht
            val tri = Path().apply {
                moveTo(cx, base - ph)
                lineTo(cx - w * 0.035f, base)
                lineTo(cx + w * 0.035f, base)
                close()
            }
            drawPath(tri, pine)
        }
    }
}

/** Standard inset used by the brand screens. */
val brandScreenPadding = PaddingValues(24.dp)
