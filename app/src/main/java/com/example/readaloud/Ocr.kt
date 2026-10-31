package com.example.readaloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import java.io.File

/** On-device OCR (ML Kit, Devanagari model reads Hindi and Latin/English). Call from a background thread. */
object Ocr {
    private const val PDF_RENDER_LONG_SIDE = 2200   // px, about 200 dpi for A4
    private const val IMAGE_MAX_SIDE = 3000

    private val recognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    fun recognize(bmp: Bitmap): String =
        Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0))).text.trim()

    fun renderAndRecognize(renderer: PdfRenderer, index: Int): String {
        val bmp = renderer.openPage(index).use { page ->
            val scale = PDF_RENDER_LONG_SIDE.toFloat() / maxOf(page.width, page.height)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            b
        }
        try {
            return recognize(bmp)
        } finally {
            bmp.recycle()
        }
    }

    fun recognizeImageFile(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > IMAGE_MAX_SIDE) sample *= 2

        var bmp = BitmapFactory.decodeFile(
            file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: throw IllegalArgumentException("Could not decode image.")

        val degrees = try {
            when (ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }

        if (degrees != 0f) {
            val rotated = Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true
            )
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        try {
            return recognize(bmp)
        } finally {
            bmp.recycle()
        }
    }
}
