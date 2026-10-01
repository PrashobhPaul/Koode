package com.trippulse.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.R
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.TransportCatalog

/**
 * Koode's illustrations: one picture for each way of travelling, each kind
 * of stop and each kind of place, so the same thing looks the same wherever
 * it appears — the mode picker, the journey card, the progress bar, the
 * timeline. Anything without a picture falls back to its emoji.
 *
 * Images are bundled WebP (drawable-nodpi, ≤480 px, transparent background).
 */
object KoodeArt {

    /** The traveller on the way — Home's "ready for your next journey". */
    @DrawableRes val traveller: Int = R.drawable.art_walk

    @DrawableRes
    fun mode(key: String?): Int? = when (TransportCatalog.profile(key).key) {
        "CAR" -> R.drawable.art_car
        "BIKE" -> R.drawable.art_bike
        "CAB" -> R.drawable.art_cab
        "BUS" -> R.drawable.art_bus
        "METRO" -> R.drawable.art_metro
        "TRAIN" -> R.drawable.art_train
        "SHIP" -> R.drawable.art_ship
        else -> null
    }

    /** Whether a mode's picture faces left (mirrored to face the destination). */
    fun modeFacesLeft(key: String?): Boolean = TransportCatalog.profile(key).key in setOf("BUS", "METRO")

    @DrawableRes
    fun event(type: String): Int? = when (type) {
        EventTypes.FUEL_STOP, EventTypes.CHARGE_STOP -> R.drawable.art_fuel
        EventTypes.FOOD_REPORTED, EventTypes.TEA_COFFEE_REPORTED, EventTypes.SNACK_REPORTED -> R.drawable.art_food
        EventTypes.TOILET_REPORTED -> R.drawable.art_toilet
        EventTypes.OVERNIGHT_CONFIRMED, EventTypes.HALT_CONFIRMED -> R.drawable.art_stay
        else -> null
    }

    /** Somewhere to stay overnight. */
    @DrawableRes val stay: Int = R.drawable.art_stay

    /** A saved place: a house for home, a building for everywhere else. */
    @DrawableRes
    fun place(name: String): Int {
        val n = name.lowercase()
        val home = listOf("home", "house", "veedu", "ghar", "amma", "achan", "parents")
        return if (home.any { it in n }) R.drawable.art_home else R.drawable.art_building
    }
}

/** A bundled illustration, fitted into a square of [size]. */
@Composable
fun ArtImage(
    @DrawableRes res: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    mirrored: Boolean = false
) {
    Image(
        painter = painterResource(res),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .graphicsLayer { if (mirrored) scaleX = -1f }
    )
}

/** A travel mode's picture, or its emoji where it has none. */
@Composable
fun ModeArt(mode: String?, size: Dp, modifier: Modifier = Modifier, faceRight: Boolean = false) {
    val res = KoodeArt.mode(mode)
    if (res != null) {
        ArtImage(
            res, size, modifier,
            contentDescription = TransportCatalog.profile(mode).label,
            mirrored = faceRight && KoodeArt.modeFacesLeft(mode)
        )
    } else {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Text(TransportCatalog.emoji(mode), fontSize = (size.value * 0.6f).sp)
        }
    }
}
