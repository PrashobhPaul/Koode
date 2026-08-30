package com.trippulse.app.ui.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as OsmGeoPoint

/**
 * Playback speeds offered by the ▶ control on the map.
 *
 * Starts at 5× because real journeys are slow: a one-to-one replay of a
 * six-hour drive is not a feature. Each tap steps up, then wraps.
 */
val PLAYBACK_SPEEDS = listOf(5, 10, 20, 30)

/**
 * One journey, drawn on a map.
 *
 * This replaces both the old `MapPanel` and the separate Replay screen. The
 * replay is not somewhere you navigate to any more — the map itself has a play
 * button, and pressing it animates the traveller along the path they actually
 * took. That is the whole interaction: no extra screen, no extra button.
 *
 * @param breadcrumb the positions actually recorded, oldest first. When this
 *   has two or more points the play control appears.
 * @param current live position; ignored while playback is running, because the
 *   dot then represents the point being replayed.
 */
@Composable
fun JourneyMap(
    modifier: Modifier = Modifier,
    current: GeoPoint? = null,
    origin: GeoPoint? = null,
    destination: GeoPoint? = null,
    route: List<GeoPoint> = emptyList(),
    breadcrumb: List<GeoPoint> = emptyList(),
    breadcrumbTimesMs: List<Long> = emptyList(),
    bearingDeg: Float? = null,
    live: Boolean = true,
    height: Dp = 240.dp,
    showPlayControl: Boolean = true,
    onLongPress: ((GeoPoint) -> Unit)? = null
) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val overlay = remember { JourneyOverlay() }
    val shape = RoundedCornerShape(Radii.lg)

    // ---- playback state ----
    var playing by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableStateOf(0) }
    var cursor by remember(breadcrumb.size) { mutableFloatStateOf(breadcrumb.lastIndex.coerceAtLeast(0).toFloat()) }
    val canPlay = showPlayControl && breadcrumb.size >= 2

    // Leaving playback returns the map to the live picture, which is what a
    // viewer expects after watching where someone has been.
    val playbackIndex = cursor.toInt().coerceIn(0, (breadcrumb.size - 1).coerceAtLeast(0))
    val inPlayback = playing || (canPlay && playbackIndex < breadcrumb.lastIndex)

    // ---- the pulse. Only runs while composed, so it costs nothing off-screen.
    val pulse = rememberInfiniteTransition(label = "mapPulse")
    val pulsePhase by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing), repeatMode = RepeatMode.Restart
        ),
        label = "mapPulsePhase"
    )

    DisposableEffect(Unit) {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.overlays.add(overlay)
        if (onLongPress != null) {
            mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean = false
                override fun longPressHelper(p: OsmGeoPoint?): Boolean {
                    p ?: return false
                    onLongPress(GeoPoint(p.latitude, p.longitude))
                    return true
                }
            }))
        }
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }

    // Advance playback. One coroutine, cancelled the moment playing stops.
    LaunchedEffect(playing, speedIndex, breadcrumb.size) {
        if (!playing || breadcrumb.size < 2) return@LaunchedEffect
        val speed = PLAYBACK_SPEEDS[speedIndex]
        // A fixed 60 ms frame keeps motion smooth; speed decides how many
        // recorded points are consumed per frame.
        val pointsPerFrame = speed * 0.06f
        while (playing && cursor < breadcrumb.lastIndex.toFloat()) {
            delay(60)
            cursor = (cursor + pointsPerFrame).coerceAtMost(breadcrumb.lastIndex.toFloat())
        }
        if (cursor >= breadcrumb.lastIndex.toFloat()) playing = false
    }

    // Feed the overlay. Cheap by design: no overlay is created or destroyed
    // here, so this can run every animation frame without stuttering the map.
    val focus = if (inPlayback) breadcrumb.getOrNull(playbackIndex) else (current ?: destination ?: origin)
    val lastFocus = remember { arrayOfNulls<GeoPoint>(1) }
    val fitted = remember { booleanArrayOf(false) }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxWidth().height(height),
            update = { map ->
                overlay.accentColor = colors.accent.toArgb()
                overlay.travellerColor = colors.traveller.toArgb()
                overlay.destinationColor = colors.warn.toArgb()
                overlay.routeColor = colors.accent.copy(alpha = 0.35f).toArgb()
                overlay.onSurfaceColor = colors.textHigh.toArgb()

                overlay.route = route
                overlay.origin = origin
                overlay.destination = destination
                overlay.travelled = if (inPlayback) breadcrumb.subList(0, playbackIndex + 1) else breadcrumb
                overlay.current = if (inPlayback) breadcrumb.getOrNull(playbackIndex) else current
                overlay.bearingDeg = if (inPlayback) null else bearingDeg
                overlay.live = live && !inPlayback
                overlay.pulsePhase = pulsePhase

                // On first draw, frame the whole journey so a viewer sees where
                // it starts and ends rather than a close-up of one dot.
                if (!fitted[0]) {
                    val all = buildList {
                        addAll(route); addAll(breadcrumb)
                        origin?.let { add(it) }; destination?.let { add(it) }; current?.let { add(it) }
                    }
                    if (all.size >= 2) {
                        map.zoomToBoundingBox(boundingBoxOf(all), false, 96)
                        fitted[0] = true
                    } else if (all.size == 1) {
                        map.controller.setZoom(14.0)
                        map.controller.setCenter(OsmGeoPoint(all[0].lat, all[0].lng))
                        fitted[0] = true
                    } else {
                        map.controller.setZoom(4.5)
                        map.controller.setCenter(OsmGeoPoint(20.5937, 78.9629))
                    }
                } else if (focus != null && focus != lastFocus[0]) {
                    // Follow the moving point, but never fight a manual pan:
                    // only recentre once it drifts outside the visible box.
                    val visible = map.boundingBox
                    val outside = visible == null || !visible.contains(OsmGeoPoint(focus.lat, focus.lng))
                    if (outside || inPlayback) {
                        map.controller.animateTo(OsmGeoPoint(focus.lat, focus.lng))
                    }
                    lastFocus[0] = focus
                }
                map.invalidate()
            }
        )

        if (canPlay) {
            PlaybackControls(
                playing = playing,
                speed = PLAYBACK_SPEEDS[speedIndex],
                progress = if (breadcrumb.lastIndex <= 0) 0f else cursor / breadcrumb.lastIndex.toFloat(),
                timeLabel = breadcrumbTimesMs.getOrNull(playbackIndex)
                    ?.let { TimeFmt.clockWithDay(it, System.currentTimeMillis()) },
                onPlayPause = {
                    if (!playing && cursor >= breadcrumb.lastIndex.toFloat()) cursor = 0f
                    playing = !playing
                },
                onCycleSpeed = { speedIndex = (speedIndex + 1) % PLAYBACK_SPEEDS.size },
                onScrub = { fraction ->
                    playing = false
                    cursor = fraction * breadcrumb.lastIndex.toFloat()
                },
                modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.md)
            )
        }
    }
}

