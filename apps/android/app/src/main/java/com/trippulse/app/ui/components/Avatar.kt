package com.trippulse.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.trippulse.app.R
import com.trippulse.app.core.Profile
import com.trippulse.app.ui.theme.KoodeTheme

/** The drawable that stands in for a given avatar style. */
fun avatarRes(style: Profile.AvatarStyle): Int = when (style) {
    Profile.AvatarStyle.MALE -> R.drawable.ic_avatar_male
    Profile.AvatarStyle.FEMALE -> R.drawable.ic_avatar_female
    Profile.AvatarStyle.NEUTRAL -> R.drawable.ic_avatar_neutral
}

/**
 * A round profile picture: the traveller's photo if they added one, otherwise
 * the chosen silhouette on a tinted disc. Pass a null [photoPath] to force the
 * silhouette — the follower side never receives the photo, only the style.
 */
@Composable
fun Avatar(
    photoPath: String?,
    style: Profile.AvatarStyle,
    size: Dp,
    modifier: Modifier = Modifier,
    version: Long = 0L
) {
    val colors = KoodeTheme.colors
    val bmp = remember(photoPath, version) {
        photoPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    Box(
        modifier.size(size).clip(CircleShape).background(colors.surfaceRaised),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        } else {
            Image(
                painter = painterResource(avatarRes(style)),
                contentDescription = "Profile avatar",
                colorFilter = ColorFilter.tint(colors.textMid),
                modifier = Modifier.size(size * 0.68f)
            )
        }
    }
}

/** The signed-in traveller's own avatar (photo when present). */
@Composable
fun OwnerAvatar(size: Dp, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    Avatar(Profile.photoPath(c), Profile.avatarStyle(c), size, modifier, Profile.photoVersion(c))
}
