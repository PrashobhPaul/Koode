package com.trippulse.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.ui.theme.Teal
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.GeoPoint as OsmGeoPoint

/**
 * Map with the current position, origin/destination markers and the route
 * corridor, rendered with osmdroid + OpenStreetMap tiles. Completely free —
 * no API key, no billing account, no Google Maps SDK.
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
    val shape = RoundedCornerShape(18.dp)
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lastFocus = remember { arrayOfNulls<GeoPoint>(1) }

    DisposableEffect(Unit) {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }

    Box(Modifier.fillMaxWidth().height(heightDp.dp).clip(shape)) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxWidth().height(heightDp.dp),
            update = { map ->
                map.overlays.clear()

                if (onLongPress != null) {
                    map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean = false
                        override fun longPressHelper(p: OsmGeoPoint?): Boolean {
                            p ?: return false
                            onLongPress(GeoPoint(p.latitude, p.longitude))
                            return true
                        }
                    }))
                }

                if (route.size >= 2) {
                    map.overlays.add(Polyline(map).apply {
                        setPoints(route.map { OsmGeoPoint(it.lat, it.lng) })
                        outlinePaint.color = Teal.toArgb()
                        outlinePaint.strokeWidth = 10f
                    })
                }
                if (breadcrumb.size >= 2) {
                    map.overlays.add(Polyline(map).apply {
                        setPoints(breadcrumb.map { OsmGeoPoint(it.lat, it.lng) })
                        outlinePaint.color = Teal.toArgb()
                        outlinePaint.strokeWidth = 6f
                    })
                }

                fun marker(p: GeoPoint, label: String) {
                    map.overlays.add(Marker(map).apply {
                        position = OsmGeoPoint(p.lat, p.lng)
                        title = label
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }
                origin?.let { marker(it, "Start") }
                destination?.let { marker(it, "Destination") }
                current?.let { marker(it, "Driver") }

                val focus = current ?: destination ?: origin
                if (focus != null) {
                    // recenter only when the tracked point moves, so manual
                    // panning/zooming isn't fought on every recomposition
                    if (focus != lastFocus[0]) {
                        if (map.zoomLevelDouble < 5.0) map.controller.setZoom(12.0)
                        map.controller.animateTo(OsmGeoPoint(focus.lat, focus.lng))
                        lastFocus[0] = focus
                    }
                } else if (lastFocus[0] == null) {
                    // India-wide default view until any point is known
                    map.controller.setZoom(5.0)
                    map.controller.setCenter(OsmGeoPoint(20.5937, 78.9629))
                }
                map.invalidate()
            }
        )
    }
}
