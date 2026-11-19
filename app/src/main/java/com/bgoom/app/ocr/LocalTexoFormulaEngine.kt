package com.bgoom.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.roundToInt

data class FormulaOcrResult(
    val rawLatex: String,
    val tokenCount: Int,
    val elapsedMs: Long,
)

/**
 * Offline 20M-parameter Mathematical Formula OCR powered by Texo (alephpi/FormulaNet, INT8 ONNX ~19.5 MB).
 * Verified end-to-end tensor contract:
 *   Encoder: pixel_values [1, 3, 384, 384] float32 -> last_hidden_state [1, S, 2048]
 *   Decoder: input_ids [1, L] int64 + encoder_hidden_states [1, S, 2048] -> logits [1, L, 687]
 *   BOS=0, PAD=1, EOS=2, WordLevel vocab (687 LaTeX tokens)
 */
class LocalTexoFormulaEngine(private val context: Context) {

    private val mutex = Mutex()
    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var idToToken: Map<Int, String>? = null

    private fun ensureLoaded() {
        if (encoder != null && decoder != null && idToToken != null) return
        val ortEnv = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val encFile = extractAsset("models/texo_encoder_int8.onnx", "texo_encoder_int8.onnx")
        val decFile = extractAsset("models/texo_decoder_int8.onnx", "texo_decoder_int8.onnx")
        encoder = ortEnv.createSession(encFile.absolutePath, opts)
        decoder = ortEnv.createSession(decFile.absolutePath, opts)
        env = ortEnv

        val tokText = context.assets.open("models/texo_tokenizer.json").bufferedReader().readText()
        val root = Json.parseToJsonElement(tokText).jsonObject
        val vocabObj = root["model"]!!.jsonObject["vocab"]!!.jsonObject
        idToToken = vocabObj.entries.associate { (tok, idEl) -> idEl.jsonPrimitive.int to tok }
    }

    suspend fun recognizeFormula(bitmap: Bitmap, maxTokens: Int = 200): FormulaOcrResult =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val start = System.currentTimeMillis()
                ensureLoaded()
                val ortEnv = env!!
                val enc = encoder!!
                val dec = decoder!!
                val vocab = idToToken!!

                // 1. Aspect-preserving white letterbox into 384x384 RGB normalized (x/255 - 0.5) / 0.5
                val side = 384
                val scale = minOf(side.toFloat() / bitmap.width.coerceAtLeast(1), side.toFloat() / bitmap.height.coerceAtLeast(1))
                val nw = (bitmap.width * scale).roundToInt().coerceIn(1, side)
                val nh = (bitmap.height * scale).roundToInt().coerceIn(1, side)
                val padX = (side - nw) / 2
                val padY = (side - nh) / 2

