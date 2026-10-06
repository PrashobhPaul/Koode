package com.trippulse.app.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.trippulse.app.R
import com.trippulse.app.data.export.report.Face
import com.trippulse.app.data.export.report.Surface
import com.trippulse.app.data.export.report.TextStyle
import com.trippulse.app.ui.components.KoodeArt

/**
 * The report [Surface] on a PDF page canvas: the app's own fonts (Sora and
 * DM Sans), its illustrations, the traveller's avatar and the Koode mark.
 * With a null canvas it only measures, for laying pages out.
 */
class AndroidReportSurface(private val assets: Assets, private val canvas: Canvas?) : Surface {

    /** Fonts and bitmaps, loaded once per document and shared by every page. */
    class Assets(
        private val context: Context,
        val mark: Bitmap?,
        val avatar: Bitmap?,
        val avatarIsPhoto: Boolean
    ) {
        private val sora: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.sora) }.getOrNull() ?: Typeface.DEFAULT
        private val dm: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.dm_sans) }.getOrNull() ?: Typeface.DEFAULT
        private val faces = HashMap<Pair<Face, Int>, Typeface>()
        private val pictures = HashMap<String, Bitmap?>()

        fun typeface(face: Face, weight: Int): Typeface = faces.getOrPut(face to weight) {
            val base = if (face == Face.HEAD) sora else dm
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, false)
            else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }

        /**
         * An illustration, decoded once and kept as one bitmap so every place
         * it appears in the PDF shares a single embedded image.
         */
        fun picture(name: String): Bitmap? = pictures.getOrPut(name) {
            val res = KoodeArt.file(name) ?: return@getOrPut null
            runCatching {
                val raw = BitmapFactory.decodeResource(context.resources, res) ?: return@runCatching null
                val max = 288
                if (raw.width <= max && raw.height <= max) raw
                else {
                    val k = max.toFloat() / maxOf(raw.width, raw.height)
                    Bitmap.createScaledBitmap(raw, (raw.width * k).toInt(), (raw.height * k).toInt(), true).also { if (it !== raw) raw.recycle() }
                }
            }.getOrNull()
        }
    }

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun textPaint(style: TextStyle): Paint = paintText.apply {
        typeface = assets.typeface(style.face, style.weight)
        textSize = style.size
        color = style.color
        letterSpacing = style.tracking
    }

    override fun measure(text: String, style: TextStyle): Float = textPaint(style.forText(text)).measureText(text)

    override fun text(text: String, x: Float, baseline: Float, style: TextStyle) {
        canvas?.drawText(text, x, baseline, textPaint(style.forText(text)))
    }

    override fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float) {
        val c = canvas ?: return
        fill.color = color
        if (radius > 0) c.drawRoundRect(RectF(l, t, r, b), radius, radius, fill) else c.drawRect(l, t, r, b, fill)
    }

    override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {
        val c = canvas ?: return
        stroke.color = color; stroke.strokeWidth = width; stroke.pathEffect = null
        if (radius > 0) c.drawRoundRect(RectF(l, t, r, b), radius, radius, stroke) else c.drawRect(l, t, r, b, stroke)
    }

    override fun circle(cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color; canvas?.drawCircle(cx, cy, r, fill)
    }

    override fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float) {
        stroke.color = color; stroke.strokeWidth = width; stroke.pathEffect = null
        canvas?.drawCircle(cx, cy, r, stroke)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, dash: Float) {
        stroke.color = color; stroke.strokeWidth = width
        stroke.pathEffect = if (dash > 0) DashPathEffect(floatArrayOf(dash, dash * 0.8f), 0f) else null
        canvas?.drawLine(x1, y1, x2, y2, stroke)
        stroke.pathEffect = null
    }

    override fun polyline(pts: FloatArray, color: Int, width: Float) {
        val c = canvas ?: return
        if (pts.size < 4) return
        val p = Path().apply { moveTo(pts[0], pts[1]); var i = 2; while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 } }
        stroke.color = color; stroke.strokeWidth = width; stroke.pathEffect = null
        c.drawPath(p, stroke)
    }

    override fun polygon(pts: FloatArray, color: Int) {
        val c = canvas ?: return
        val p = Path().apply { moveTo(pts[0], pts[1]); var i = 2; while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }; close() }
        fill.color = color; c.drawPath(p, fill)
    }

    override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, color: Int, width: Float) {
        stroke.color = color; stroke.strokeWidth = width; stroke.pathEffect = null; stroke.strokeCap = Paint.Cap.BUTT
        canvas?.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), startDeg - 90f, sweepDeg, false, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
    }

    override fun image(image: Any, l: Float, t: Float, w: Float, h: Float, radius: Float): Boolean {
        val bmp = image as? Bitmap ?: return false
        val c = canvas ?: return true
        c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(l, t, l + w, t + h), radius, radius, Path.Direction.CW) })
        c.drawBitmap(bmp, null, RectF(l, t, l + w, t + h), bitmapPaint)
        c.restore()
        return true
    }

    override fun picture(name: String, l: Float, t: Float, w: Float, h: Float, mirrored: Boolean): Boolean {
        val bmp = assets.picture(name) ?: return false
        val c = canvas ?: return true
        val k = minOf(w / bmp.width, h / bmp.height)
        val dw = bmp.width * k; val dh = bmp.height * k
        val x = l + (w - dw) / 2; val y = t + (h - dh) / 2
        c.save()
        if (mirrored) c.scale(-1f, 1f, x + dw / 2, y + dh / 2)
        c.drawBitmap(bmp, null, RectF(x, y, x + dw, y + dh), bitmapPaint)
        c.restore()
        return true
    }

    override fun avatar(cx: Float, cy: Float, r: Float): Boolean {
        val bmp = assets.avatar ?: return false
        val c = canvas ?: return true
        if (assets.avatarIsPhoto) {
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val k = (r * 2) / minOf(bmp.width, bmp.height)
            shader.setLocalMatrix(android.graphics.Matrix().apply {
                setScale(k, k)
                postTranslate(cx - bmp.width * k / 2, cy - bmp.height * k / 2)
            })
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
            c.drawCircle(cx, cy, r, p)
        } else {
            fill.color = android.graphics.Color.WHITE
            c.drawCircle(cx, cy, r, fill)
            c.drawBitmap(bmp, null, RectF(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f), bitmapPaint)
        }
        return true
    }

    override fun mark(l: Float, t: Float, size: Float, alpha: Float): Boolean {
        val bmp = assets.mark ?: return false
        val c = canvas ?: return true
        c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(l, t, l + size, t + size), size * 0.22f, size * 0.22f, Path.Direction.CW) })
        val p = Paint(bitmapPaint).apply { this.alpha = (alpha * 255).toInt() }
        c.drawBitmap(bmp, null, RectF(l, t, l + size, t + size), p)
        c.restore()
        return true
    }
}
