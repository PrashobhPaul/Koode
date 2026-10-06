package com.trippulse.app.ui.map

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.ui.theme.KoodeTheme
import kotlinx.coroutines.delay

/**
 * The map data's credit, as the licences ask and no louder.
 *
 * OpenStreetMap's data is under the ODbL and its attribution guidelines ask
 * for the credit to be visible when a map first loads; it may then fade on
 * the first touch or after five seconds, so long as it stays findable
 * (Settings → About → Open source licenses carries it for good). OpenFreeMap
 * serves the OpenMapTiles schema, whose credit travels with it. MapLibre's
 * own ⓘ button is switched off in favour of this.
 */
object MapCredit {
    const val TEXT = "© OpenMapTiles · © OpenStreetMap"
    const val SHOWN_MS = 5_000L
}

/** The credit line, faded out after [MapCredit.SHOWN_MS] or once [touched]. */
@Composable
fun MapCreditLine(touched: Boolean, modifier: Modifier = Modifier) {
    val colors = KoodeTheme.colors
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(MapCredit.SHOWN_MS); shown = false }
    val alpha by animateFloatAsState(if (shown && !touched) 1f else 0f, tween(600), label = "mapCredit")
    if (alpha <= 0.01f) return
    Text(
        MapCredit.TEXT,
        color = colors.textMid,
        fontSize = 9.sp,
        modifier = modifier
            .alpha(alpha)
            .background(colors.background.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}
