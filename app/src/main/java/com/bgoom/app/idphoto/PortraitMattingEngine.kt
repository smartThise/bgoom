package com.bgoom.app.idphoto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.media.FaceDetector
import com.bgoom.app.scanner.NativeDocScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.roundToInt

data class DetectedFaceInfo(
    val eyeMidXRatio: Float, // [0..1] in foreground bitmap
    val eyeMidYRatio: Float,
    val eyeDistRatioW: Float, // eyeDistance / bitmap.width
    val confidence: Float,
)

data class MattingResult(
    val foregroundArgb: Bitmap, // Clean RGBA portrait (guided filter + hole-fill + halo decontaminated)
    val alphaTopRatio: Float,
    val alphaBottomRatio: Float,
    val alphaLeftRatio: Float,
    val alphaRightRatio: Float,
    val alphaCenterXRatio: Float,
    val face: DetectedFaceInfo?,
    val elapsedMs: Long,
)

/**
 * Studio-grade offline ID photo portrait matting powered by HivisionIDPhotos MODNet FP16 ONNX (12.4 MB)
 * + C++17 Guided Filter, interior hole fill (hollow_out_fix), largest-component cleanup,
 * foreground color decontamination (halo removal), and Android FaceDetector eye-centric framing.
 *
 * Verified exact HivisionIDPhotos contract:
 *   - Direct 512x512 resize (NO white letterboxing!)
 *   - BGR channel order normalized via (x / 255.0 - 0.5) / 0.5
 *   - IN "input1" [1, 3, 512, 512], OUT "output1" [1, 1, 512, 512]
 */
class PortraitMattingEngine(private val context: Context) {

    private val mutex = Mutex()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    private fun ensureSession(): Pair<OrtEnvironment, OrtSession> {
        val existingEnv = env
        val existingSession = session
        if (existingEnv != null && existingSession != null) {
            return existingEnv to existingSession
        }
        val modelFile = extractAssetToCache("models/hivision_modnet_fp16.onnx", "hivision_modnet_fp16.onnx")
        val ortEnv = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val ortSession = ortEnv.createSession(modelFile.absolutePath, opts)
        env = ortEnv
        session = ortSession
        return ortEnv to ortSession
    }

    suspend fun extractForeground(
        source: Bitmap,
        studioLighting: Boolean = true,
    ): MattingResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            val start = System.currentTimeMillis()
            val (ortEnv, ortSession) = ensureSession()

            // Work at crisp 1400px max side (fast guided filter & plenty sharp for 300DPI ID photos)
            val work = downscaleMutableArgb(source, 1400)
            val w = work.width
            val h = work.height

            // 1. Detect face eyes using Android's offline FaceDetector BEFORE alpha modification
            val detectedFace = detectBestFace(work)

            // 2. Direct 512x512 resize (exact HivisionIDPhotos preprocessing — NO white sidebars!)
            val side = 512
            val resized512 = Bitmap.createScaledBitmap(work, side, side, true)
            val pixels = IntArray(side * side)
            resized512.getPixels(pixels, 0, side, 0, 0, side, side)
            resized512.recycle()

            // Exact HivisionIDPhotos BGR channel order + (x / 255 - 0.5) / 0.5 normalization
            val planeSize = side * side
            val chw = FloatArray(3 * planeSize)
            for (i in 0 until planeSize) {
                val p = pixels[i]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                chw[i] = (b - 0.5f) / 0.5f
                chw[planeSize + i] = (g - 0.5f) / 0.5f
                chw[2 * planeSize + i] = (r - 0.5f) / 0.5f
            }

            val inputName = ortSession.inputNames.first()
            val alpha512 = OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(chw),
                longArrayOf(1, 3, side.toLong(), side.toLong())
            ).use { tensor ->
                ortSession.run(mapOf(inputName to tensor)).use { outputs ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = outputs[0].value as Array<Array<Array<FloatArray>>>
                    val flat = FloatArray(side * side)
                    for (y in 0 until side) {
                        System.arraycopy(raw[0][0][y], 0, flat, y * side, side)
                    }
                    flat
                }
            }

            // 3. C++17 NDK studio matte refinement:
            //    largest component + hole fill + guided filter hair sharpening + color decontamination
            val bbox = NativeDocScanner.refinePortraitMatte(
                mutableArgb8888 = work,
                alpha512 = alpha512,
                alphaSize = side,
                studioLighting = studioLighting,
            )

            MattingResult(
                foregroundArgb = work,
                alphaTopRatio = bbox[0],
                alphaBottomRatio = bbox[1],
                alphaLeftRatio = bbox[2],
                alphaRightRatio = bbox[3],
                alphaCenterXRatio = bbox[4],
                face = detectedFace,
                elapsedMs = System.currentTimeMillis() - start,
            )
        }
    }

    private fun detectBestFace(src: Bitmap): DetectedFaceInfo? {
        // Android FaceDetector requires RGB_565 and EVEN width
        val maxSide = 640
        val scale = if (maxOf(src.width, src.height) > maxSide) {
            maxSide.toFloat() / maxOf(src.width, src.height).toFloat()
        } else 1f
        val dw = ((src.width * scale).roundToInt() and 0xFFFFFFFE.toInt()).coerceAtLeast(64)
        val dh = (src.height * scale).roundToInt().coerceAtLeast(64)

        val rgb565 = Bitmap.createBitmap(dw, dh, Bitmap.Config.RGB_565)
        Canvas(rgb565).drawBitmap(
            src,
            null,
            android.graphics.Rect(0, 0, dw, dh),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        val faces = arrayOfNulls<FaceDetector.Face>(3)
        val count = runCatching { FaceDetector(dw, dh, 3).findFaces(rgb565, faces) }.getOrDefault(0)
        rgb565.recycle()
        if (count <= 0) return null

        val best = faces.take(count).filterNotNull()
            .maxByOrNull { it.confidence() * it.eyesDistance() } ?: return null
        if (best.eyesDistance() < dw * 0.04f) return null

        val mid = PointF()
        best.getMidPoint(mid)
        return DetectedFaceInfo(
            eyeMidXRatio = (mid.x / dw).coerceIn(0.05f, 0.95f),
            eyeMidYRatio = (mid.y / dh).coerceIn(0.05f, 0.95f),
            eyeDistRatioW = (best.eyesDistance() / dw).coerceIn(0.04f, 0.8f),
            confidence = best.confidence(),
        )
    }

    private fun downscaleMutableArgb(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        return if (longest > maxSide) {
            val s = maxSide.toFloat() / longest.toFloat()
            val nw = (src.width * s).roundToInt().coerceAtLeast(64)
            val nh = (src.height * s).roundToInt().coerceAtLeast(64)
            val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
            if (scaled.isMutable && scaled.config == Bitmap.Config.ARGB_8888) scaled
            else scaled.copy(Bitmap.Config.ARGB_8888, true).also { if (scaled !== src) scaled.recycle() }
        } else {
            src.copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    private fun extractAssetToCache(assetPath: String, cacheName: String): File {
        val outFile = File(context.cacheDir, "onnx_models/$cacheName")
        if (outFile.exists() && outFile.length() > 1024 * 1024) return outFile
        outFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        return outFile
    }
}