                val canvasBmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(canvasBmp)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(padX, padY, padX + nw, padY + nh),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )

                val pixels = IntArray(side * side)
                canvasBmp.getPixels(pixels, 0, side, 0, 0, side, side)
                canvasBmp.recycle()

                val plane = side * side
                val chw = FloatArray(3 * plane)
                for (i in 0 until plane) {
                    val p = pixels[i]
                    val r = ((p shr 16) and 0xFF) / 255f
                    val g = ((p shr 8) and 0xFF) / 255f
                    val b = (p and 0xFF) / 255f
                    chw[i] = (r - 0.5f) / 0.5f
                    chw[plane + i] = (g - 0.5f) / 0.5f
                    chw[2 * plane + i] = (b - 0.5f) / 0.5f
                }

                // 2. Run vision encoder once
                val encShape: LongArray
                val encHiddenFlat: FloatArray
                OnnxTensor.createTensor(
                    ortEnv,
                    FloatBuffer.wrap(chw),
                    longArrayOf(1, 3, side.toLong(), side.toLong())
                ).use { pixelTensor ->
                    enc.run(mapOf("pixel_values" to pixelTensor)).use { encOut ->
                        val outTensor = encOut[0] as OnnxTensor
                        encShape = outTensor.info.shape
                        val fb = outTensor.floatBuffer
                        encHiddenFlat = FloatArray(fb.remaining())
                        fb.get(encHiddenFlat)
                    }
                }

                // 3. Autoregressive greedy decoding (2-layer lightweight decoder, <700 vocab)
                val generated = ArrayList<Long>(64)
                generated.add(BOS_ID)

                OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(encHiddenFlat), encShape).use { encHiddenTensor ->
                    for (step in 0 until maxTokens) {
                        val idsArr = LongArray(generated.size) { generated[it] }
                        val nextId = OnnxTensor.createTensor(
                            ortEnv,
                            LongBuffer.wrap(idsArr),
                            longArrayOf(1, idsArr.size.toLong())
                        ).use { idsTensor ->
                            dec.run(
                                mapOf(
                                    "input_ids" to idsTensor,
                                    "encoder_hidden_states" to encHiddenTensor,
                                )
                            ).use { decOut ->
                                val logitsTensor = decOut[0] as OnnxTensor
                                val shape = logitsTensor.info.shape // [1, L, V]
                                val seqLen = shape[1].toInt()
                                val vocabSize = shape[2].toInt()
                                val fb = logitsTensor.floatBuffer
                                val lastOffset = (seqLen - 1) * vocabSize
                                var bestIdx = 0
                                var bestScore = Float.NEGATIVE_INFINITY
                                for (v in 0 until vocabSize) {
                                    val s = fb.get(lastOffset + v)
                                    if (s > bestScore) {
                                        bestScore = s
                                        bestIdx = v
                                    }
                                }
                                bestIdx.toLong()
                            }
                        }

                        if (nextId == EOS_ID || nextId == PAD_ID) break
                        generated.add(nextId)

                        // Guard against degenerate repetition loops on non-formula noise images
                        if (generated.size >= 10) {
                            val tail = generated.subList(generated.size - 8, generated.size)
                            if (tail.distinct().size <= 1) break
                        }
                    }
                }

                val tokens = generated.drop(1).mapNotNull { vocab[it.toInt()] }
                val latex = detokenizeLatex(tokens)
                FormulaOcrResult(
                    rawLatex = latex,
                    tokenCount = tokens.size,
                    elapsedMs = System.currentTimeMillis() - start,
                )
            }
        }

    private fun extractAsset(assetPath: String, cacheName: String): File {
        val outFile = File(context.cacheDir, "onnx_models/$cacheName")
        if (outFile.exists() && outFile.length() > 1024 * 500) return outFile
        outFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        return outFile
    }

    companion object {
        private const val BOS_ID = 0L
        private const val PAD_ID = 1L
        private const val EOS_ID = 2L

        /**
         * Joins Texo WordLevel LaTeX tokens into clean, standard LaTeX while fixing known Texo aliases (e.g. \infin -> \infty).
         */
        fun detokenizeLatex(tokens: List<String>): String {
            if (tokens.isEmpty()) return ""
            val normalized = tokens.map { tok ->
                when (tok) {
                    "\\infin" -> "\\infty"
                    else -> tok
                }
            }
            val sb = StringBuilder()
            for (tok in normalized) {
                if (tok == "<s>" || tok == "</s>" || tok == "<pad>" || tok == "<unk>") continue
                if (sb.isNotEmpty()) {
                    val prev = sb.last()
                    val first = tok.first()
                    val prevIsCommandOrAlpha = prev.isLetter()
                    val currIsAlphaOrDigit = first.isLetterOrDigit()
                    if (prevIsCommandOrAlpha && currIsAlphaOrDigit) {
                        sb.append(' ')
                    } else if (tok == "~") {
                        sb.append(' ')
                    }
                }
                if (tok != "~") {
                    sb.append(tok)
                }
            }
            return sb.toString()
                .replace(Regex("\\s+"), " ")
                .replace(" _ ", "_")
                .replace(" ^ ", "^")
                .replace("{ ", "{")
                .replace(" }", "}")
                .trim()
        }
    }
}
