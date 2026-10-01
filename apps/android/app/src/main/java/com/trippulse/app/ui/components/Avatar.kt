package com.trippulse.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trippulse.app.R
import com.trippulse.app.core.Profile
import com.trippulse.app.domain.AvatarFace
import com.trippulse.app.ui.theme.DisplayFamily
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Navy

/** The drawable that stands in for a given avatar style. */
fun avatarRes(style: Profile.AvatarStyle): Int = when (style) {
    Profile.AvatarStyle.MALE -> R.drawable.ic_avatar_male
    Profile.AvatarStyle.FEMALE -> R.drawable.ic_avatar_female
    Profile.AvatarStyle.NEUTRAL -> R.drawable.ic_avatar_neutral
}

/**
 * The traveller's own avatar — the one component for it everywhere: the app
 * bar, the More card, Edit profile and the journey header.
 *
 * It reads the canonical profile ([Profile.revision]) rather than keeping a
 * copy, so adding, changing or removing the photo (or renaming) refreshes
 * every instance at once.
 */
@Composable
fun ProfileAvatar(size: Dp, modifier: Modifier = Modifier, ring: Color? = null) {
    val context = LocalContext.current
    val revision by Profile.revision.collectAsStateWithLifecycle()
    val photo = remember(revision) { Profile.photoPath(context) }
    val name = remember(revision) { Profile.name(context) }
    val style = remember(revision) { Profile.avatarStyle(context) }
    AvatarImage(photo, name, style, size, modifier, ring, version = revision)
}

/**
 * The single avatar renderer: a photo when [photoPath] loads, else the first
 * letter of [name] on that person's colour, else the neutral silhouette
 * ([AvatarFace]). Always a circle; [ring] draws the live-journey halo.
 */
@Composable
fun AvatarImage(
    photoPath: String?,
    name: String?,
    style: Profile.AvatarStyle,
    size: Dp,
    modifier: Modifier = Modifier,
    ring: Color? = null,
    version: Long = 0L
) {
    val colors = KoodeTheme.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    val targetPx = with(density) { size.roundToPx() }.coerceAtLeast(64)
    val bmp = remember(photoPath, version, targetPx) { photoPath?.let { Profile.decodePhoto(it, targetPx) } }
    val face = AvatarFace.resolve(photoLoadable = bmp != null, name = name)

    val ringed = if (ring != null) modifier.border(2.dp, ring, CircleShape).padding(4.dp) else modifier
    val background = when (face) {
        is AvatarFace.Initial -> Color(face.mark.argb)
        else -> colors.surfaceRaised
    }
    Box(
        ringed.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center
    ) {
        when (face) {
            AvatarFace.Photo -> Image(
                bitmap = bmp!!.asImageBitmap(),
                contentDescription = "Profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
            is AvatarFace.Initial -> Text(
                face.mark.initial,
                color = Navy,
                fontFamily = DisplayFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.4f).sp
            )
            AvatarFace.Neutral -> Image(
                painter = painterResource(avatarRes(style)),
                contentDescription = "Profile avatar",
                colorFilter = ColorFilter.tint(colors.textMid),
                modifier = Modifier.size(size * 0.68f)
            )
        }
    }
}

/** Kept for existing callers: the traveller's own avatar. */
@Composable
fun OwnerAvatar(size: Dp, modifier: Modifier = Modifier) = ProfileAvatar(size, modifier)
