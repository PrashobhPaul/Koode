package com.trippulse.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.ui.components.KoodeIcons
import com.trippulse.app.ui.components.KoodeMarkTile
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing

/** Whether this phone has seen the first-run welcome. */
object Onboarding {
    private const val PREFS = "koode_onboarding"
    private const val KEY = "welcome_seen"

    fun welcomeSeen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markWelcomeSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}

/**
 * The first thing a new person sees: what Koode does, in one sentence, and
 * the three promises that matter before they share a location — it is live
 * only during a journey, they choose who follows, and SOS is one tap.
 */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, onSkip: () -> Unit) {
    val colors = KoodeTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // Absorb taps so nothing on the Home screen beneath reacts.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KoodeMarkTile(size = 40.dp, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Koode", color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Know they're okay, without having to ask.",
                color = colors.textHigh,
                style = MaterialTheme.typography.displaySmall,
                fontSize = 32.sp, lineHeight = 38.sp
            )
            Text(
                "Start a journey and the people you choose hear about every stop, toll and arrival — automatically.",
                color = colors.textMid, style = MaterialTheme.typography.bodyLarge
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Promise(KoodeIcons.Pin, colors.accent, "Live, only during a journey", "Sharing ends when you end the trip")
            Promise(KoodeIcons.Circle, colors.traveller, "You choose who follows", "Approve each person before they see anything")
            Promise(KoodeIcons.Alert, colors.danger, "SOS in one tap", "With a few seconds to cancel a mistake")
        }

        Spacer(Modifier.height(Spacing.sm))

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PrimaryButton("Get started", onGetStarted)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(Radii.md))
                    .clickable(role = Role.Button, onClick = onSkip),
                contentAlignment = Alignment.Center
            ) {
                Text("Look around first", color = colors.accent, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Promise(icon: ImageVector, tint: Color, title: String, body: String) {
    val colors = KoodeTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.backgroundElevated)
            .border(1.dp, colors.surfaceRaised, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
            Text(body, color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
