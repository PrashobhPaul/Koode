package com.trippulse.app.ui.screens

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.ui.components.KoodeGlyph
import com.trippulse.app.ui.components.KoodeNightSky
import com.trippulse.app.ui.components.KoodeWordmark
import com.trippulse.app.ui.theme.KoodeTheme
import kotlinx.coroutines.delay

/**
 * The in-app splash.
 *
 * The platform splash (a static mark on navy) hands over here the instant the
 * first frame is ready; this screen picks up from the same navy so the seam is
 * invisible, then brings the brand to life — the glyph settles in, the wordmark
 * and tagline rise — before routing on. It is a moment, not a wait: ~2 seconds,
 * skippable by a tap, and it never blocks the app behind a network call.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    fun finish() {
        if (!leaving) {
            leaving = true
            onDone()
        }
    }

    LaunchedEffect(Unit) {
        started = true
        delay(2100)
        finish()
    }

    // The glyph stays visible from the first frame so it continues the platform
    // splash's mark rather than fading up from blank navy; only its scale
    // settles. Everything else (the scene, the wordmark) fades in around it.
    val glyphScale by animateFloatAsState(
        targetValue = if (started) 1f else 0.86f,
        animationSpec = tween(760, easing = EaseOutBack), label = "glyphScale"
    )
    val sceneAlpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(900), label = "sceneAlpha"
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(640, delayMillis = 420, easing = EaseOutCubic), label = "textAlpha"
    )
    val textRise by animateFloatAsState(
        targetValue = if (started) 0f else 20f,
        animationSpec = tween(640, delayMillis = 420, easing = EaseOutCubic), label = "textRise"
    )

    // A slow breathing ring, so the mark feels alive rather than pasted on.
    val infinite = rememberInfiniteTransition(label = "splashPulse")
    val ring by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "ring"
    )

    val tap = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1E2D))
            .clickable(interactionSource = tap, indication = null) { finish() },
        contentAlignment = Alignment.Center
    ) {
        KoodeNightSky(Modifier.fillMaxSize().alpha(sceneAlpha * 0.9f))

        // breathing ring behind the glyph
        Box(
            Modifier
                .size((150 + 90 * ring).dp)
                .alpha((1f - ring) * 0.18f)
                .clip(CircleShape)
                .background(KoodeTheme.colors.accent)
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KoodeGlyph(
                size = 128.dp,
                modifier = Modifier.scale(glyphScale)
            )
            Spacer(Modifier.height(18.dp))
            KoodeWordmark(
                fontSize = 40.sp,
                color = Color(0xFFF2FAFC),
                modifier = Modifier
                    .alpha(textAlpha)
                    .graphicsLayer { translationY = textRise }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Always with you",
                color = KoodeTheme.colors.accent,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .alpha(textAlpha)
                    .graphicsLayer { translationY = textRise }
            )
        }
    }
}
