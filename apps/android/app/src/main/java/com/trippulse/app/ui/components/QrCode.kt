package com.trippulse.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * A QR code someone can scan to follow a journey.
 *
 * Always black-on-white regardless of the app theme — a scanner needs the
 * contrast, and the surrounding card provides the light background. Returns
 * nothing (draws nothing) if encoding ever fails, so a share screen never
 * breaks over a QR.
 */
private fun qrBitmap(content: String, sizePx: Int): Bitmap? = try {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) pixels[row + x] = if (matrix.get(x, y)) black else white
    }
    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
} catch (_: Exception) {
    null
}

@Composable
fun QrImage(content: String, modifier: Modifier = Modifier, sizeDp: Dp = 200.dp) {
    val px = with(LocalDensity.current) { sizeDp.roundToPx() }.coerceIn(1, 1024)
    val bmp = remember(content, px) { qrBitmap(content, px) } ?: return
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = "QR code to follow this journey",
        modifier = modifier.size(sizeDp)
    )
}
