package com.trippulse.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.ui.theme.KoodeTheme
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * "Move the map, not the pin."
 *
 * The pin stays fixed in the centre and the traveller drags the map under it —
 * the pattern every ride-hailing app uses, because it is far easier on a phone
 * than aiming a long-press at the right building. [onCenter] reports the spot
 * under the pin each time the map comes to rest.
 */
@Composable
fun PinDropMap(
    start: GeoPoint?,
    onCenter: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 320.dp
) {
    val colors = KoodeTheme.colors
    val dark = colors.background.luminance() < 0.4f
    val mapView = rememberLifecycleMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val report by rememberUpdatedState(onCenter)

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.setLogoEnabled(false)
            m.uiSettings.setCompassEnabled(false)
            m.uiSettings.setAttributionEnabled(false)
            m.uiSettings.setTiltGesturesEnabled(false)
            m.uiSettings.setRotateGesturesEnabled(false)
            val focus = start ?: GeoPoint(20.5937, 78.9629)
            m.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(focus.toLatLng()).zoom(if (start != null) 15.0 else 4.2).build()
                )
            )
            m.addOnCameraIdleListener {
                val t = m.cameraPosition.target ?: return@addOnCameraIdleListener
                report(GeoPoint(t.latitude, t.longitude))
            }
            map = m
        }
    }
    LaunchedEffect(map, dark) {
        map?.setStyle(Style.Builder().fromUri(MapStyles.url(dark)))
    }

    Box(modifier.fillMaxWidth().height(height)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        MapCreditLine(touched = false, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
        // The pin's tip sits exactly on the map centre.
        val pinColor = colors.warn
        Canvas(Modifier.align(Alignment.Center).size(36.dp, 48.dp).offset(y = (-24).dp)) {
            val cx = size.width / 2
            val r = size.width * 0.36f
            val headY = r + 2f
            drawOval(
                Color.Black.copy(alpha = 0.25f),
                topLeft = Offset(cx - r * 0.6f, size.height - 6f),
                size = Size(r * 1.2f, 6f)
            )
            drawLine(pinColor, Offset(cx, headY), Offset(cx, size.height - 3f), strokeWidth = 5f)
            drawCircle(pinColor, radius = r, center = Offset(cx, headY))
            drawCircle(Color.White, radius = r * 0.42f, center = Offset(cx, headY))
            drawCircle(Color.White.copy(alpha = 0.9f), radius = r, center = Offset(cx, headY), style = Stroke(3f))
        }
    }
}
