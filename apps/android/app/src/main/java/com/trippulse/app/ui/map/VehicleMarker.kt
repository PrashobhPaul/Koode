package com.trippulse.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.annotation.DrawableRes
import com.trippulse.app.R
import com.trippulse.app.domain.TransportCatalog
import kotlin.math.roundToInt

/**
 * The traveller's vehicle on the map, drawn from real views of it:
 *
 *  - **Top-down**, turned to the heading and lying flat on the map — what you
 *    see in the overview, like any navigation app.
 *  - **From behind**, standing upright — when the camera rides along behind
 *    the vehicle, tilted and heading-up, so you see the car you're following.
 *
 * Only some modes have these views (car and cab share the car; the bus has
 * its own). Every other mode keeps its low-poly 3D model ([Vehicle3D]).
 */
internal object VehicleMarker {

    enum class Kind { TOP, REAR }

    class Views(val key: String, @DrawableRes val top: Int, @DrawableRes val rear: Int,
                /** On-screen length of the top view, and width of the rear view, in dp. */
                val topLengthDp: Float, val rearWidthDp: Float,
                /** A person, not a vehicle: always drawn standing, never turned flat to a heading. */
                val uprightOnly: Boolean = false)

    private val CAR = Views("car", R.drawable.map_car_top, R.drawable.map_car_rear, 50f, 46f)
    private val BUS = Views("bus", R.drawable.map_bus_top, R.drawable.map_bus_rear, 78f, 54f)
    private val WALKER = Views("walk", R.drawable.art_walk, R.drawable.art_walk, 40f, 40f, uprightOnly = true)

    fun views(mode: String?): Views? = when (TransportCatalog.profile(mode).key) {
        "CAR", "CAB" -> CAR
        "BUS" -> BUS
        "WALK" -> WALKER
        else -> null
    }

    fun name(v: Views, kind: Kind): String = "kd-veh-${v.key}-${kind.name.lowercase()}"

    fun bitmap(context: Context, v: Views, kind: Kind): Bitmap? {
        val src = BitmapFactory.decodeResource(context.resources, if (kind == Kind.TOP) v.top else v.rear) ?: return null
        val d = context.resources.displayMetrics.density
        val (w, h) = if (kind == Kind.TOP) {
            val hh = v.topLengthDp * d
            (src.width * hh / src.height).roundToInt() to hh.roundToInt()
        } else {
            val ww = v.rearWidthDp * d
            ww.roundToInt() to (src.height * ww / src.width).roundToInt()
        }
        val art = Bitmap.createScaledBitmap(src, w, h, true)
        if (art !== src) src.recycle()
        val blur = 5f * d
        val pad = (blur * 2).roundToInt()
        val groundH = if (kind == Kind.REAR) (8f * d).roundToInt() else 0
        val out = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad + groundH, Bitmap.Config.ARGB_8888)
        out.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(out)

        if (kind == Kind.TOP) {
            // A soft shadow cast by the body itself, a touch below it.
            val shadow = art.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }, null)
            canvas.drawBitmap(shadow, pad - blur, pad - blur + 2f * d, Paint().apply { color = Color.argb(150, 0, 0, 0) })
            shadow.recycle()
        } else {
            // A soft ground shadow under the wheels.
            val cx = out.width / 2f
            val cy = (pad + h).toFloat()
            val rx = w * 0.55f
            canvas.save()
            canvas.scale(1f, (groundH * 1.4f) / rx, cx, cy)
            canvas.drawCircle(cx, cy, rx, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, rx, intArrayOf(Color.argb(140, 0, 0, 0), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            })
            canvas.restore()
        }
        canvas.drawBitmap(art, pad.toFloat(), pad.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        art.recycle()
        return out
    }

    /**
     * Which view to show: from behind only while the camera is tilted and
     * looking the same way the vehicle is heading; top-down otherwise.
     */
    fun kindFor(vehicleBearing: Double, cameraBearing: Double, cameraTilt: Double): Kind {
        val diff = ((vehicleBearing - cameraBearing + 540.0) % 360.0) - 180.0
        return if (cameraTilt >= 30.0 && kotlin.math.abs(diff) <= 40.0) Kind.REAR else Kind.TOP
    }
}
