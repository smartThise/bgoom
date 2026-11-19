package com.bgoom.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LocalOcrResult(
    val lines: List<OcrLineBox>,
    val elapsedMs: Long,
)

/**
 * Offline ultra-fast PP-OCRv5 Mobile engine (ONNX Runtime Mobile).
 * Verified models bundled in assets/models/:
 * - ppocr_v5_det.onnx (4.7 MB): DBNet text detector (x -> fetch_name_0)
 * - ppocr_v5_rec.onnx (15.8 MB): SVTR/CRNN recognizer (x -> fetch_name_0, [1, T, 18385])
 * - ppocr_v5_dict.txt (18,383 chars, strong Chinese/English messy handwriting support)
 */
class LocalPpOcrEngine(private val context: Context) {

    private val mutex = Mutex()
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var dictionary: List<String>? = null

    private fun ensureLoaded() {
        if (detSession != null && recSession != null && dictionary != null) return
        val ortEnv = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val detFile = extractAsset("models/ppocr_v5_det.onnx", "ppocr_v5_det.onnx")
        val recFile = extractAsset("models/ppocr_v5_rec.onnx", "ppocr_v5_rec.onnx")
        detSession = ortEnv.createSession(detFile.absolutePath, opts)
        recSession = ortEnv.createSession(recFile.absolutePath, opts)
        env = ortEnv

        val keys = context.assets.open("models/ppocr_v5_dict.txt").bufferedReader().useLines { seq ->
            seq.map { it.trimEnd('\r', '\n') }.toList()
        }
        // Index 0 = CTC blank, 1..keys.size = dict chars, last = space
        dictionary = buildList(keys.size + 2) {
            add("<blank>")
            addAll(keys)
            add(" ")
        }
    }

    suspend fun recognize(bitmap: Bitmap): LocalOcrResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            val start = System.currentTimeMillis()
            ensureLoaded()
            val ortEnv = env!!
            val det = detSession!!
            val rec = recSession!!
            val dict = dictionary!!

            val boxes = runDetection(ortEnv, det, bitmap)
            val lines = mutableListOf<OcrLineBox>()

            for (box in boxes) {
                val x0 = box.left.roundToInt().coerceIn(0, bitmap.width - 2)
                val y0 = box.top.roundToInt().coerceIn(0, bitmap.height - 2)
                val x1 = box.right.roundToInt().coerceIn(x0 + 2, bitmap.width)
                val y1 = box.bottom.roundToInt().coerceIn(y0 + 2, bitmap.height)
                val cropW = x1 - x0
                val cropH = y1 - y0
                if (cropW < 6 || cropH < 6) continue

                val crop = Bitmap.createBitmap(bitmap, x0, y0, cropW, cropH)
                val (text, conf) = runRecognition(ortEnv, rec, dict, crop)
                crop.recycle()
                if (text.isNotBlank() && conf >= 0.35f) {
                    lines.add(
                        OcrLineBox(
                            text = text.trim(),
                            confidence = conf,
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom,
                        )
                    )
                }
            }

            // Fallback: if detector found nothing (e.g. tight single-line crop), run recognizer directly
            if (lines.isEmpty() && bitmap.width >= 16 && bitmap.height >= 12) {
                val (text, conf) = runRecognition(ortEnv, rec, dict, bitmap)
                if (text.isNotBlank()) {
                    lines.add(
                        OcrLineBox(
                            text = text.trim(),
                            confidence = conf,
                            left = 0f,
                            top = 0f,
                            right = bitmap.width.toFloat(),
                            bottom = bitmap.height.toFloat(),
                        )
                    )
                }
            }

