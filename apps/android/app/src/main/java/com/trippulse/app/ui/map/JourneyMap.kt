package com.trippulse.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.trippulse.app.core.Geo
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.MapStages
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import kotlin.math.abs

/**
 * Playback speeds offered by the ▶ control on the map.
 *
 * Starts at 5× because real journeys are slow: a one-to-one replay of a
 * six-hour drive is not a feature. Each tap steps up, then wraps.
 */
val PLAYBACK_SPEEDS = listOf(5, 10, 20, 30)

private const val FOLLOW_TILT = 58.0
private const val FOLLOW_ZOOM = 15.5
private const val GLIDE_MS = 1100.0

/**
 * One journey, drawn on a tilting vector map with the traveller's vehicle.
 *
 * The vehicle is the traveller's actual transport, stage by stage: a cab, an
 * auto, a metro, a walker, each drawn from real views of it — top-down and
 * turned to the heading in the overview, from behind when the camera rides
 * along (see [VehicleMarker]). A flight is a low-poly 3D model that lifts off
 * (see [Vehicle3D]). On a journey of several stages the replay changes
 * vehicle where the traveller changed mode, and walked stretches of the
 * trail are dotted.
 * Between fixes it *glides* from the previous
 * position to the new one; it never runs ahead of the last real fix, because a
 * guessed position shown as live is exactly what Koode refuses to do.
 *
 * Two camera modes: **Follow** (tilted, heading-up, riding with the vehicle)
 * and **Overview** (flat, whole journey framed). Panning by hand drops out of
 * Follow, as navigation apps do.
 *
 * The replay still lives on the map itself: ▶ drives the vehicle along the
 * path actually recorded.
 *
 * @param mode transport mode key of the active stage; picks the vehicle.
 * @param stages when each stage began and its mode, for a journey of several:
 *   the replay shows the vehicle of the moment, and walked stretches are dotted.
 * @param moving whether the traveller is in motion (lifts a flight into the
 *   air; a parked plane stays on the apron).
 * @param immersive edge-to-edge hero use: no border, Follow on by default.
 * @param controlsPadding keeps map controls and the followed vehicle clear of
 *   content overlaid on the map.
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
    onLongPress: ((GeoPoint) -> Unit)? = null,
    mode: String? = null,
    stages: List<MapStages.Stage> = emptyList(),
    moving: Boolean = false,
    immersive: Boolean = false,
    controlsPadding: PaddingValues = PaddingValues(0.dp)
) {
    val colors = KoodeTheme.colors
    val dark = colors.background.luminance() < 0.4f
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val shape = RoundedCornerShape(Radii.lg)

    val mapView = rememberLifecycleMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val longPress by rememberUpdatedState(onLongPress)

    // ---- playback -----------------------------------------------------------
    var playing by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableIntStateOf(0) }
    var cursor by remember(breadcrumb.size) { mutableFloatStateOf(breadcrumb.lastIndex.coerceAtLeast(0).toFloat()) }
    val canPlay = showPlayControl && breadcrumb.size >= 2
    val playbackIndex = cursor.toInt().coerceIn(0, (breadcrumb.size - 1).coerceAtLeast(0))
    val inPlayback = playing || (canPlay && playbackIndex < breadcrumb.lastIndex)

    LaunchedEffect(playing, speedIndex, breadcrumb.size) {
        if (!playing || breadcrumb.size < 2) return@LaunchedEffect
        val pointsPerFrame = PLAYBACK_SPEEDS[speedIndex] * 0.06f
        while (playing && cursor < breadcrumb.lastIndex.toFloat()) {
            delay(60)
            cursor = (cursor + pointsPerFrame).coerceAtMost(breadcrumb.lastIndex.toFloat())
        }
        if (cursor >= breadcrumb.lastIndex.toFloat()) playing = false
    }

    // ---- camera mode --------------------------------------------------------
    val canFollow = current != null || canPlay
    var follow by remember { mutableStateOf(false) }
    var userChoseCamera by remember { mutableStateOf(false) }
    LaunchedEffect(immersive, current != null) {
        if (immersive && current != null && !userChoseCamera) follow = true
    }

    // ---- the vehicle, animated outside Compose state ------------------------
    val motion = remember { VehicleMotion() }
    // Replaying a journey of several stages: the vehicle of that moment.
    val shownMode = if (inPlayback && stages.isNotEmpty())
        breadcrumbTimesMs.getOrNull(playbackIndex)?.let { MapStages.modeAt(stages, it, mode) } ?: mode
    else mode
    val airborne = shownMode == "FLIGHT" && (moving || inPlayback)
    val context = LocalContext.current
    SideEffect {
        motion.mode = shownMode
        motion.airborne = airborne
        motion.context = context.applicationContext
    }

    val padPx = with(density) {
        doubleArrayOf(
            controlsPadding.calculateLeftPadding(layoutDirection).toPx().toDouble(),
            controlsPadding.calculateTopPadding().toPx().toDouble(),
            controlsPadding.calculateRightPadding(layoutDirection).toPx().toDouble(),
            controlsPadding.calculateBottomPadding().toPx().toDouble()
        )
    }
    val framePadPx = with(density) { 56.dp.toPx().toInt() }

    // Map configuration, once.
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.setLogoEnabled(false)
            m.uiSettings.setCompassEnabled(false)
            m.uiSettings.setAttributionEnabled(true)
            m.uiSettings.setRotateGesturesEnabled(true)
            m.uiSettings.setTiltGesturesEnabled(true)
            m.addOnMapLongClickListener { ll ->
                val cb = longPress ?: return@addOnMapLongClickListener false
                cb(GeoPoint(ll.latitude, ll.longitude))
                true
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && follow) {
                    follow = false
                }
            }
            // Keep the 3D vehicle the same on-screen size while zooming, and
            // switch between the top-down and rear views as the camera moves.
            m.addOnCameraMoveListener {
                val cam = m.cameraPosition
                if (abs(cam.zoom - motion.renderedZoom) > 0.03 ||
                    abs(cam.bearing - motion.renderedCameraBearing) > 4.0 ||
                    abs(cam.tilt - motion.renderedTilt) > 4.0
                ) motion.render(m)
            }
            map = m
        }
    }

    // Style: reloaded when the theme flips, and our layers re-installed on it.
    val palette = MapPalette(
        accent = colors.accent.toArgb(),
        traveller = colors.traveller.toArgb(),
        warn = colors.warn.toArgb(),
        casing = if (dark) 0xCC07131D.toInt() else 0xCCFFFFFF.toInt()
    )
    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        style = null
        motion.style = null
        m.setStyle(Style.Builder().fromUri(MapStyles.url(dark))) { s ->
            installLayers(s, palette)
            motion.style = s
            style = s
        }
    }

    // ---- what the vehicle is heading for ------------------------------------
    val target: GeoPoint? = if (inPlayback) interpolate(breadcrumb, cursor) else current
    val targetBearing: Double? = when {
        inPlayback -> segmentBearing(breadcrumb, cursor)
        bearingDeg != null && moving -> bearingDeg.toDouble()
        else -> null
    }

    // Static layers: route, trail, ends, flight estimate.
    LaunchedEffect(style, route, breadcrumb, playbackIndex, inPlayback, current, origin, destination, mode, stages) {
        val s = style ?: return@LaunchedEffect
        val trail = when {
            inPlayback -> breadcrumb.subList(0, playbackIndex + 1) + listOfNotNull(target)
            current != null && breadcrumb.lastOrNull() != current -> breadcrumb + current
            else -> breadcrumb
        }
        // Points past the recorded ones (the gliding vehicle, the live fix) belong to the latest stage.
        val times = if (breadcrumbTimesMs.size != breadcrumb.size) emptyList()
        else breadcrumbTimesMs.take(trail.size).let { t -> t + List(trail.size - t.size) { Long.MAX_VALUE } }
        s.source(SRC_ROUTE)?.setGeoJson(lineCollection(route))
        s.source(SRC_TRAIL)?.setGeoJson(trailCollection(trail, times, stages, mode))
        s.source(SRC_ORIGIN)?.setGeoJson(pointCollection(origin))
        s.source(SRC_DEST)?.setGeoJson(pointCollection(destination))
        val arcFrom = target ?: origin
        s.source(SRC_ARC)?.setGeoJson(
            if (mode == "FLIGHT" && arcFrom != null && destination != null)
                lineCollection(Vehicle3D.greatCircle(arcFrom, destination))
            else EMPTY_COLLECTION
        )
    }

    // Frame the journey when the ends change and we're not riding along.
    LaunchedEffect(style, origin, destination) {
        val m = map ?: return@LaunchedEffect
        if (style == null || follow) return@LaunchedEffect
        frameAll(m, route + breadcrumb + listOfNotNull(origin, destination, current), framePadPx, animate = motion.framedOnce)
        motion.framedOnce = true
    }

    // Glide the vehicle to each new target.
    LaunchedEffect(style, target, targetBearing, shownMode, airborne) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val to = target ?: run { motion.clear(); return@LaunchedEffect }
        val from = motion.pos ?: to
        val toBearing = targetBearing
            ?: if (Geo.haversineM(from, to) > 8.0) Vehicle3D.bearing(from, to)
            else if (motion.pos == null) trailHeading(breadcrumb, to) ?: motion.bearing
            else motion.bearing
        val fromBearing = motion.bearing
        if (inPlayback || motion.pos == null) {
            motion.pos = to
            motion.bearing = toBearing
            motion.render(m)
            if (follow) followCamera(m, motion, padPx, entering = !motion.followPlaced)
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - start) / 1_000_000.0 / GLIDE_MS).coerceIn(0.0, 1.0)
            val e = t * t * (3 - 2 * t) // smoothstep
            motion.pos = Vehicle3D.lerp(from, to, e)
            motion.bearing = Vehicle3D.lerpBearing(fromBearing, toBearing, e)
            motion.render(m)
            if (follow) followCamera(m, motion, padPx, entering = false)
            if (t >= 1.0) break
        }
        // Not following: at least keep the traveller in view.
        if (!follow && !immersive) {
            val visible = m.projection.visibleRegion.latLngBounds
            if (!visible.contains(to.toLatLng())) {
                m.easeCamera(CameraUpdateFactory.newLatLng(to.toLatLng()), 600)
            }
        }
    }

    // Switching camera modes.
    LaunchedEffect(follow, style) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        if (follow) {
            if (motion.pos != null) followCamera(m, motion, padPx, entering = true)
        } else if (motion.followPlaced) {
            motion.followPlaced = false
            frameAll(m, route + breadcrumb + listOfNotNull(origin, destination, current), framePadPx, animate = true)
        }
    }

    // The live halo breathes under the vehicle while the signal is fresh.
    val halo = live && !inPlayback && current != null
    LaunchedEffect(style, halo) {
        val layer = style?.getLayer(L_HALO) ?: return@LaunchedEffect
        if (!halo) {
            layer.setProperties(PropertyFactory.circleOpacity(0f))
            return@LaunchedEffect
        }
        while (true) {
            withFrameMillis { t ->
                val p = (t % 1600L) / 1600f
                layer.setProperties(
                    PropertyFactory.circleRadius(10f + 26f * p),
                    PropertyFactory.circleOpacity(0.38f * (1f - p))
                )
            }
        }
    }

    // ---- layout ---------------------------------------------------------------
    val frame = if (immersive) modifier.fillMaxWidth().height(height)
    else modifier.fillMaxWidth().height(height).clip(shape).border(1.dp, colors.outline.copy(alpha = 0.6f), shape)

    Box(frame) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (canFollow) {
            Box(Modifier.align(Alignment.TopEnd).padding(controlsPadding).padding(Spacing.md)) {
                MapPill(
                    if (follow) "🗺  Overview" else "🧭  Follow in 3D",
                    onClick = { userChoseCamera = true; follow = !follow }
                )
            }
        }

        if (mode == "FLIGHT" && destination != null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(controlsPadding).padding(Spacing.md)) {
                MapPill("┄  estimated flight path", onClick = null)
            }
        }

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
                modifier = Modifier.align(Alignment.BottomStart).padding(controlsPadding).padding(Spacing.md)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Map internals
// ---------------------------------------------------------------------------

private const val SRC_ROUTE = "kd-route"
private const val SRC_TRAIL = "kd-trail"
private const val SRC_ARC = "kd-arc"
private const val SRC_ORIGIN = "kd-origin"
private const val SRC_DEST = "kd-dest"
private const val SRC_HALO = "kd-halo"
private const val SRC_GROUND = "kd-ground"
private const val SRC_VEHICLE = "kd-vehicle"
private const val SRC_MARKER = "kd-marker"
private const val L_HALO = "kd-halo-layer"

private data class MapPalette(val accent: Int, val traveller: Int, val warn: Int, val casing: Int)

private fun Style.source(id: String): GeoJsonSource? = getSourceAs(id)

private fun installLayers(s: Style, c: MapPalette) {
    listOf(SRC_ROUTE, SRC_TRAIL, SRC_ARC, SRC_ORIGIN, SRC_DEST, SRC_HALO, SRC_GROUND, SRC_VEHICLE, SRC_MARKER)
        .forEach { s.addSource(GeoJsonSource(it)) }
    val white = 0xFFFFFFFF.toInt()
    val cap = PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
    val join = PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    val flat = PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)

    s.addLayer(LineLayer("kd-route-layer", SRC_ROUTE).withProperties(
        PropertyFactory.lineColor(c.accent), PropertyFactory.lineWidth(5f), PropertyFactory.lineOpacity(0.5f), cap, join
    ))
    val ridden = Expression.neq(Expression.get("walk"), Expression.literal(true))
    s.addLayer(LineLayer("kd-trail-casing", SRC_TRAIL).withProperties(
        PropertyFactory.lineColor(c.casing), PropertyFactory.lineWidth(8.5f), cap, join
    ).withFilter(ridden))
    s.addLayer(LineLayer("kd-trail-layer", SRC_TRAIL).withProperties(
        PropertyFactory.lineColor(c.traveller), PropertyFactory.lineWidth(5f), cap, join
    ).withFilter(ridden))
    // On foot: a line of footstep dots rather than a road.
    s.addLayer(LineLayer("kd-trail-walk", SRC_TRAIL).withProperties(
        PropertyFactory.lineColor(c.traveller), PropertyFactory.lineWidth(5.5f), cap, join,
        PropertyFactory.lineDasharray(arrayOf(0.01f, 2.2f))
    ).withFilter(Expression.eq(Expression.get("walk"), Expression.literal(true))))
    s.addLayer(LineLayer("kd-arc-layer", SRC_ARC).withProperties(
        PropertyFactory.lineColor(c.traveller), PropertyFactory.lineWidth(3f),
        PropertyFactory.lineOpacity(0.85f), PropertyFactory.lineDasharray(arrayOf(1.6f, 1.6f))
    ))
    s.addLayer(FillLayer("kd-ground-layer", SRC_GROUND).withProperties(
        PropertyFactory.fillColor(Expression.get("c")), PropertyFactory.fillOpacity(0.22f)
    ))
    s.addLayer(CircleLayer(L_HALO, SRC_HALO).withProperties(
        PropertyFactory.circleColor(c.traveller), PropertyFactory.circleRadius(14f),
        PropertyFactory.circleOpacity(0f), flat
    ))
    s.addLayer(CircleLayer("kd-origin-layer", SRC_ORIGIN).withProperties(
        PropertyFactory.circleColor(c.accent), PropertyFactory.circleRadius(7f),
        PropertyFactory.circleStrokeColor(white), PropertyFactory.circleStrokeWidth(3f), flat
    ))
    s.addLayer(CircleLayer("kd-dest-halo", SRC_DEST).withProperties(
        PropertyFactory.circleColor(c.warn), PropertyFactory.circleRadius(15f),
        PropertyFactory.circleOpacity(0.3f), flat
    ))
    s.addLayer(CircleLayer("kd-dest-layer", SRC_DEST).withProperties(
        PropertyFactory.circleColor(c.warn), PropertyFactory.circleRadius(7f),
        PropertyFactory.circleStrokeColor(white), PropertyFactory.circleStrokeWidth(3f), flat
    ))
    // Last, so they draw over everything else. Modes without real views:
    // the low-poly 3D model.
    s.addLayer(FillExtrusionLayer("kd-vehicle-layer", SRC_VEHICLE).withProperties(
        PropertyFactory.fillExtrusionColor(Expression.get("c")),
        PropertyFactory.fillExtrusionBase(Expression.get("b")),
        PropertyFactory.fillExtrusionHeight(Expression.get("h")),
        PropertyFactory.fillExtrusionOpacity(Expression.literal(1.0f))
    ))
    // Seen from above: flat on the map, turned to the heading.
    s.addLayer(SymbolLayer("kd-vehicle-top", SRC_MARKER).withProperties(
        PropertyFactory.iconImage(Expression.get("icon")),
        PropertyFactory.iconRotate(Expression.get("rot")),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP)
    ).withFilter(Expression.eq(Expression.get("kind"), Expression.literal("top"))))
    // ...and upright: from behind when the camera rides along, and a walker always.
    s.addLayer(SymbolLayer("kd-vehicle-rear", SRC_MARKER).withProperties(
        PropertyFactory.iconImage(Expression.get("icon")),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT)
    ).withFilter(Expression.eq(Expression.get("kind"), Expression.literal("rear"))))
}

/** Where the vehicle is drawn right now; mutated at animation rate. */
private class VehicleMotion {
    var pos: GeoPoint? = null
    var bearing: Double = 0.0
    var mode: String? = null
    var airborne: Boolean = false
    var style: Style? = null
    var context: android.content.Context? = null
    var renderedZoom: Double = -1.0
    var renderedCameraBearing: Double = 0.0
    var renderedTilt: Double = 0.0
    var followPlaced: Boolean = false
    var framedOnce: Boolean = false
    /** Until when the camera is easing into Follow; moves meanwhile would freeze it part-way. */
    var easingUntilMs: Long = 0L

