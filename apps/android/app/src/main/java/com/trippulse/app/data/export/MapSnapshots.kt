package com.trippulse.app.data.export

import android.content.Context
import com.trippulse.app.data.export.report.RouteMap
import com.trippulse.app.ui.map.MapStyles
import com.trippulse.app.ui.map.ensureMapLibre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume

/**
 * A real map for a report's route: the light OpenFreeMap style rendered
 * off-screen by MapLibre, framed on the journey, handed to [RouteMap] with
 * the projection from a place to a pixel on it.
 *
 * Best effort, bounded in time. No network, no WebGL-class GPU, or a style
 * that will not load within [TIMEOUT_MS] means null, and the report draws
 * its paper grid as before; a report never waits on a map.
 */
object MapSnapshots {

    private const val TIMEOUT_MS = 12_000L

    /** Rendered at this many pixels per point of the page, so the print is crisp. */
    private const val OVERSAMPLE = 1.6f

    suspend fun backdrop(context: Context, points: List<Pair<Double, Double>>, widthPt: Float, heightPt: Float): RouteMap.Backdrop? {
        if (points.size < 2) return null
        val w = (widthPt * OVERSAMPLE).toInt()
        val h = (heightPt * OVERSAMPLE).toInt()
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                runCatching { render(context.applicationContext, points, w, h) }.getOrNull()
            }
        }
    }

    private suspend fun render(context: Context, points: List<Pair<Double, Double>>, w: Int, h: Int): RouteMap.Backdrop? {
        ensureMapLibre(context)
        val lats = points.map { it.first }; val lngs = points.map { it.second }
        val padLat = ((lats.max() - lats.min()) * RouteMap.Backdrop.PADDING).coerceAtLeast(0.002)
        val padLng = ((lngs.max() - lngs.min()) * RouteMap.Backdrop.PADDING).coerceAtLeast(0.002)
        val bounds = LatLngBounds.from(
            (lats.max() + padLat).coerceAtMost(85.0), (lngs.max() + padLng).coerceAtMost(180.0),
            (lats.min() - padLat).coerceAtLeast(-85.0), (lngs.min() - padLng).coerceAtLeast(-180.0)
        )
        val options = MapSnapshotter.Options(w, h)
            .withStyleBuilder(Style.Builder().fromUri(MapStyles.LIGHT))
            .withRegion(bounds)
            .withLogo(false)
            .withPixelRatio(1f)
        val snapshotter = MapSnapshotter(context, options)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { snapshotter.cancel() } }
            snapshotter.start(
                { snapshot ->
                    val bmp = snapshot.bitmap
                    val backdrop = RouteMap.Backdrop(bmp, bmp.width, bmp.height) { lat, lng ->
                        val p = snapshot.pixelForLatLng(LatLng(lat, lng))
                        p.x to p.y
                    }
                    if (cont.isActive) cont.resume(backdrop)
                },
                { if (cont.isActive) cont.resume(null) }
            )
        }
    }
}
