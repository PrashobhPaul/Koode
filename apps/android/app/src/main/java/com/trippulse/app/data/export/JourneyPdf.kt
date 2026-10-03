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
 * Turns a report into a PDF the traveller can keep, print or send.
 *
 * Produced with Android's own [PdfDocument]: a flat, painted page with no form
 * fields to edit. Nothing leaves the device: the file is written to
 * app-private cache and only ever shared through an explicit user action.
 */
object JourneyPdf {

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

    /** The muted grey a silhouette avatar is tinted with. */
    private val MUTED = Color.parseColor("#6B8391")

    /**
     * Renders [report] as a PDF and returns the file (in `cacheDir/exports`).
     *
     * The report is laid out in plain Kotlin (see data/export/report); this
     * only supplies the page canvas, the fonts and the pictures.
     */
    suspend fun write(context: Context, report: com.trippulse.app.data.export.report.Report): File = withContext(Dispatchers.IO) {
        val (avatar, isPhoto) = avatarForPdf(context)
        val assets = AndroidReportSurface.Assets(context, markBitmap(context), avatar, isPhoto)
        val pages = com.trippulse.app.data.export.report.Paginator.paginate(AndroidReportSurface(assets, null), report)
        val pdf = PdfDocument()
        try {
            pages.indices.forEach { i ->
                val page = pdf.startPage(
                    PdfDocument.PageInfo.Builder(
                        com.trippulse.app.data.export.report.Paginator.PAGE_W.toInt(),
                        com.trippulse.app.data.export.report.Paginator.PAGE_H.toInt(), i + 1
                    ).create()
                )
                com.trippulse.app.data.export.report.Paginator.drawPage(AndroidReportSurface(assets, page.canvas), report, pages, i)
                pdf.finishPage(page)
            }
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            // A stable name per journey and document, so re-exporting overwrites.
            val file = File(dir, "${report.fileLabel}.pdf")
            FileOutputStream(file).use { pdf.writeTo(it) }
            file
        } finally {
            pdf.close()
        }
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
}
