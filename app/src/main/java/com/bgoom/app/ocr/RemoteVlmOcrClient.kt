package com.bgoom.app.ocr

import com.bgoom.app.data.OcrOutputFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible Vision API OCR client.
 * Compatible with Gemini OpenAI endpoint, GPT-4o, Qwen-VL, MiniCPM-V, local LM Studio, and self-hosted OXR / vLLM.
 */
class RemoteVlmOcrClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun recognize(
        imageJpegBytes: ByteArray,
        baseUrl: String,
        apiKey: String,
        model: String,
        format: OcrOutputFormat,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trim().trimEnd('/')
            require(cleanBase.isNotEmpty()) { "请先在设置中填写视觉 API Base URL" }
            require(model.isNotBlank()) { "请先在设置中填写视觉模型名称" }

            val endpoint = if (cleanBase.endsWith("/chat/completions")) {
                cleanBase
            } else {
                "$cleanBase/chat/completions"
            }

            val b64 = Base64.getEncoder().encodeToString(imageJpegBytes)
            val prompt = when (format) {
                OcrOutputFormat.TYPST ->
                    "You are a precision document & handwriting OCR engine. Transcribe EVERY word, table, and mathematical formula faithfully into clean, directly compilable Typst 0.14 markup (use Typst math syntax like \$integral_0^infinity e^(-x^2) dif x\$, headings with =, bullet lists with -). Output ONLY the raw Typst source with no explanation or code fences."

                OcrOutputFormat.MARKDOWN ->
                    "You are a precision document & handwriting OCR engine. Transcribe EVERY word, heading, table, and math formula (using $...$ and $$...$$ LaTeX math) faithfully into clean GitHub-flavored Markdown. Output ONLY the raw Markdown with no explanation or code fences."

                OcrOutputFormat.LATEX ->
                    "You are a precision document & handwriting OCR engine. Transcribe EVERY word, table, and mathematical formula faithfully into clean LaTeX body/document code using amsmath/amssymb. Output ONLY the raw LaTeX with no explanation or code fences."
            }

            val payload = buildJsonObject {
                put("model", JsonPrimitive(model.trim()))
                put("temperature", JsonPrimitive(0.0))
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(prompt))
                            })
                            add(buildJsonObject {
                                put("type", JsonPrimitive("image_url"))
                                put("image_url", buildJsonObject {
                                    put("url", JsonPrimitive("data:image/jpeg;base64,$b64"))
                                })
                            })
                        })
                    })
                })
            }

            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("视觉 API 返回 HTTP ${resp.code}: ${body.take(280)}")
                }
                val root = json.parseToJsonElement(body).jsonObject
                val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalStateException("视觉 API 响应缺少 content 字段")
                OcrFormatConverter.stripCodeFence(content)
            }
        }
    }
}
