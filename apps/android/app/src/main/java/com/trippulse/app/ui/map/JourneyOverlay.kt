package com.trippulse.app.ui.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.trippulse.app.domain.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.util.GeoPoint as OsmGeoPoint

/**
 * Everything Koode draws on top of the map, in one overlay.
 *
 * osmdroid's stock `Marker` is a bitmap pin — the conventional teardrop that
 * makes every map look like every other map. Drawing the journey ourselves
 * costs one class and buys three things the product asked for:
 *
 *  - a **live blue dot** with a breathing halo, so a glance at the map tells
 *    you the journey is happening *now* rather than showing a frozen picture;
 *  - **modern start and end indicators** — a ringed origin node and a flagged
 *    destination node — instead of two identical pins;
 *  - a single overlay that can be mutated and invalidated at animation rate,
 *    which is what makes the pulse and the playback cheap. Rebuilding osmdroid
 *    overlays every frame is what makes maps stutter.
 */
class JourneyOverlay : Overlay() {

    /** Route corridor: the planned road, drawn faint. */
    var route: List<GeoPoint> = emptyList()

    /** Where the traveller has actually been, drawn solid. */
    var travelled: List<GeoPoint> = emptyList()

    var origin: GeoPoint? = null
    var destination: GeoPoint? = null

    /** The traveller's live (or playback) position. */
    var current: GeoPoint? = null

    /** 0..1, advanced by the caller; drives the halo. */
    var pulsePhase: Float = 0f

    /** Direction of travel in degrees, when known — draws a heading wedge. */
    var bearingDeg: Float? = null

    /** Dims the live dot when the position is stale rather than hiding it. */
    var live: Boolean = true

    var accentColor: Int = 0xFF2DD4BF.toInt()
    var travellerColor: Int = 0xFF38BDF8.toInt()
    var routeColor: Int = 0x552DD4BF
    var destinationColor: Int = 0xFFF59E0B.toInt()
    var onSurfaceColor: Int = 0xFFEAF3F7.toInt()

    // Paints are allocated once: this draw() runs at animation rate.
    private val routePaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val travelledPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val travelledGlowPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val stroke = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }

    private val reusablePath = Path()
    private val reusablePoint = android.graphics.Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection: Projection = mapView.projection ?: return

        // ---- planned route, underneath everything ----
        if (route.size >= 2) {
            routePaint.color = routeColor
            drawPolyline(canvas, projection, route, routePaint)
        }

        // ---- the path actually travelled ----
        if (travelled.size >= 2) {
            travelledGlowPaint.color = withAlpha(travellerColor, 46)
            drawPolyline(canvas, projection, travelled, travelledGlowPaint)
            travelledPaint.color = travellerColor
            drawPolyline(canvas, projection, travelled, travelledPaint)
        }

        // ---- endpoints ----
        origin?.let { drawOriginNode(canvas, toScreen(projection, it)) }
        destination?.let { drawDestinationNode(canvas, toScreen(projection, it)) }

        // ---- the traveller ----
        current?.let { drawLiveDot(canvas, toScreen(projection, it)) }
    }

    private fun toScreen(projection: Projection, p: GeoPoint): PointF {
        projection.toPixels(OsmGeoPoint(p.lat, p.lng), reusablePoint)
        return PointF(reusablePoint.x.toFloat(), reusablePoint.y.toFloat())
    }

    private fun drawPolyline(
        canvas: Canvas, projection: Projection, points: List<GeoPoint>, paint: Paint
    ) {
        reusablePath.reset()
        for (i in points.indices) {
            val s = toScreen(projection, points[i])
            if (i == 0) reusablePath.moveTo(s.x, s.y) else reusablePath.lineTo(s.x, s.y)
        }
        canvas.drawPath(reusablePath, paint)
    }

    /**
     * Origin: a hollow ring with a solid core — the visual language of "you
     * started here", quieter than the destination because it is already done.
     */
    private fun drawOriginNode(canvas: Canvas, p: PointF) {
        fill.color = withAlpha(accentColor, 40)
        canvas.drawCircle(p.x, p.y, 20f, fill)
        stroke.color = accentColor
        stroke.strokeWidth = 3.5f
        canvas.drawCircle(p.x, p.y, 12f, stroke)
        fill.color = accentColor
        canvas.drawCircle(p.x, p.y, 5.5f, fill)
    }

    /**
     * Destination: a rounded marker with a pennant, standing on a small base
     * so it reads as a place on the ground rather than a floating pin.
     */
    private fun drawDestinationNode(canvas: Canvas, p: PointF) {
        // ground shadow
        fill.color = 0x33000000
        canvas.drawOval(p.x - 11f, p.y - 3f, p.x + 11f, p.y + 5f, fill)

        // mast
        stroke.color = destinationColor
        stroke.strokeWidth = 3.2f
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(p.x, p.y, p.x, p.y - 30f, stroke)

        // pennant
        reusablePath.reset()
        reusablePath.moveTo(p.x + 1.5f, p.y - 30f)
        reusablePath.lineTo(p.x + 22f, p.y - 24f)
        reusablePath.lineTo(p.x + 1.5f, p.y - 17f)
        reusablePath.close()
        fill.color = destinationColor
        canvas.drawPath(reusablePath, fill)

        // base node
        fill.color = withAlpha(destinationColor, 60)
        canvas.drawCircle(p.x, p.y, 9f, fill)
        fill.color = destinationColor
        canvas.drawCircle(p.x, p.y, 4f, fill)
    }

    /**
     * The live dot: a solid blue core, a white collar to lift it off any tile
     * colour, and an expanding halo that fades as it grows. The halo is driven
     * by [pulsePhase] rather than a timer inside the overlay, so the caller
     * owns the animation lifecycle and it stops when the screen is not visible.
     */
    private fun drawLiveDot(canvas: Canvas, p: PointF) {
        val phase = pulsePhase.coerceIn(0f, 1f)
        if (live) {
            val haloRadius = 13f + 26f * phase
            fill.color = withAlpha(travellerColor, ((1f - phase) * 90).toInt())
            canvas.drawCircle(p.x, p.y, haloRadius, fill)
        }

        // heading wedge, drawn under the dot so it reads as motion, not clutter
        bearingDeg?.let { bearing ->
            canvas.save()
            canvas.rotate(bearing, p.x, p.y)
            reusablePath.reset()
            reusablePath.moveTo(p.x, p.y - 26f)
            reusablePath.lineTo(p.x - 8f, p.y - 8f)
            reusablePath.lineTo(p.x + 8f, p.y - 8f)
            reusablePath.close()
            fill.color = withAlpha(travellerColor, if (live) 190 else 90)
            canvas.drawPath(reusablePath, fill)
            canvas.restore()
        }

        fill.color = withAlpha(0xFFFFFFFF.toInt(), if (live) 255 else 150)
        canvas.drawCircle(p.x, p.y, 10f, fill)
        fill.color = withAlpha(travellerColor, if (live) 255 else 140)
        canvas.drawCircle(p.x, p.y, 7f, fill)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}
