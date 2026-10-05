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
import com.trippulse.app.domain.Pictures
import com.trippulse.app.domain.TransportCatalog

/**
 * Koode's illustrations: one picture for each way of travelling, each kind
 * of stop and each kind of place, so the same thing looks the same wherever
 * it appears — the mode picker, the journey card, the progress bar, the
 * timeline, saved places. Which picture stands for what is decided once, in
 * [Pictures] (shared with the web viewer); this only maps names to files.
 * Anything without a picture falls back to its emoji.
 *
 * Images are bundled WebP (drawable-nodpi, ≤480 px, transparent background).
 */
object KoodeArt {

    /** Bundled drawable for each [Pictures] name. */
    private val FILES: Map<String, Int> = mapOf(
        Pictures.HOME to R.drawable.art_home,
        Pictures.BUILDING to R.drawable.art_building,
        Pictures.STAY to R.drawable.art_stay,
        Pictures.RESTAURANT to R.drawable.art_restaurant,
        Pictures.TOILET to R.drawable.art_toilet,
        Pictures.FUEL to R.drawable.art_fuel,
        Pictures.WALK to R.drawable.art_walk,
        Pictures.FOOD to R.drawable.art_food,
        Pictures.WATER to R.drawable.art_water,
        Pictures.REST to R.drawable.art_rest,
        "car" to R.drawable.art_car,
        "bike" to R.drawable.art_bike,
        "cab" to R.drawable.art_cab,
        "auto" to R.drawable.art_auto,
        "metro" to R.drawable.art_metro,
        "train" to R.drawable.art_train,
        "bus" to R.drawable.art_bus,
        "flight" to R.drawable.art_flight,
        "ship" to R.drawable.art_ship,
        "cycle" to R.drawable.art_cycle,
        "ferry" to R.drawable.art_ferry,
        "cruise" to R.drawable.art_cruise
    )

    @DrawableRes fun file(name: String?): Int? = name?.let { FILES[it] }

    /** The traveller on the way — Home's "ready for your next journey". */
    @DrawableRes val traveller: Int = R.drawable.art_walk

    /** Somewhere to stay overnight. */
    @DrawableRes val stay: Int = R.drawable.art_stay

    @DrawableRes fun mode(key: String?): Int? = file(Pictures.mode(key))

    /** Whether a mode's picture faces left (mirrored to face the destination). */
    fun modeFacesLeft(key: String?): Boolean = Pictures.modeFacesLeft(key)

    @DrawableRes
    fun event(type: String, payload: Map<String, Any?> = emptyMap()): Int? = file(Pictures.event(type, payload))

    /** A saved place: home, somewhere to stay, a restaurant, fuel, or a building. */
    @DrawableRes fun place(name: String): Int = file(Pictures.place(name)) ?: R.drawable.art_building
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
