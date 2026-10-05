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
 * Every mode on the ground or water has these views, each its own vehicle:
 * the cab is a taxi, the auto an electric auto, the bike a motorbike with its
 * rider. The metro and the train are long and seen only from above. A walker
 * is a person, so never laid flat: they are drawn standing, seen from behind
 * when walking away from the viewer and from the front when coming towards
 * them. Only a flight keeps its low-poly 3D model ([Vehicle3D]), which can
 * lift off the ground.
 */
internal object VehicleMarker {

    /** TOP lies flat on the map, turned to the heading; REAR and FRONT stand upright. */
    enum class Kind { TOP, REAR, FRONT }

    class Views(val key: String, @DrawableRes val top: Int, @DrawableRes val rear: Int?,
                /** On-screen length of the top view, and width of the rear view, in dp. */
                val topLengthDp: Float, val rearWidthDp: Float,
                /** Seen from the front: only a person, who is never laid flat (see [uprightOnly]). */
                @DrawableRes val front: Int? = null,
                /** A person, not a vehicle: always drawn standing, never turned flat to a heading. */
                val uprightOnly: Boolean = false,
                /** Height of a standing figure, in dp (its width follows the picture). */
                val uprightHeightDp: Float = 0f)

    private val CAR = Views("car", R.drawable.map_car_top, R.drawable.map_car_rear, 50f, 46f)
    private val CAB = Views("cab", R.drawable.map_cab_top, R.drawable.map_cab_rear, 50f, 46f)
    private val AUTO = Views("auto", R.drawable.map_auto_top, R.drawable.map_auto_rear, 40f, 38f)
    private val BIKE = Views("bike", R.drawable.map_bike_top, R.drawable.map_bike_rear, 40f, 32f)
    private val CYCLE = Views("cycle", R.drawable.map_cycle_top, R.drawable.map_cycle_rear, 38f, 22f)
    private val BUS = Views("bus", R.drawable.map_bus_top, R.drawable.map_bus_rear, 78f, 54f)
    private val METRO = Views("metro", R.drawable.map_metro_top, null, 96f, 0f)
    private val TRAIN = Views("train", R.drawable.map_train_top, null, 104f, 0f)
    private val SHIP = Views("ship", R.drawable.map_ship_top, R.drawable.map_ship_rear, 66f, 62f)
    private val WALKER = Views("walk", R.drawable.map_walk_front, R.drawable.map_walk_rear, 0f, 0f,
        front = R.drawable.map_walk_front, uprightOnly = true, uprightHeightDp = 46f)

    fun views(mode: String?): Views? = when (TransportCatalog.profile(mode).key) {
        "CAR" -> CAR
        "CAB" -> CAB
        "AUTO" -> AUTO
        "BIKE" -> BIKE
        "CYCLE" -> CYCLE
        "BUS" -> BUS
        "METRO" -> METRO
        "TRAIN" -> TRAIN
        "SHIP" -> SHIP
        "WALK" -> WALKER
        else -> null
    }

    fun name(v: Views, kind: Kind): String = "kd-veh-${v.key}-${kind.name.lowercase()}"

    fun bitmap(context: Context, v: Views, kind: Kind): Bitmap? {
        val res = when (kind) {
            Kind.TOP -> v.top
            Kind.REAR -> v.rear ?: v.top
            Kind.FRONT -> v.front ?: v.rear ?: v.top
        }
        val src = BitmapFactory.decodeResource(context.resources, res) ?: return null
        val d = context.resources.displayMetrics.density
        val (w, h) = when {
            v.uprightOnly -> {
                val hh = v.uprightHeightDp * d
                (src.width * hh / src.height).roundToInt() to hh.roundToInt()
            }
            kind == Kind.TOP -> {
                val hh = v.topLengthDp * d
                (src.width * hh / src.height).roundToInt() to hh.roundToInt()
            }
            else -> {
                val ww = v.rearWidthDp * d
                ww.roundToInt() to (src.height * ww / src.width).roundToInt()
            }
        }
        val art = Bitmap.createScaledBitmap(src, w, h, true)
        if (art !== src) src.recycle()
        val blur = 5f * d
        val pad = (blur * 2).roundToInt()
        val groundH = if (kind != Kind.TOP) (8f * d).roundToInt() else 0
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
     * looking the same way the vehicle is heading; top-down otherwise. The
     * metro and the train are only ever seen from above. A walker always
     * stands: from behind when heading up the screen, from the front when
     * heading down it.
     */
    fun kindFor(v: Views, vehicleBearing: Double, cameraBearing: Double, cameraTilt: Double): Kind {
        val diff = ((vehicleBearing - cameraBearing + 540.0) % 360.0) - 180.0
        if (v.uprightOnly) return if (kotlin.math.abs(diff) <= 90.0) Kind.REAR else Kind.FRONT
        if (v.rear == null) return Kind.TOP
        return if (cameraTilt >= 30.0 && kotlin.math.abs(diff) <= 40.0) Kind.REAR else Kind.TOP
    }
}
