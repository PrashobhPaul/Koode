package com.trippulse.app.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.trippulse.app.R
import com.trippulse.app.core.Profile
import com.trippulse.app.core.TimeFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Turns a finished journey into a PDF the traveller can keep, print or send.
 *
 * Two documents, one renderer: the **timeline** (what happened, when) and the
 * **money tracker** (what it cost). Both carry the Koode wordmark and a small,
 * faint maker's mark low on the page, and both are produced with Android's own
 * [PdfDocument] — a flat, painted page with no form fields and no embedded
 * text layer to edit, which is exactly the "non-editable" the product asks for.
 * Nothing leaves the device: the file is written to app-private cache and only
 * ever shared through an explicit user action.
 */
object JourneyPdf {

    // ---- page geometry (A4 at 72dpi, the PdfDocument convention) ----
    /** Header mark, in points. Matches the wordmark's cap height beside it. */
    private const val MARK_SIZE = 22f

    /** Watermark size: a small, faint mark low on the page — a maker's mark,
     *  not a stamp across the content. */
    private const val WATERMARK_SIZE = 132f

    /**
     * Rasterises the app's icon for use on the page.
     *
     * Rendered once per document and reused on every page. A failure here is
     * never fatal -- the caller falls back to the wordmark -- because a
     * missing logo must not be the reason a traveller cannot export the
     * record of their journey.
     */
    private fun markBitmap(context: Context): Bitmap? = runCatching {
        // The traveller's own logo mark (cropped from assets/Logo.png), so the
        // document a family hands to the police carries the authentic brand,
        // not a redrawn approximation of it.
        val d = ContextCompat.getDrawable(context, R.drawable.koode_icon) ?: return null
        // Generously oversampled: the same bitmap is drawn small in the header
        // and very large as the watermark, and upscaling the second from the
        // first would show.
        d.toBitmap(MARK_RASTER_PX, MARK_RASTER_PX)
    }.getOrNull()

    private const val MARK_RASTER_PX = 512

    /**
     * The traveller's photo (drawn circular) or their chosen silhouette, for
     * the document header. Second value is true when it's a real photo.
     * Never fatal — a missing avatar just means no picture on the page.
     */
    private fun avatarForPdf(context: Context): Pair<Bitmap?, Boolean> {
        // The same rule as every avatar in the app (AvatarFace): photo, else
        // the first letter of the name, else the neutral silhouette.
        val photo = Profile.photoPath(context)?.let { path ->
            Profile.decodePhoto(path, 256)
        }
        when (val face = com.trippulse.app.domain.AvatarFace.resolve(photo != null, Profile.name(context))) {
            com.trippulse.app.domain.AvatarFace.Photo -> return photo to true
            is com.trippulse.app.domain.AvatarFace.Initial -> initialBitmap(context, face.mark)?.let { return it to true }
            com.trippulse.app.domain.AvatarFace.Neutral -> Unit
        }
        val res = when (Profile.avatarStyle(context)) {
            Profile.AvatarStyle.MALE -> R.drawable.ic_avatar_male
            Profile.AvatarStyle.FEMALE -> R.drawable.ic_avatar_female
            Profile.AvatarStyle.NEUTRAL -> R.drawable.ic_avatar_neutral
        }
        val bmp = runCatching {
            val d = ContextCompat.getDrawable(context, res)?.mutate() ?: return@runCatching null
            d.setTint(MUTED)
            d.toBitmap(256, 256)
        }.getOrNull()
        return bmp to false
    }