/**
 * The map's own transport controls.
 *
 * Deliberately floating on the map rather than sitting below it as a "Replay"
 * button: replay is a way of looking at this map, not a different screen.
 */
@Composable
private fun PlaybackControls(
    playing: Boolean,
    speed: Int,
    progress: Float,
    timeLabel: String?,
    onPlayPause: () -> Unit,
    onCycleSpeed: () -> Unit,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KoodeTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(Radii.md))
            .background(colors.background.copy(alpha = 0.86f))
            .border(1.dp, colors.outline.copy(alpha = 0.7f), RoundedCornerShape(Radii.md))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(Radii.pill))
                    .background(colors.accent)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Text(if (playing) "⏸" else "▶", fontSize = 15.sp)
            }
            Spacer(Modifier.width(Spacing.sm))
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radii.pill))
                    .background(colors.surfaceRaised)
                    .clickable(onClick = onCycleSpeed)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    "${speed}×",
                    color = colors.accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 12.sp
                )
            }
            if (timeLabel != null) {
                Spacer(Modifier.width(Spacing.sm))
                Text(timeLabel, color = colors.textMid, style = MaterialTheme.typography.bodySmall)
            }
        }
        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = onScrub,
            modifier = Modifier.width(210.dp).height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.outline
            )
        )
    }
}

/** Bounding box with a little breathing room around the extremes. */
private fun boundingBoxOf(points: List<GeoPoint>): BoundingBox {
    var north = -90.0; var south = 90.0; var east = -180.0; var west = 180.0
    points.forEach {
        if (it.lat > north) north = it.lat
        if (it.lat < south) south = it.lat
        if (it.lng > east) east = it.lng
        if (it.lng < west) west = it.lng
    }
    val padLat = ((north - south) * 0.15).coerceAtLeast(0.01)
    val padLng = ((east - west) * 0.15).coerceAtLeast(0.01)
    return BoundingBox(north + padLat, east + padLng, south - padLat, west - padLng)
}

/**
 * Backwards-compatible alias.
 *
 * Screens that only need a static map keep calling `MapPanel`; it is now
 * simply [JourneyMap] with playback switched off.
 */
@Composable
fun MapPanel(
    current: GeoPoint?,
    origin: GeoPoint?,
    destination: GeoPoint?,
    route: List<GeoPoint>,
    breadcrumb: List<GeoPoint> = emptyList(),
    heightDp: Int = 240,
    onLongPress: ((GeoPoint) -> Unit)? = null
) {
    JourneyMap(
        current = current, origin = origin, destination = destination,
        route = route, breadcrumb = breadcrumb,
        height = heightDp.dp, showPlayControl = false, onLongPress = onLongPress
    )
}