    fun render(m: MapLibreMap) {
        val s = style ?: return
        val p = pos ?: return
        val cam = m.cameraPosition
        val views = VehicleMarker.views(mode)
        val ctx = context
        if (views != null && ctx != null) {
            val kind = VehicleMarker.kindFor(views, bearing, cam.bearing, cam.tilt)
            val name = VehicleMarker.name(views, kind)
            val ready = s.getImage(name) != null ||
                VehicleMarker.bitmap(ctx, views, kind)?.let { s.addImage(name, it); true } == true
            if (ready) {
                s.source(SRC_MARKER)?.setGeoJson(Feature.fromGeometry(p.toPoint()).apply {
                    addStringProperty("icon", name)
                    addStringProperty("kind", if (kind == VehicleMarker.Kind.TOP) "top" else "rear") // REAR and FRONT both stand
                    addNumberProperty("rot", bearing)
                })
                s.source(SRC_VEHICLE)?.setGeoJson(EMPTY_COLLECTION)
                s.source(SRC_GROUND)?.setGeoJson(EMPTY_COLLECTION)
                s.source(SRC_HALO)?.setGeoJson(pointCollection(p))
                remember(cam)
                return
            }
        }
        s.source(SRC_MARKER)?.setGeoJson(EMPTY_COLLECTION)
        val mpp = m.projection.getMetersPerPixelAtLatitude(p.lat)
        val placed = Vehicle3D.place(mode, p, bearing, mpp, airborne)
        s.source(SRC_VEHICLE)?.setGeoJson(FeatureCollection.fromFeatures(placed.solids.map { solid ->
            polygonFeature(solid.ring).apply {
                addStringProperty("c", solid.color)
                addNumberProperty("b", solid.baseM)
                addNumberProperty("h", solid.topM)
            }
        }))
        s.source(SRC_GROUND)?.setGeoJson(FeatureCollection.fromFeatures(placed.ground.map { ring ->
            polygonFeature(ring).apply { addStringProperty("c", placed.groundColor) }
        }))
        s.source(SRC_HALO)?.setGeoJson(pointCollection(p))
        remember(cam)
    }