    /** The traveller's initial on their colour, as a round bitmap for the header. */
    private fun initialBitmap(context: Context, mark: com.trippulse.app.domain.PersonMark): Bitmap? = runCatching {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = mark.argb
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.parseColor("#07131D")
        paint.textSize = size * 0.44f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.sora) }
            .getOrNull()?.let { android.graphics.Typeface.create(it, android.graphics.Typeface.BOLD) }
            ?: android.graphics.Typeface.DEFAULT_BOLD
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(mark.initial, size / 2f, y, paint)
        bmp
    }.getOrNull()

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 44f
    private const val LINE = 18f

    /** Height of the recorded-route panel on the page, in points. */
    private const val MAP_HEIGHT = 188f

    // ---- brand ----
    private val INK = Color.parseColor("#0B1E2D")
    private val TEAL = Color.parseColor("#2DD4BF")
    private val TEAL_DEEP = Color.parseColor("#14B8A6")
    private val MUTED = Color.parseColor("#6B8391")
    private val RULE = Color.parseColor("#DCE6EB")
    private val OK = Color.parseColor("#0E9F6E")
    private val MAP_BG = Color.parseColor("#EAF3F5")
    private val START_DOT = Color.parseColor("#0E9F6E")
    private val END_DOT = Color.parseColor("#0EA5E9")

    /** One printed row: a time, a label and an optional right-hand value. */
    data class Row(val left: String, val middle: String, val right: String = "")

    /** A titled block of rows, optionally with a column header. */
    data class Section(val title: String, val header: Row?, val rows: List<Row>, val note: String? = null)

    /** One headline figure in the dashboard band, e.g. "Distance / 412 km". */
    data class Figure(val label: String, val value: String)

    /**
     * Everything a document needs, already formatted by the caller.
     *
     * [figures] and [insights] are what stop this being a printout of a
     * database table: the same analysed dashboard the app shows, so the
     * exported document answers "how did that go?" rather than only "what
     * happened, in order".
     */
    data class Document(
        val title: String,
        val subtitle: String,
        val meta: List<String>,
        /** A prominent status line under the title, e.g. "Journey completed". */
        val status: String? = null,
        val figures: List<Figure> = emptyList(),
        val insights: List<String> = emptyList(),
        /**
         * The recorded GPS path as (lat, lng) points, drawn as a plain
         * visualization of where the journey went — never a route-quality
         * judgement. Empty = no map (falls back to the textual origin/dest).
         */
        val path: List<Pair<Double, Double>> = emptyList(),
        val sections: List<Section>,
        val fileLabel: String,
        /** Short reference shown in the footer, e.g. "Trip TP-12345". */
        val footerRef: String? = null
    )

    /**
     * Renders [doc] and returns a shareable file.
     *
     * @param context any context; the file lands in `cacheDir/exports`.
     */
    suspend fun write(context: Context, doc: Document): File = withContext(Dispatchers.IO) {
        val pdf = PdfDocument()
        val (avatarBmp, avatarIsPhoto) = avatarForPdf(context)
        val painter = Painter(markBitmap(context), avatarBmp, avatarIsPhoto)
        var pageNumber = 1
        var page = pdf.startPage(pageInfo(pageNumber))
        var canvas = page.canvas
        var y = painter.drawHeader(canvas, doc, first = true)
        if (doc.figures.isNotEmpty()) y = painter.drawFigures(canvas, doc.figures, y)

        // The recorded route, if we have a path to draw. Kept near the top,
        // right after the numbers, per the report hierarchy (map before detail).
        if (doc.path.size >= 2) {
            val mapBlock = LINE * 1.4f + MAP_HEIGHT + LINE * 0.8f
            if (y + mapBlock > PAGE_H - MARGIN) {
                painter.drawFooter(canvas, pageNumber, doc.footerRef)
                pdf.finishPage(page)
                pageNumber++
                page = pdf.startPage(pageInfo(pageNumber))
                canvas = page.canvas
                y = painter.drawHeader(canvas, doc, first = false)
            }
            y = painter.drawSectionTitle(canvas, "Recorded route", y)
            y = painter.drawPath(canvas, doc.path, y)
        }

        if (doc.insights.isNotEmpty()) y = painter.drawInsights(canvas, doc.insights, y)

        for (section in doc.sections) {
            // A section header stranded at the foot of a page reads badly, so
            // break early if the title plus one row would not fit.
            if (y + LINE * 3 > PAGE_H - MARGIN) {
                painter.drawFooter(canvas, pageNumber, doc.footerRef)
                pdf.finishPage(page)
                pageNumber++
                page = pdf.startPage(pageInfo(pageNumber))
                canvas = page.canvas
                y = painter.drawHeader(canvas, doc, first = false)
            }
            y = painter.drawSectionTitle(canvas, section.title, y)
            section.header?.let { y = painter.drawRow(canvas, it, y, header = true) }

            for (row in section.rows) {
                if (y + LINE > PAGE_H - MARGIN - LINE) {
                    painter.drawFooter(canvas, pageNumber, doc.footerRef)
                    pdf.finishPage(page)
                    pageNumber++
                    page = pdf.startPage(pageInfo(pageNumber))
                    canvas = page.canvas
                    y = painter.drawHeader(canvas, doc, first = false)
                    y = painter.drawSectionTitle(canvas, "${section.title} (continued)", y)
                    section.header?.let { y = painter.drawRow(canvas, it, y, header = true) }
                }
                y = painter.drawRow(canvas, row, y, header = false)
            }
            section.note?.let { y = painter.drawNote(canvas, it, y) }
            y += LINE * 0.6f
        }

        painter.drawFooter(canvas, pageNumber, doc.footerRef)
        pdf.finishPage(page)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // A stable name per journey+document so re-exporting overwrites rather
        // than littering the cache with near-identical files.
        val file = File(dir, "${doc.fileLabel}.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        file
    }

    /**
     * Copies a finished PDF into the phone's Downloads (no permission needed
     * on Android 10+; the app's own Downloads folder before that). Returns
     * where it went, or null if it could not be saved.
     */
    suspend fun saveToDownloads(context: Context, file: File, displayName: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Downloads/$displayName"
            } else {
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: return@runCatching null
                val out = File(dir, displayName)
                file.copyTo(out, overwrite = true)
                out.absolutePath
            }
        }.getOrNull()
    }

    /** Wraps a rendered file in a share intent the caller can launch. */
    /** Opens [file] in the phone's PDF viewer (falls back to the share sheet if there is none). */
    fun viewIntent(context: Context, file: File, title: String): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return if (view.resolveActivity(context.packageManager) != null) view else shareIntent(context, file, title)
    }

    fun shareIntent(context: Context, file: File, title: String): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            title
        )
    }

    private fun pageInfo(number: Int) =
        PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create()

    /**
     * All drawing lives here so page breaks above stay readable. Paints are
     * allocated once per document rather than per row.
     */
    private class Painter(
        private val mark: Bitmap?,
        private val avatar: Bitmap?,
        private val avatarIsPhoto: Boolean
    ) {
        private val disc = Paint().apply { isAntiAlias = true; color = Color.argb(30, 45, 212, 191) }
        private val title = Paint().apply {
            isAntiAlias = true; color = INK; textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val subtitle = Paint().apply {
            isAntiAlias = true; color = MUTED; textSize = 11f
            typeface = Typeface.SANS_SERIF
        }
        private val route = Paint().apply {
            isAntiAlias = true; color = INK; textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val statusPaint = Paint().apply {
            isAntiAlias = true; color = OK; textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val mapBgPaint = Paint().apply { isAntiAlias = true; color = MAP_BG }
        private val pathPaint = Paint().apply {
            isAntiAlias = true; color = TEAL_DEEP; style = Paint.Style.STROKE
            strokeWidth = 2.4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val startDotPaint = Paint().apply { isAntiAlias = true; color = START_DOT }
        private val endDotPaint = Paint().apply { isAntiAlias = true; color = END_DOT }
        private val dotHalo = Paint().apply { isAntiAlias = true; color = Color.WHITE }
        private val startLegend = Paint().apply {
            isAntiAlias = true; color = START_DOT; textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val endLegend = Paint().apply {
            isAntiAlias = true; color = END_DOT; textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val sectionPaint = Paint().apply {
            isAntiAlias = true; color = INK; textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val body = Paint().apply {
            isAntiAlias = true; color = INK; textSize = 11f; typeface = Typeface.SANS_SERIF
        }
        private val bodyMuted = Paint().apply {
            isAntiAlias = true; color = MUTED; textSize = 11f; typeface = Typeface.SANS_SERIF
        }
        private val figureValue = Paint().apply {
            isAntiAlias = true; color = INK; textSize = 17f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val headerCell = Paint().apply {
            isAntiAlias = true; color = MUTED; textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val rule = Paint().apply { color = RULE; strokeWidth = 0.6f }
        private val accent = Paint().apply { isAntiAlias = true; color = TEAL }
        private val imagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        /** Faint enough to read straight through, present enough to be seen. */
        private val watermarkPaint = Paint(imagePaint).apply { alpha = 8 }

        fun drawHeader(canvas: Canvas, doc: Document, first: Boolean): Float {
            drawWatermark(canvas)
            drawMark(canvas, MARGIN, 46f)
            canvas.drawText("Koode", MARGIN + 26f, 52f, title)
            canvas.drawText("Always with you", MARGIN + 26f, 66f, subtitle)
            if (first) drawAvatar(canvas, PAGE_W - MARGIN - 20f, 44f, 20f)

            var y = 96f
            if (first) {
                canvas.drawText(doc.title, MARGIN, y, title)
                y += LINE * 1.1f
                doc.status?.let {
                    canvas.drawText("✓  $it", MARGIN, y, statusPaint)
                    y += LINE * 1.15f
                }
                canvas.drawText(doc.subtitle, MARGIN, y, route)
                y += LINE * 0.95f
                doc.meta.forEach {
                    canvas.drawText(it, MARGIN, y, bodyMuted)
                    y += LINE * 0.8f
                }
                y += LINE * 0.4f
            }
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
            return y + LINE
        }

        /**
         * The Koode mark, rendered from the app's own icon asset.
         *
         * This used to be redrawn here in Canvas calls, which meant the
         * document someone was sent carried a slightly different logo from the
         * one on their home screen. Rendering the drawable keeps them the same
         * thing by construction: change the asset and every surface follows.
         */
        private fun drawAvatar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val bmp = avatar ?: return
            canvas.drawCircle(cx, cy, r, disc)
            if (avatarIsPhoto) {
                val save = canvas.save()
                canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
                canvas.drawBitmap(bmp, null, RectF(cx - r, cy - r, cx + r, cy + r), imagePaint)
                canvas.restoreToCount(save)
            } else {
                canvas.drawBitmap(bmp, null, RectF(cx - r, cy - r, cx + r, cy + r), imagePaint)
            }
        }

        private fun drawMark(canvas: Canvas, x: Float, y: Float) {
            val bmp = mark ?: return
            val size = MARK_SIZE
            canvas.drawBitmap(
                bmp,
                null,
                RectF(x, y - size, x + size, y),
                imagePaint
            )
        }

        /**
         * The watermark: the mark itself, small and very faint, low on the
         * page — a maker's mark, never a stamp across the content. It sits in
         * the lower margin band so it can never compete with the journey
         * information above it.
         */
        private fun drawWatermark(canvas: Canvas) {
            // A small, faint maker's mark low on the page — never behind the
            // content it would compete with. Placed near the foot, centred.
            val bmp = mark ?: return
            val size = WATERMARK_SIZE
            val cx = PAGE_W / 2f
            val cy = PAGE_H - 150f
            canvas.drawBitmap(
                bmp,
                null,
                RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2),
                watermarkPaint
            )
        }

        /**
         * The dashboard band: figures laid out three to a row, each a label
         * above a large value, so the page opens with the answer.
         */
        fun drawFigures(canvas: Canvas, figures: List<Figure>, y0: Float): Float {
            val columns = 3
            val usable = PAGE_W - MARGIN * 2
            val cellWidth = usable / columns
            var y = y0 + LINE * 0.2f
            figures.chunked(columns).forEach { row ->
                row.forEachIndexed { index, figure ->
                    val x = MARGIN + cellWidth * index
                    canvas.drawText(figure.label.uppercase(), x, y, headerCell)
                    canvas.drawText(clip(figure.value, 18), x, y + LINE, figureValue)
                }
                y += LINE * 2.4f
            }
            canvas.drawLine(MARGIN, y - LINE * 0.7f, PAGE_W - MARGIN, y - LINE * 0.7f, rule)
            return y
        }

        /** The plain-English read of those numbers, under its own heading. */
        fun drawInsights(canvas: Canvas, insights: List<String>, y0: Float): Float {
            var y = drawSectionTitle(canvas, "Journey insights", y0)
            insights.forEach {
                canvas.drawText("•  " + clip(it, 92), MARGIN, y, body)
                y += LINE
            }
            return y + LINE * 0.4f
        }

        /**
         * The recorded GPS track, projected into a light panel. This is only a
         * picture of where the journey went — no basemap, no "correct/incorrect
         * route" claim. Start and end are marked; the line is the path recorded.
         */
        fun drawPath(canvas: Canvas, points: List<Pair<Double, Double>>, y0: Float): Float {
            val left = MARGIN
            val top = y0 + LINE * 0.2f
            val right = PAGE_W - MARGIN
            val bottom = top + MAP_HEIGHT
            canvas.drawRoundRect(RectF(left, top, right, bottom), 10f, 10f, mapBgPaint)

            val pad = 16f
            val minLat = points.minOf { it.first }; val maxLat = points.maxOf { it.first }
            val minLng = points.minOf { it.second }; val maxLng = points.maxOf { it.second }
            val midLatRad = Math.toRadians((minLat + maxLat) / 2.0)
            // Equirectangular: scale longitude by cos(lat) so shape isn't skewed.
            val spanX = ((maxLng - minLng) * Math.cos(midLatRad)).coerceAtLeast(1e-9)
            val spanY = (maxLat - minLat).coerceAtLeast(1e-9)
            val availW = (right - left) - pad * 2
            val availH = (bottom - top) - pad * 2
            val scale = minOf(availW / spanX, availH / spanY)
            val drawW = spanX * scale
            val drawH = spanY * scale
            val offX = left + pad + (availW - drawW) / 2
            val offY = top + pad + (availH - drawH) / 2

            fun sx(lng: Double) = (offX + ((lng - minLng) * Math.cos(midLatRad)) * scale).toFloat()
            // Flip Y so north is up.
            fun sy(lat: Double) = (offY + (maxLat - lat) * scale).toFloat()

            val path = Path()
            points.forEachIndexed { i, p ->
                val x = sx(p.second); val yy = sy(p.first)
                if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            }
            canvas.drawPath(path, pathPaint)

            val first = points.first(); val last = points.last()
            fun dot(lat: Double, lng: Double, paint: Paint) {
                val x = sx(lng); val yy = sy(lat)
                canvas.drawCircle(x, yy, 5.5f, dotHalo)
                canvas.drawCircle(x, yy, 4f, paint)
            }
            dot(first.first, first.second, startDotPaint)
            dot(last.first, last.second, endDotPaint)

            // A tiny legend so the two dots are unambiguous.
            canvas.drawText("● Start", left + pad, bottom + LINE * 0.9f, startLegend)
            canvas.drawText("● Latest", left + pad + 64f, bottom + LINE * 0.9f, endLegend)
            return bottom + LINE * 1.4f
        }

        fun drawSectionTitle(canvas: Canvas, text: String, y0: Float): Float {
            val y = y0 + LINE * 0.4f
            canvas.drawText(text, MARGIN, y, sectionPaint)
            canvas.drawRect(RectF(MARGIN, y + 4f, MARGIN + 28f, y + 6f), accent)
            return y + LINE
        }

        fun drawRow(canvas: Canvas, row: Row, y0: Float, header: Boolean): Float {
            val paintLeft = if (header) headerCell else bodyMuted
            val paintMid = if (header) headerCell else body
            val paintRight = if (header) headerCell else body
            canvas.drawText(clip(row.left, 14), MARGIN, y0, paintLeft)
            canvas.drawText(clip(row.middle, 52), MARGIN + 96f, y0, paintMid)
            if (row.right.isNotEmpty()) {
                val w = paintRight.measureText(row.right)
                canvas.drawText(row.right, PAGE_W - MARGIN - w, y0, paintRight)
            }
            val y = y0 + LINE * 0.35f
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
            return y0 + LINE
        }

        fun drawNote(canvas: Canvas, text: String, y0: Float): Float {
            canvas.drawText(clip(text, 96), MARGIN, y0 + LINE * 0.4f, bodyMuted)
            return y0 + LINE
        }

        fun drawFooter(canvas: Canvas, pageNumber: Int, ref: String?) {
            val yb = PAGE_H - MARGIN + 12f
            val left = buildString {
                append("Koode")
                ref?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
                append("  ·  Generated ").append(TimeFmt.dateTime(System.currentTimeMillis()))
            }
            canvas.drawText(clip(left, 88), MARGIN, yb, subtitle)
            val pageText = "Page $pageNumber"
            val w = subtitle.measureText(pageText)
            canvas.drawText(pageText, PAGE_W - MARGIN - w, yb, subtitle)
        }

        private fun clip(text: String, max: Int): String =
            if (text.length <= max) text else text.take(max - 1) + "…"
    }
}
