package com.trippulse.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.R
import com.trippulse.app.core.KoodeSettings

/** Semantic colours for the current scheme. Read via [KoodeTheme.colors]. */
val LocalKoodeColors = staticCompositionLocalOf { KoodeColors.dark }

/**
 * Spacing, radii and elevation as named tokens.
 *
 * Screens use these instead of literal dp values, which is the difference
 * between a UI that feels designed and one that merely compiles: the rhythm
 * stays consistent because there is only one place the rhythm is defined.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val section = 24.dp
    /** Bottom padding that keeps content clear of the tab bar and the FAB. */
    val scrollBottom = 120.dp
}

object Radii {
    val sm = 10.dp
    val md = 16.dp
    val lg = 22.dp
    val xl = 28.dp
    val pill = 999.dp
}

/**
 * Motion tokens. Every animation in Koode is short, spatial and subtle — it
 * exists to explain where something came from, never to be noticed.
 */
object Motion {
    const val fast = 120
    const val normal = 220
    const val slow = 380
    /** Scale a control shrinks to while pressed. */
    const val pressScale = 0.965f
}

/**
 * Display face: Sora — geometric and a little warm, used for headlines and
 * big numbers so the product has a recognisable voice. Bundled as a variable
 * font (res/font, SIL Open Font License); each weight is an instance of it.
 */
val DisplayFamily = FontFamily(
    Font(R.font.sora, FontWeight.Medium),
    Font(R.font.sora, FontWeight.SemiBold),
    Font(R.font.sora, FontWeight.Bold)
)

/** Body face: DM Sans — quiet and highly legible at small sizes. */
val BodyFamily = FontFamily(
    Font(R.font.dm_sans, FontWeight.Normal),
    Font(R.font.dm_sans, FontWeight.Medium),
    Font(R.font.dm_sans, FontWeight.SemiBold),
    Font(R.font.dm_sans, FontWeight.Bold)
)

/** The Koode type scale: Sora for what you read at a glance, DM Sans for the rest. */
private val KoodeTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 19.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp
    )
)

private fun darkScheme(c: KoodeColors) = darkColorScheme(
    primary = c.accent, onPrimary = Navy,
    secondary = c.traveller, onSecondary = Navy,
    tertiary = c.warn, onTertiary = Navy,
    background = c.background, onBackground = c.textHigh,
    surface = c.surface, onSurface = c.textHigh,
    surfaceVariant = c.surfaceRaised, onSurfaceVariant = c.textMid,
    outline = c.outline, outlineVariant = c.outline,
    error = c.danger, onError = Color.White
)

private fun lightScheme(c: KoodeColors) = lightColorScheme(
    primary = c.accent, onPrimary = Color.White,
    secondary = c.traveller, onSecondary = Color.White,
    tertiary = c.warn, onTertiary = Color.White,
    background = c.background, onBackground = c.textHigh,
    surface = c.surface, onSurface = c.textHigh,
    surfaceVariant = c.surfaceRaised, onSurfaceVariant = c.textMid,
    outline = c.outline, outlineVariant = c.outline,
    error = c.danger, onError = Color.White
)

/**
 * Wraps the app in the Koode design system.
 *
 * @param themeMode one of [KoodeSettings.THEME_SYSTEM] / `THEME_DARK` /
 *   `THEME_LIGHT`; the user picks this in Settings.
 */
@Composable
fun TripPulseTheme(
    themeMode: String = KoodeSettings.THEME_SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        KoodeSettings.THEME_DARK -> true
        KoodeSettings.THEME_LIGHT -> false
        else -> systemDark
    }
    val colors = if (dark) KoodeColors.dark else KoodeColors.light
    CompositionLocalProvider(LocalKoodeColors provides colors) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(colors) else lightScheme(colors),
            typography = KoodeTypography,
            content = content
        )
    }
}

/** Convenience accessor: `KoodeTheme.colors.accent`. */
object KoodeTheme {
    val colors: KoodeColors
        @Composable get() = LocalKoodeColors.current
}
