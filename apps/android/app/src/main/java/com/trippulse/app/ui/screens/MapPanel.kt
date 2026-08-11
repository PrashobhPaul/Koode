package com.trippulse.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.trippulse.app.BuildConfig
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.ui.theme.Surface2
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextMid
import androidx.compose.foundation.background

/**
 * Map with the current position, origin/destination markers and the route
 * corridor. Degrades to an informative card when no Maps key is configured, so
 * the rest of the dashboard stays useful in local/dev builds.
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
    if (!BuildConfig.MAPS_KEY_SET) {
        Box(
            Modifier.fillMaxWidth().height(heightDp.dp).clip(shape).background(Surface2),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Map unavailable — add a Maps API key\n(local.properties: MAPS_API_KEY=…)",
                color = TextMid, fontSize = 13.sp, modifier = Modifier.padding(16.dp)
            )
        }
        return
    }

    val focus = current ?: destination ?: origin ?: GeoPoint(20.5937, 78.9629) // India centroid
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(focus.lat, focus.lng), 12f)
    }
    LaunchedEffect(current?.lat, current?.lng) {
        val c = current ?: return@LaunchedEffect
        cameraState.position = CameraPosition.fromLatLngZoom(LatLng(c.lat, c.lng), cameraState.position.zoom)
    }

    Box(Modifier.fillMaxWidth().height(heightDp.dp).clip(shape)) {
        GoogleMap(
            modifier = Modifier.fillMaxWidth().height(heightDp.dp),
            cameraPositionState = cameraState,
            properties = MapProperties(mapType = MapType.NORMAL),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, compassEnabled = false),
            onMapLongClick = { latLng -> onLongPress?.invoke(GeoPoint(latLng.latitude, latLng.longitude)) }
        ) {
            if (route.size >= 2) {
                Polyline(points = route.map { LatLng(it.lat, it.lng) }, color = Teal, width = 10f)
            }
            if (breadcrumb.size >= 2) {
                Polyline(points = breadcrumb.map { LatLng(it.lat, it.lng) }, color = Teal, width = 6f)
            }
            origin?.let { Marker(state = MarkerState(LatLng(it.lat, it.lng)), title = "Start") }
            destination?.let { Marker(state = MarkerState(LatLng(it.lat, it.lng)), title = "Destination") }
            current?.let { Marker(state = MarkerState(LatLng(it.lat, it.lng)), title = "Driver") }
        }
    }
}
