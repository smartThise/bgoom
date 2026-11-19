package com.bgoom.app.idphoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.sqrt

/**
 * Studio-grade ID Photo Composer modeled after HivisionIDPhotos `photo_adjuster.py`:
 * - Face/eye-centric standard Chinese ID geometry (head area ratio ~0.20, eye line ~43% from top,
 *   hair-top headroom automatically clamped to 9%-13%, shoulders flush to bottom edge).
 * - Seamless fallback to alpha-silhouette framing when face is small/profiled.
 * - Clean 6-inch print-sheet tiling (1800x1200 @ 300DPI) with cutting guides.
 */
object IdPhotoComposer {

    fun composeIdPhoto(
        matting: MattingResult,
        spec: IdPhotoSpec,
        bgColor: IdBgColor,
        userScale: Float = 1.0f,
        userOffsetX: Float = 0.0f, // normalized [-0.5..0.5]
        userOffsetY: Float = 0.0f, // normalized [-0.5..0.5]
    ): Bitmap {
        val outW = spec.widthPx
        val outH = spec.heightPx
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // 1. Background fill (solid or vertical studio gradient)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (bgColor.topColorArgb == bgColor.bottomColorArgb) {
            bgPaint.color = bgColor.topColorArgb
        } else {
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, outH.toFloat(),
                bgColor.topColorArgb,
                bgColor.bottomColorArgb,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), bgPaint)

        // 2. Compute studio-standard ID photo framing
        val fg = matting.foregroundArgb
        val fgW = fg.width.toFloat()
        val fgH = fg.height.toFloat()
        val face = matting.face

        var baseScale: Float
        var anchorXInFg: Float
        var baseTop: Float

        if (face != null) {
            // Standard Chinese ID photo geometry (exact HivisionIDPhotos ratios):
            // Inter-ocular distance is ~24% of ID photo width (~head width 56% of photo width)
            // Face rectangle w ≈ 2.25 * eyeDist, h ≈ 2.65 * eyeDist, faceArea / photoArea ≈ 0.20
            val eyeDistPx = face.eyeDistRatioW * fgW
            val faceArea = (2.25f * eyeDistPx) * (2.65f * eyeDistPx)
            val targetCropArea = faceArea / 0.20f
            val scaleFromArea = sqrt((outW.toFloat() * outH.toFloat()) / targetCropArea.coerceAtLeast(1f))
            val scaleFromWidth = (outW * 0.245f) / eyeDistPx.coerceAtLeast(1f)
            baseScale = (scaleFromArea * 0.55f + scaleFromWidth * 0.45f)

            anchorXInFg = face.eyeMidXRatio * fgW
            val eyeYInDraw = face.eyeMidYRatio * fgH * baseScale
            // Place eye line near 43% from top
            baseTop = outH * 0.43f - eyeYInDraw

            // Clamp hair top (from alpha matte) into clean official headroom [9%..14%] of photo height
            val hairTopInDraw = matting.alphaTopRatio * fgH * baseScale
            val actualHeadroom = baseTop + hairTopInDraw
            val minHeadroom = outH * 0.085f
            val maxHeadroom = outH * 0.145f
            if (actualHeadroom < minHeadroom) {
                baseTop += (minHeadroom - actualHeadroom)
            } else if (actualHeadroom > maxHeadroom) {
                baseTop -= (actualHeadroom - maxHeadroom)
            }

            // Ensure torso/shoulders reach the bottom edge (never leave a floating torso gap!)
            val bottomInDraw = matting.alphaBottomRatio * fgH * baseScale
            if (baseTop + bottomInDraw < outH * 0.99f) {
                val neededScale = (outH * 1.01f - outH * 0.10f) /
                    ((matting.alphaBottomRatio - matting.alphaTopRatio).coerceAtLeast(0.25f) * fgH)
                if (neededScale > baseScale) {
                    baseScale = minOf(neededScale, baseScale * 1.45f)
                    baseTop = outH * 0.10f - matting.alphaTopRatio * fgH * baseScale
                }
            }
        } else {
            // Fallback when face detector didn't lock (e.g. heavy glasses/angle): frame cleanly from silhouette
            val subjH = ((matting.alphaBottomRatio - matting.alphaTopRatio).coerceAtLeast(0.35f)) * fgH
            val subjW = ((matting.alphaRightRatio - matting.alphaLeftRatio).coerceAtLeast(0.25f)) * fgW
            baseScale = maxOf((outH * 0.90f) / subjH, (outW * 0.92f) / subjW)
            anchorXInFg = matting.alphaCenterXRatio * fgW
            baseTop = outH * 0.10f - matting.alphaTopRatio * fgH * baseScale
        }

        val finalScale = baseScale * userScale.coerceIn(0.65f, 1.9f)
        val drawW = fgW * finalScale
        val drawH = fgH * finalScale
        val scaleRatio = finalScale / baseScale
        // Zoom around photo center naturally
        val left = (outW * 0.5f - anchorXInFg * finalScale) + userOffsetX * outW
        val top = (outH * 0.5f + (baseTop - outH * 0.5f) * scaleRatio) + userOffsetY * outH

        val dstRect = RectF(left, top, left + drawW, top + drawH)
        val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(fg, null, dstRect, fgPaint)

        return result
    }

    /**
     * Generates a standard 6-inch photo paper print sheet (1800 x 1200 px at 300 DPI).
     */
    fun composeSixInchPrintSheet(singleIdPhoto: Bitmap): Bitmap {
        val sheetW = 1800
        val sheetH = 1200
        val margin = 48
        val gap = 28

        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val photoW = singleIdPhoto.width
        val photoH = singleIdPhoto.height

        val cols = ((sheetW - 2 * margin + gap) / (photoW + gap)).coerceAtLeast(1)
        val rows = ((sheetH - 2 * margin + gap) / (photoH + gap)).coerceAtLeast(1)

        val gridW = cols * photoW + (cols - 1) * gap
        val gridH = rows * photoH + (rows - 1) * gap
        val startX = (sheetW - gridW) / 2f
        val startY = (sheetH - gridH) / 2f

        val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = startX + c * (photoW + gap)
                val y = startY + r * (photoH + gap)
                val rect = RectF(x, y, x + photoW, y + photoH)
                canvas.drawBitmap(singleIdPhoto, null, rect, photoPaint)
                canvas.drawRect(rect, guidePaint)
            }
        }
        return sheet
    }
}
