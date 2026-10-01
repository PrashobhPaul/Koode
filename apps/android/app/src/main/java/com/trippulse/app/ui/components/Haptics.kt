package com.trippulse.app.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * Koode's haptic vocabulary: four sensations, each with one meaning, so the
 * phone's response is as consistent as the visuals.
 *
 *   tick     a detent: a tab passing under the finger, a chip selected
 *   click    a deliberate tap on a primary control: tab, FAB, primary button
 *   confirm  something took effect: a journey started, a report approved
 *   heavy    a long press, or an action that cannot be undone
 *
 * Goes through the window's View, so the system's own touch-feedback setting
 * is honoured, and through Koode's "Vibrate on important taps" setting, so it
 * can be switched off entirely.
 */
class KoodeHaptics(private val view: View, private val enabled: Boolean) {

    fun tick() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
        else HapticFeedbackConstants.CLOCK_TICK
    )

    fun click() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY
    )

    fun heavy() = perform(HapticFeedbackConstants.LONG_PRESS)

    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }
}

/** Provided once at the root, carrying the user's haptics setting. */
val LocalHaptics = staticCompositionLocalOf<KoodeHaptics?> { null }

/** The haptics for this composition; enabled by default outside the app root. */
@Composable
fun rememberHaptics(): KoodeHaptics {
    val provided = LocalHaptics.current
    val view = LocalView.current
    return provided ?: remember(view) { KoodeHaptics(view, enabled = true) }
}