            LocalOcrResult(
                lines = OcrFormatConverter.sortReadingOrder(lines),
                elapsedMs = System.currentTimeMillis() - start,
            )
        }
    }

    private data class RectBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun runDetection(
        ortEnv: OrtEnvironment,
        det: OrtSession,
        src: Bitmap,
    ): List<RectBox> {
        val maxSide = 960
        val scale = if (max(src.width, src.height) > maxSide) {
            maxSide.toFloat() / max(src.width, src.height).toFloat()
        } else {
            1.0f
        }
        val resizedW = max(32, ((src.width * scale) / 32f).roundToInt() * 32)
        val resizedH = max(32, ((src.height * scale) / 32f).roundToInt() * 32)
        val resized = Bitmap.createScaledBitmap(src, resizedW, resizedH, true)

        val pixels = IntArray(resizedW * resizedH)
        resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, resizedH)
        resized.recycle()

        // PaddleOCR DBNet normalization: BGR order, ImageNet mean/std
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val plane = resizedW * resizedH
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            chw[i] = (b - mean[0]) / std[0]
            chw[plane + i] = (g - mean[1]) / std[1]
            chw[2 * plane + i] = (r - mean[2]) / std[2]
        }

        val inputName = det.inputNames.first()
        val probMap: Array<FloatArray> = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(chw),
            longArrayOf(1, 3, resizedH.toLong(), resizedW.toLong())
        ).use { tensor ->
            det.run(mapOf(inputName to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val arr = out[0].value as Array<Array<Array<FloatArray>>>
                arr[0][0]
            }
        }

        return extractBoxesFromProbMap(
            probMap = probMap,
            mapW = resizedW,
            mapH = resizedH,
            srcW = src.width,
            srcH = src.height,
        )
    }

    private fun extractBoxesFromProbMap(
        probMap: Array<FloatArray>,
        mapW: Int,
        mapH: Int,
        srcW: Int,
        srcH: Int,
    ): List<RectBox> {
        val thresh = 0.30f
        val boxScoreThresh = 0.50f
        val visited = ByteArray(mapW * mapH)
        val queue = IntArray(mapW * mapH)
        val boxes = mutableListOf<RectBox>()

        val sx = srcW.toFloat() / mapW.toFloat()
        val sy = srcH.toFloat() / mapH.toFloat()

        for (y in 0 until mapH) {
            val row = probMap[y]
            for (x in 0 until mapW) {
                val idx = y * mapW + x
                if (visited[idx].toInt() != 0 || row[x] < thresh) continue

                var head = 0
                var tail = 0
                visited[idx] = 1
                queue[tail++] = idx

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var scoreSum = 0f
                var count = 0

                while (head < tail) {
                    val cur = queue[head++]
                    val cx = cur % mapW
                    val cy = cur / mapW
                    scoreSum += probMap[cy][cx]
                    count++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    // 4-connected BFS
                    if (cx > 0) {
                        val n = cur - 1
                        if (visited[n].toInt() == 0 && probMap[cy][cx - 1] >= thresh) {
                            visited[n] = 1; queue[tail++] = n
                        }
                    }
                    if (cx + 1 < mapW) {
                        val n = cur + 1
                        if (visited[n].toInt() == 0 && probMap[cy][cx + 1] >= thresh) {
                            visited[n] = 1; queue[tail++] = n
                        }
                    }
                    if (cy > 0) {
                        val n = cur - mapW
                        if (visited[n].toInt() == 0 && probMap[cy - 1][cx] >= thresh) {
                            visited[n] = 1; queue[tail++] = n
                        }
                    }
                    if (cy + 1 < mapH) {
                        val n = cur + mapW
                        if (visited[n].toInt() == 0 && probMap[cy + 1][cx] >= thresh) {
                            visited[n] = 1; queue[tail++] = n
                        }
                    }
                }

                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                val avgScore = scoreSum / max(1, count)
                if (count < 12 || bw < 4 || bh < 4 || avgScore < boxScoreThresh) continue

                // DBNet unclip expansion: pad proportionally to box height so ascenders/descenders aren't clipped
                val padY = max(2f, bh * 0.36f)
                val padX = max(3f, bh * 0.32f)
                val left = ((minX - padX) * sx).coerceIn(0f, srcW.toFloat())
                val top = ((minY - padY) * sy).coerceIn(0f, srcH.toFloat())
                val right = ((maxX + 1 + padX) * sx).coerceIn(0f, srcW.toFloat())
                val bottom = ((maxY + 1 + padY) * sy).coerceIn(0f, srcH.toFloat())

                if (right - left >= 8f && bottom - top >= 8f) {
                    boxes.add(RectBox(left, top, right, bottom))
                }
            }
        }
        return boxes
    }

    private fun runRecognition(
        ortEnv: OrtEnvironment,
        rec: OrtSession,
        dict: List<String>,
        crop: Bitmap,
    ): Pair<String, Float> {
        val targetH = 48
        val ratio = crop.width.toFloat() / crop.height.coerceAtLeast(1).toFloat()
        val targetW = ceil(targetH * ratio).toInt().coerceIn(32, 1280)
        val resized = Bitmap.createScaledBitmap(crop, targetW, targetH, true)

        val pixels = IntArray(targetW * targetH)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        resized.recycle()

        // Verified PP-OCRv5 rec normalization: BGR, (x / 255.0 - 0.5) / 0.5
        val plane = targetW * targetH
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            chw[i] = (b - 0.5f) / 0.5f
            chw[plane + i] = (g - 0.5f) / 0.5f
            chw[2 * plane + i] = (r - 0.5f) / 0.5f
        }

        val inputName = rec.inputNames.first()
        val logits: Array<FloatArray> = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(chw),
            longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        ).use { tensor ->
            rec.run(mapOf(inputName to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val arr = out[0].value as Array<Array<FloatArray>>
                arr[0]
            }
        }

        return decodeCtc(logits, dict)
    }

    private fun extractAsset(assetPath: String, cacheName: String): File {
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

    companion object {
        fun decodeCtc(probs: Array<FloatArray>, dict: List<String>): Pair<String, Float> {
            val sb = StringBuilder()
            var prevIdx = -1
            var confSum = 0f
            var charCount = 0
            for (step in probs) {
                var bestIdx = 0
                var bestVal = step[0]
                for (i in 1 until step.size) {
                    if (step[i] > bestVal) {
                        bestVal = step[i]
                        bestIdx = i
                    }
                }
                if (bestIdx != 0 && bestIdx != prevIdx && bestIdx < dict.size) {
                    sb.append(dict[bestIdx])
                    confSum += bestVal
                    charCount++
                }
                prevIdx = bestIdx
            }
            val meanConf = if (charCount > 0) confSum / charCount else 0f
            return sb.toString() to meanConf
        }
    }
}
