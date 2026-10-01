package com.trippulse.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.trippulse.app.core.Profile


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
) = AvatarImage(photoPath = null, name = name, style = Profile.AvatarStyle.NEUTRAL, size = size, modifier = modifier, ring = ring)
