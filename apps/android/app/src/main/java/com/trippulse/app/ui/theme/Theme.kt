package com.trippulse.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Koode visual language, matched to the brand mark: deep navy night-sky
 * ground, the teal/blue of the two figures as the living accents. A calm,
 * high-contrast dark theme — teal signals "safe/live", amber signals
 * attention, red is reserved for SOS/concern.
 */
val Navy = Color(0xFF0B1E2D)
val Surface1 = Color(0xFF12283A)
val Surface2 = Color(0xFF1B3A50)
val Teal = Color(0xFF2DD4BF)
val Blue = Color(0xFF3B82F6)
val Amber = Color(0xFFF59E0B)
val Danger = Color(0xFFEF4444)
val TextHigh = Color(0xFFE8F1F5)
val TextMid = Color(0xFFA9C0CC)

private val Scheme = darkColorScheme(
    primary = Teal,
    onPrimary = Navy,
    secondary = Amber,
    onSecondary = Navy,
    tertiary = Surface2,
    background = Navy,
    onBackground = TextHigh,
    surface = Surface1,
    onSurface = TextHigh,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMid,
    error = Danger,
    onError = Color.White
)

private val AppTypography = Typography()

@Composable
fun TripPulseTheme(content: @Composable () -> Unit) {
    // App is dark-first regardless of system setting for a consistent identity.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
