package com.trippulse.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing

/** Height of the part of the status card that sits over the map. */
private val CARD_OVERLAP = 88.dp

/** Space reserved at the top of the map for the overlaid header. */
private val HEADER_SPACE = 104.dp

/**
 * The journey, map first.
 *
 * The 3D map runs edge to edge under the status bar; the header floats over
 * its top on a soft scrim, and the status card (ETA, progress) floats over its
 * bottom edge. [map] receives the padding it must keep its controls — and the
 * vehicle it follows — clear of, so nothing important hides behind the card.
 */
@Composable
fun JourneyHero(
    map: @Composable (height: Dp, controlsPadding: PaddingValues) -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    card: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KoodeTheme.colors
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val mapHeight = (screenH * 0.56f).coerceIn(340.dp, 620.dp)
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val controls = PaddingValues(top = topInset + HEADER_SPACE, bottom = CARD_OVERLAP + Spacing.sm)
    val cardShape = RoundedCornerShape(Radii.xl)

    Box(modifier.fillMaxWidth()) {
        map(mapHeight, controls)

        // Header over a scrim, so it reads on any map colour.
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to colors.background.copy(alpha = 0.94f),
                        0.7f to colors.background.copy(alpha = 0.55f),
                        1f to colors.background.copy(alpha = 0f)
                    )
                )
                .statusBarsPadding()
                .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xl),
            content = header
        )

        // The card straddles the map's bottom edge.
        Box(
            Modifier.fillMaxWidth().padding(top = mapHeight - CARD_OVERLAP),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .shadow(14.dp, cardShape, clip = false)
                    .clip(cardShape)
                    .background(colors.surface)
                    .border(1.dp, colors.outline.copy(alpha = 0.55f), cardShape)
                    .padding(Spacing.lg),
                content = card
            )
        }
    }
}

/**
 * Progress as a road: the traveller's own vehicle rides the track from the
 * start dot to the destination flag, bobbing gently while it moves.
 */
@Composable
fun RideProgress(progress: Float, mode: String?, emoji: String, moving: Boolean, modifier: Modifier = Modifier) {
    val colors = KoodeTheme.colors
    val animated by animateFloatAsState(
        progress.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "rideProgress"
    )
    val bobTransition = rememberInfiniteTransition(label = "rideBob")
    val bob by bobTransition.animateFloat(
        initialValue = 0f, targetValue = if (moving) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "rideBobY"
    )
    val vehicleSize = 30.dp

    BoxWithConstraints(modifier.fillMaxWidth().height(40.dp)) {
        val track = maxWidth
        // track
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(Radii.pill))
                .background(colors.surfaceRaised)
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(animated.coerceAtLeast(0.02f))
                .height(8.dp)
                .clip(RoundedCornerShape(Radii.pill))
                .background(Brush.horizontalGradient(listOf(colors.accentDeep, colors.accent)))
        )
        // destination flag
        Text("🏁", fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterEnd))
        // the vehicle
        val x = (track - vehicleSize) * animated
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = x, y = (-6).dp - (2.dp * bob))
                .size(vehicleSize),
            contentAlignment = Alignment.Center
        ) {
            Text(
                emoji, fontSize = 22.sp,
                modifier = Modifier.graphicsLayer {
                    // Most road and sea vehicle emoji face left; turn them to face
                    // the destination. The plane points up-right, so level it.
                    when (mode) {
                        "CAR", "CAB", "BUS", "BIKE", "SHIP", null -> scaleX = -1f
                        "FLIGHT" -> rotationZ = 45f
                    }
                }
            )
        }
    }
}
