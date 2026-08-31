package com.trippulse.app.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.trippulse.app.R
import com.trippulse.app.BuildConfig
import com.trippulse.app.core.TimeFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Turns a finished journey into a PDF the traveller can keep, print or send.
 *
 * Two documents, one renderer: the **timeline** (what happened, when) and the
 * **money tracker** (what it cost). Both carry the Koode wordmark and a
 * diagonal watermark, and both are produced with Android's own
 * [PdfDocument] — a flat, painted page with no form fields and no embedded
 * text layer to edit, which is exactly the "non-editable" the product asks for.
 * Nothing leaves the device: the file is written to app-private cache and only
 * ever shared through an explicit user action.
 */
object JourneyPdf {

    // ---- page geometry (A4 at 72dpi, the PdfDocument convention) ----
    /** Header mark, in points. Matches the wordmark's cap height beside it. */
    private const val MARK_SIZE = 22f

    /** Watermark size: large enough to be unmistakable, not so large it tiles. */
    private const val WATERMARK_SIZE = 320f

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

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 44f
    private const val LINE = 18f

    // ---- brand ----
    private val INK = Color.parseColor("#0B1E2D")
    private val TEAL = Color.parseColor("#2DD4BF")
    private val MUTED = Color.parseColor("#6B8391")
    private val RULE = Color.parseColor("#DCE6EB")

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
        val figures: List<Figure> = emptyList(),
        val insights: List<String> = emptyList(),
        val sections: List<Section>,
        val fileLabel: String
    )

    /**
     * Renders [doc] and returns a shareable file.
     *
     * @param context any context; the file lands in `cacheDir/exports`.
     */
    suspend fun write(context: Context, doc: Document): File = withContext(Dispatchers.IO) {
        val pdf = PdfDocument()
        val painter = Painter(markBitmap(context))
        var pageNumber = 1
        var page = pdf.startPage(pageInfo(pageNumber))
        var canvas = page.canvas
        var y = painter.drawHeader(canvas, doc, first = true)
        if (doc.figures.isNotEmpty()) y = painter.drawFigures(canvas, doc.figures, y)
        if (doc.insights.isNotEmpty()) y = painter.drawInsights(canvas, doc.insights, y)

        for (section in doc.sections) {
            // A section header stranded at the foot of a page reads badly, so
            // break early if the title plus one row would not fit.
            if (y + LINE * 3 > PAGE_H - MARGIN) {
                painter.drawFooter(canvas, pageNumber)
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
                    painter.drawFooter(canvas, pageNumber)
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

        painter.drawFooter(canvas, pageNumber)
        pdf.finishPage(page)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // A stable name per journey+document so re-exporting overwrites rather
        // than littering the cache with near-identical files.
        val file = File(dir, "${doc.fileLabel}.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        file
    }

    /** Wraps a rendered file in a share intent the caller can launch. */
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
    private class Painter(private val mark: Bitmap?) {
        private val title = Paint().apply {
            isAntiAlias = true; color = INK; textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val subtitle = Paint().apply {
            isAntiAlias = true; color = MUTED; textSize = 11f
            typeface = Typeface.SANS_SERIF
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
        private val watermark = Paint().apply {
            isAntiAlias = true
            color = Color.argb(22, 45, 212, 191)
            textSize = 96f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val imagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        /** Faint enough to read straight through, present enough to be seen. */
        private val watermarkPaint = Paint(imagePaint).apply { alpha = 18 }

        fun drawHeader(canvas: Canvas, doc: Document, first: Boolean): Float {
            drawWatermark(canvas)
            drawMark(canvas, MARGIN, 46f)
            canvas.drawText("Koode", MARGIN + 26f, 52f, title)
            canvas.drawText("Always with you", MARGIN + 26f, 66f, subtitle)

            var y = 96f
            if (first) {
                canvas.drawText(doc.title, MARGIN, y, title)
                y += LINE
                canvas.drawText(doc.subtitle, MARGIN, y, subtitle)
                y += LINE * 0.8f
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
         * The watermark: the mark itself, large and faint, behind the page.
         *
         * A word set diagonally across a document is the conventional answer
         * and the weaker one -- it reads as a stamp applied to someone else's
         * paper. The mark at low opacity reads as the paper being ours.
         */
        private fun drawWatermark(canvas: Canvas) {
            canvas.save()
            canvas.rotate(-32f, PAGE_W / 2f, PAGE_H / 2f)
            val bmp = mark
            if (bmp != null) {
                val size = WATERMARK_SIZE
                val cx = PAGE_W / 2f
                val cy = PAGE_H / 2f
                canvas.drawBitmap(
                    bmp,
                    null,
                    RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2),
                    watermarkPaint
                )
            } else {
                // The asset failed to load; a page with no watermark at all
                // would look like a different document, so fall back to type.
                canvas.drawText("KOODE", 92f, PAGE_H / 2f + 30f, watermark)
            }
            canvas.restore()
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

        /** The plain-English read of those numbers. */
        fun drawInsights(canvas: Canvas, insights: List<String>, y0: Float): Float {
            var y = y0
            insights.forEach {
                canvas.drawText("•  " + clip(it, 92), MARGIN, y, body)
                y += LINE
            }
            canvas.drawLine(MARGIN, y - LINE * 0.5f, PAGE_W - MARGIN, y - LINE * 0.5f, rule)
            return y + LINE * 0.4f
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

        fun drawFooter(canvas: Canvas, pageNumber: Int) {
            val line = "Generated by Koode ${BuildConfig.VERSION_NAME} on " +
                TimeFmt.dateTime(System.currentTimeMillis()) + "   ·   Page $pageNumber"
            canvas.drawText(line, MARGIN, PAGE_H - MARGIN + 12f, subtitle)
        }

        private fun clip(text: String, max: Int): String =
            if (text.length <= max) text else text.take(max - 1) + "…"
    }
}
