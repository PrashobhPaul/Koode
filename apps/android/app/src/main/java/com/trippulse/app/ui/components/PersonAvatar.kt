package com.trippulse.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.domain.PersonMark
import com.trippulse.app.ui.theme.DisplayFamily
import com.trippulse.app.ui.theme.Navy

/**
 * A person as the circle sees them: their initial on a colour that is theirs.
 *
 * Followers never receive the traveller's photo (it stays on their phone), so
 * this is how someone is recognised at a glance — the same letter and colour
 * on Home, in the circle list, on the map chip and in the notification.
 * [ring] draws the live-journey halo.
 */
@Composable
fun PersonAvatar(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    ring: Color? = null
) {
    val mark = PersonMark.of(name)
    val ringed = if (ring != null) {
        modifier.border(2.dp, ring, CircleShape).padding(4.dp)
    } else modifier
    Box(
        ringed.size(size).clip(CircleShape).background(Color(mark.argb)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            mark.initial,
            color = Navy,
            fontFamily = DisplayFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.4f).sp
        )
    }
}
