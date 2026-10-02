package com.trippulse.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import com.trippulse.app.domain.Pictures
import com.trippulse.app.ui.components.KoodeArt
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The traveller's vehicle on the map: the same illustration as everywhere
 * else in Koode, drawn upright like a sticker — a crisp white outline so it
 * reads on any map, a soft shadow on the ground beneath it, and turned to
 * face the way it is going across the screen. A plane flies a little above
 * its shadow.
 *
 * Images are built once per (mode, facing, airborne) and added to the map
 * style on first use.
 */
internal object VehicleMarker {

    /** Width of the illustration on screen. Long vehicles get a little more room. */
    private fun widthDp(mode: String?): Float = when (Pictures.mode(mode)) {
        "bus", "train", "metro", "ship" -> 84f
        "flight" -> 80f
        "bike", "auto" -> 60f
        else -> 70f
    }

    private const val OUTLINE_DP = 2.5f
    private const val SHADOW_H_DP = 12f
    private const val LIFT_DP = 22f

    fun name(mode: String?, faceLeft: Boolean, airborne: Boolean): String =
        "kd-veh-${Pictures.mode(mode) ?: "car"}-${if (faceLeft) "l" else "r"}${if (airborne) "-air" else ""}"

    fun bitmap(context: Context, mode: String?, faceLeft: Boolean, airborne: Boolean): Bitmap? {
        val res = KoodeArt.mode(mode) ?: KoodeArt.mode("CAR") ?: return null
        val src = BitmapFactory.decodeResource(context.resources, res) ?: return null
        val d = context.resources.displayMetrics.density

        val artW = (widthDp(mode) * d).roundToInt()
        val artH = (src.height * artW.toFloat() / src.width).roundToInt().coerceAtMost((64f * d).roundToInt())
        val fitW = (src.width * artH.toFloat() / src.height).roundToInt().coerceAtMost(artW)
        val outline = OUTLINE_DP * d
        val shadowH = SHADOW_H_DP * d
        val lift = if (airborne) LIFT_DP * d else 0f
        val pad = (outline + 2 * d).roundToInt()

        val w = fitW + 2 * pad
        val h = (artH + 2 * pad + lift + shadowH / 2).roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(out)

        // The ground shadow: a soft ellipse, smaller and fainter for a plane in the air.
        val cx = w / 2f
        val cy = h - shadowH / 2f
        val rx = fitW * (if (airborne) 0.30f else 0.42f)
        val ry = shadowH / 2f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, rx, intArrayOf(Color.argb(if (airborne) 70 else 120, 0, 0, 0), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.save()
        canvas.scale(1f, ry / rx, cx, cy)
        canvas.drawCircle(cx, cy, rx, shadow)
        canvas.restore()

        // The illustration, mirrored when it must face the other way.
        val mirror = faceLeft != Pictures.modeFacesLeft(mode)
        val scaled = Bitmap.createScaledBitmap(src, fitW, artH, true)
        val art = if (mirror) Bitmap.createBitmap(scaled, 0, 0, fitW, artH, Matrix().apply { preScale(-1f, 1f) }, true) else scaled
        val left = pad.toFloat()
        val top = h - shadowH / 2f - lift - artH - outline * 0.5f

        // A white sticker outline: the silhouette stamped in white all around it.
        val white = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        for (i in 0 until 16) {
            val a = i * Math.PI / 8
            canvas.drawBitmap(art, left + (cos(a) * outline).toFloat(), top + (sin(a) * outline).toFloat(), white)
        }
        canvas.drawBitmap(art, left, top, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        if (art !== scaled) art.recycle()
        if (scaled !== src) scaled.recycle()
        src.recycle()
        return out
    }
}