    private fun remember(cam: CameraPosition) {
        renderedZoom = cam.zoom
        renderedCameraBearing = cam.bearing
        renderedTilt = cam.tilt
    }

    fun clear() {
        pos = null
        val s = style ?: return
        s.source(SRC_VEHICLE)?.setGeoJson(EMPTY_COLLECTION)
        s.source(SRC_GROUND)?.setGeoJson(EMPTY_COLLECTION)
        s.source(SRC_MARKER)?.setGeoJson(EMPTY_COLLECTION)
        s.source(SRC_HALO)?.setGeoJson(EMPTY_COLLECTION)
    }
}

/** Ride along: tilted, heading-up, vehicle kept clear of any overlaid card. */
private fun followCamera(m: MapLibreMap, motion: VehicleMotion, padPx: DoubleArray, entering: Boolean) {
    val p = motion.pos ?: return
    val now = android.os.SystemClock.uptimeMillis()
    // A move while the camera eases in would cancel the ease and keep whatever
    // zoom it had reached -- from the overview, that is the whole world.
    if (!entering && motion.followPlaced && now < motion.easingUntilMs) return
    val position = CameraPosition.Builder()
        .target(p.toLatLng())
        .zoom(FOLLOW_ZOOM)
        .tilt(FOLLOW_TILT)
        .bearing(motion.bearing)
        .padding(padPx[0], padPx[1], padPx[2], padPx[3])
        .build()
    if (entering) {
        m.easeCamera(CameraUpdateFactory.newCameraPosition(position), 900)
        motion.easingUntilMs = now + 950
    } else m.moveCamera(CameraUpdateFactory.newCameraPosition(position))
    motion.followPlaced = true
}

