package com.bgoom.app.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * Zero-dependency PDF export & page preview renderer using Android native PdfDocument / PdfRenderer.
 */
object PdfExporter {

    fun exportBitmapsToPdf(pages: List<Bitmap>, outputFile: File): File {
        require(pages.isNotEmpty()) { "至少需要一页扫描图" }
        outputFile.parentFile?.mkdirs()
        val doc = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        try {
            pages.forEachIndexed { idx, bmp ->
                // Standard A4 point width (595pt = 72dpi A4), adaptive aspect ratio for crisp viewing/printing
                val pageW = 595
                val pageH = ((bmp.height.toFloat() / bmp.width.toFloat()) * pageW).toInt().coerceIn(300, 1200)
                val info = PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create()
                val page = doc.startPage(info)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bmp, null, Rect(0, 0, pageW, pageH), paint)
                doc.finishPage(page)
            }
            FileOutputStream(outputFile).use { fos ->
                doc.writeTo(fos)
            }
            return outputFile
        } finally {
            doc.close()
        }
    }

    fun renderPdfPages(pdfFile: File, maxPages: Int = 8, targetWidth: Int = 1080): List<Bitmap> {
        val result = mutableListOf<Bitmap>()
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = minOf(renderer.pageCount, maxPages)
                for (i in 0 until count) {
                    renderer.openPage(i).use { page ->
                        val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1).toFloat()
                        val w = targetWidth
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        canvas.drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        result.add(bmp)
                    }
                }
            }
        }
        return result
    }
}
