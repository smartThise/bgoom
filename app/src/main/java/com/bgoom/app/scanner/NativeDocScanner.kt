package com.bgoom.app.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

enum class DocFilter(val nativeId: Int, val label: String) {
    MAGIC_COLOR(1, "去影增白"),
    CRISP_BW(3, "极清黑白"),
    SHADOW_FREE_GRAY(2, "去影灰度"),
    HIGH_CONTRAST_COLOR(4, "彩色锐化"),
    ORIGINAL(0, "原图矫正"),
}

data class NormPoint(val x: Float, val y: Float)

data class QuadDetectionResult(
    val corners: List<NormPoint>, // TL, TR, BR, BL in [0..1]
    val confident: Boolean,
)

object NativeDocScanner {
    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("bgoom_scanner")
                    loaded = true
                }
            }
        }
    }

    private external fun nativeDetectQuad(bitmap: Bitmap): FloatArray?
    private external fun nativeComputeOutputSize(srcW: Int, srcH: Int, quad: FloatArray, maxSide: Int): IntArray?
    private external fun nativeWarpAndFilter(src: Bitmap, dst: Bitmap, quad: FloatArray, filterMode: Int): Boolean
    private external fun nativeApplyFilterInPlace(bitmap: Bitmap, filterMode: Int)
    private external fun nativeRefinePortraitMatte(
        bitmap: Bitmap,
        alpha512: FloatArray,
        alphaSize: Int,
        studioLighting: Boolean,
    ): FloatArray?

    fun refinePortraitMatte(
        mutableArgb8888: Bitmap,
        alpha512: FloatArray,
        alphaSize: Int = 512,
        studioLighting: Boolean = true,
    ): FloatArray {
        ensureLoaded()
        return nativeRefinePortraitMatte(mutableArgb8888, alpha512, alphaSize, studioLighting)
            ?: floatArrayOf(0.08f, 0.98f, 0.1f, 0.9f, 0.5f)
    }

    fun defaultQuad(): List<NormPoint> = listOf(
        NormPoint(0.08f, 0.08f),
        NormPoint(0.92f, 0.08f),
        NormPoint(0.92f, 0.92f),
        NormPoint(0.08f, 0.92f),
    )

    fun detectQuad(bitmap: Bitmap): QuadDetectionResult {
        ensureLoaded()
        val rgba = ensureArgb8888(bitmap)
        val raw = nativeDetectQuad(rgba)
        if (rgba !== bitmap) rgba.recycle()
        if (raw == null || raw.size < 9) {
            return QuadDetectionResult(defaultQuad(), false)
        }
        return QuadDetectionResult(
            corners = listOf(
                NormPoint(raw[0], raw[1]),
                NormPoint(raw[2], raw[3]),
                NormPoint(raw[4], raw[5]),
                NormPoint(raw[6], raw[7]),
            ),
            confident = raw[8] > 0.5f,
        )
    }

    fun warpAndFilter(
        source: Bitmap,
        corners: List<NormPoint>,
        filter: DocFilter,
        maxSide: Int = 2200,
    ): Bitmap {
        ensureLoaded()
        val rgba = ensureArgb8888(source)
        val quadArr = FloatArray(8) { i ->
            val p = corners[i / 2]
            if (i % 2 == 0) p.x.coerceIn(0f, 1f) else p.y.coerceIn(0f, 1f)
        }
        val dims = nativeComputeOutputSize(rgba.width, rgba.height, quadArr, maxSide)
            ?: intArrayOf(rgba.width, rgba.height)
        val dst = Bitmap.createBitmap(dims[0].coerceAtLeast(64), dims[1].coerceAtLeast(64), Bitmap.Config.ARGB_8888)
        val ok = nativeWarpAndFilter(rgba, dst, quadArr, filter.nativeId)
        if (rgba !== source) rgba.recycle()
        if (!ok) {
            dst.recycle()
            return source.copy(Bitmap.Config.ARGB_8888, true)
        }
        return dst
    }

    fun applyFilterCopy(source: Bitmap, filter: DocFilter): Bitmap {
        ensureLoaded()
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        if (filter != DocFilter.ORIGINAL) {
            nativeApplyFilterInPlace(copy, filter.nativeId)
        }
        return copy
    }

    private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        return if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
}

object BitmapIO {
    fun loadBitmapOriented(context: Context, uri: Uri, maxSide: Int = 2400): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sample = 1
        while (maxOf(origW / sample, origH / sample) > maxSide * 2) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        return applyExifAndScale(decoded, orientation, maxSide)
    }

    fun loadFileOriented(file: File, maxSide: Int = 2400): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sample = 1
        while (maxOf(origW / sample, origH / sample) > maxSide * 2) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return applyExifAndScale(decoded, orientation, maxSide)
    }

    private fun applyExifAndScale(src: Bitmap, orientation: Int, maxSide: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        }
        val longest = maxOf(src.width, src.height)
        if (longest > maxSide) {
            val s = maxSide.toFloat() / longest.toFloat()
            matrix.postScale(s, s)
        }
        if (matrix.isIdentity) return src
        val transformed = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (transformed !== src) src.recycle()
        return transformed
    }

    fun saveJpeg(bitmap: Bitmap, dest: File, quality: Int = 92): File {
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
        }
        return dest
    }

    fun savePng(bitmap: Bitmap, dest: File): File {
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        return dest
    }
}