/** Flat, north-up view of everything worth seeing. */
private fun frameAll(m: MapLibreMap, points: List<GeoPoint>, padPx: Int, animate: Boolean) {
    val bounds = boundsOf(points)
    val update = when {
        bounds != null -> CameraUpdateFactory.newLatLngBounds(bounds, 0.0, 0.0, padPx)
        points.isNotEmpty() -> CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(points.first().toLatLng()).zoom(14.0).tilt(0.0).bearing(0.0).build()
        )
        else -> CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(GeoPoint(20.5937, 78.9629).toLatLng()).zoom(4.2).tilt(0.0).bearing(0.0).build()
        )
    }
    if (animate) m.easeCamera(update, 800) else m.moveCamera(update)
}

private fun interpolate(path: List<GeoPoint>, cursor: Float): GeoPoint? {
    if (path.isEmpty()) return null
    val i = cursor.toInt().coerceIn(0, path.lastIndex)
    val next = path.getOrNull(i + 1) ?: return path[i]
    return Vehicle3D.lerp(path[i], next, (cursor - i).toDouble())
}

/** The heading of the last stretch of the trail into [to]: the way it was going on arrival there. */
private fun trailHeading(path: List<GeoPoint>, to: GeoPoint): Double? =
    path.lastOrNull { Geo.haversineM(it, to) > 15.0 }?.let { Vehicle3D.bearing(it, to) }

private fun segmentBearing(path: List<GeoPoint>, cursor: Float): Double? {
    if (path.size < 2) return null
    val i = cursor.toInt().coerceIn(0, path.lastIndex - 1)
    // Look a few points ahead so GPS jitter doesn't make the vehicle twitch.
    val a = path[i]
    val b = path[(i + 3).coerceAtMost(path.lastIndex)]
    return if (Geo.haversineM(a, b) < 3.0) null else Vehicle3D.bearing(a, b)
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

@Composable
private fun MapPill(label: String, onClick: (() -> Unit)?) {
    val colors = KoodeTheme.colors
    val pill = RoundedCornerShape(Radii.pill)
    Box(
        Modifier
            .clip(pill)
            .background(colors.background.copy(alpha = 0.82f))
            .border(1.dp, colors.outline.copy(alpha = 0.7f), pill)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = colors.textHigh, style = MaterialTheme.typography.labelMedium, fontSize = 12.sp)
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
                Text("${speed}×", color = colors.accent, style = MaterialTheme.typography.labelSmall, fontSize = 12.sp)
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

/**
 * Backwards-compatible alias.
 *
 * Screens that only need a static map keep calling `MapPanel`; it is simply
 * [JourneyMap] with playback switched off.
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
