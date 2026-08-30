package com.trippulse.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Koode palette.
 *
 * Anchored on the brand mark: a deep night-sky navy ground with the teal and
 * sky-blue of the two travelling figures as the living accents. Colour carries
 * meaning here and nowhere else in the app invents its own:
 *
 *   teal   → safe, live, "everything is fine"
 *   sky    → the traveller themselves (the moving dot, their route)
 *   amber  → worth a look, not worth worrying about
 *   red    → SOS and genuine concern, and nothing else
 *
 * Both schemes are defined in full. Koode ships dark-first because most of its
 * moments happen at night, but a light scheme exists for daytime screens and
 * for the tablets and laptops the responsive layouts now target.
 */

// ---- brand constants (identical in both schemes) --------------------------
val Teal = Color(0xFF2DD4BF)
val TealDeep = Color(0xFF14B8A6)
val Sky = Color(0xFF38BDF8)
val SkyDeep = Color(0xFF0EA5E9)
val Amber = Color(0xFFF59E0B)
val Danger = Color(0xFFEF4444)
val Violet = Color(0xFFA78BFA)

// ---- dark scheme ----------------------------------------------------------
val Navy = Color(0xFF07131D)
val NavyElevated = Color(0xFF0E2231)
val Surface1 = Color(0xFF12283A)
val Surface2 = Color(0xFF1B3A50)
val Surface3 = Color(0xFF244B66)
val TextHigh = Color(0xFFEAF3F7)
val TextMid = Color(0xFFA9C0CC)
val TextLow = Color(0xFF6F8A99)
val OutlineDark = Color(0xFF23455C)

// ---- light scheme ---------------------------------------------------------
val Paper = Color(0xFFF4F8FA)
val PaperElevated = Color(0xFFFFFFFF)
val LightSurface1 = Color(0xFFFFFFFF)
val LightSurface2 = Color(0xFFE8F1F5)
val LightSurface3 = Color(0xFFD7E6ED)
val LightTextHigh = Color(0xFF0B1E2D)
val LightTextMid = Color(0xFF4A6273)
val LightTextLow = Color(0xFF7A93A2)
val OutlineLight = Color(0xFFC7DAE3)

/**
 * Semantic colours the components read, so a screen never has to ask "am I in
 * dark mode?" — it asks for `KoodeColors.current.surface` and gets the right
 * answer. This is what lets one component library serve both schemes without
 * a single conditional at the call site.
 */
data class KoodeColors(
    val isDark: Boolean,
    val background: Color,
    val backgroundElevated: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceHighest: Color,
    val outline: Color,
    val textHigh: Color,
    val textMid: Color,
    val textLow: Color,
    val accent: Color,
    val accentDeep: Color,
    val traveller: Color,
    val warn: Color,
    val danger: Color
) {
    companion object {
        val dark = KoodeColors(
            isDark = true,
            background = Navy, backgroundElevated = NavyElevated,
            surface = Surface1, surfaceRaised = Surface2, surfaceHighest = Surface3,
            outline = OutlineDark,
            textHigh = TextHigh, textMid = TextMid, textLow = TextLow,
            accent = Teal, accentDeep = TealDeep, traveller = Sky,
            warn = Amber, danger = Danger
        )
        val light = KoodeColors(
            isDark = false,
            background = Paper, backgroundElevated = PaperElevated,
            surface = LightSurface1, surfaceRaised = LightSurface2, surfaceHighest = LightSurface3,
            outline = OutlineLight,
            textHigh = LightTextHigh, textMid = LightTextMid, textLow = LightTextLow,
            accent = TealDeep, accentDeep = TealDeep, traveller = SkyDeep,
            warn = Amber, danger = Danger
        )
    }
}
