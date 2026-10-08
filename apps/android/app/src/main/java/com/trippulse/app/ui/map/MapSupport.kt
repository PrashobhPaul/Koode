package com.trippulse.app.ui.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trippulse.app.domain.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Free, keyless vector map styles from OpenFreeMap (OpenStreetMap data).
 *
 * `liberty` carries a `building-3d` layer, which is what makes the tilted
 * camera feel like a city rather than a flat poster. The dark style keeps the
 * night-sky look for the dark theme. Both are served without registration.
 */
object MapStyles {
    const val LIGHT = "https://tiles.openfreemap.org/styles/liberty"
    const val DARK = "https://tiles.openfreemap.org/styles/dark"
    fun url(dark: Boolean) = if (dark) DARK else LIGHT
}

/** Initialise MapLibre once per process; safe to call repeatedly. */
fun ensureMapLibre(context: Context) {
    MapLibre.getInstance(context.applicationContext)
}

/**
 * A MapView tied to the screen's lifecycle.
 *
 * Texture mode matters here: a SurfaceView ignores Compose clipping and draws
 * over sibling content, which breaks rounded corners and anything overlaid on
 * the map (the hero card, playback controls). TextureView behaves like a
 * normal view.
 */
@Composable
internal fun rememberLifecycleMapView(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        ensureMapLibre(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply {
            onCreate(null)
        }
    }
    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

// ---- GeoJSON helpers -------------------------------------------------------

internal fun GeoPoint.toLatLng() = LatLng(lat, lng)
internal fun GeoPoint.toPoint(): Point = Point.fromLngLat(lng, lat)

internal val EMPTY_COLLECTION: FeatureCollection = FeatureCollection.fromFeatures(emptyList())

internal fun lineCollection(points: List<GeoPoint>): FeatureCollection =
    if (points.size < 2) EMPTY_COLLECTION
    else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(points.map { it.toPoint() }))))

/** The trail as one line per stage, walked stretches marked so they draw as dots. */
internal fun trailCollection(
    points: List<GeoPoint>, timesMs: List<Long>, stages: List<com.trippulse.app.domain.MapStages.Stage>, fallback: String?
): FeatureCollection {
    val runs = com.trippulse.app.domain.MapStages.runs(timesMs, stages, fallback, points.size)
    if (runs.isEmpty()) return EMPTY_COLLECTION
    return FeatureCollection.fromFeatures(runs.map { r ->
        // A metro or ferry ride follows its line's stations, not the chords between its few fixes.
        val stretch = points.subList(r.from, r.to + 1).let { p ->
            com.trippulse.app.domain.TransitNetwork.kindOf(r.mode)?.let { com.trippulse.app.data.TransitData.current.followLine(p, it) } ?: p
        }
        Feature.fromGeometry(LineString.fromLngLats(stretch.map { it.toPoint() })).apply {
            addStringProperty("look", com.trippulse.app.domain.MapStages.look(r.mode))
        }
    })
}

internal fun pointCollection(p: GeoPoint?): FeatureCollection =
    if (p == null) EMPTY_COLLECTION else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(p.toPoint())))

internal fun polygonFeature(ring: List<GeoPoint>): Feature =
    Feature.fromGeometry(Polygon.fromLngLats(listOf(ring.map { it.toPoint() })))

internal fun boundsOf(points: List<GeoPoint>): LatLngBounds? {
    val distinct = points.distinctBy { "%.5f,%.5f".format(it.lat, it.lng) }
    if (distinct.size < 2) return null
    return LatLngBounds.Builder().includes(distinct.map { it.toLatLng() }).build()
}
