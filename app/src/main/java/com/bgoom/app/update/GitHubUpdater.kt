package com.bgoom.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bgoom.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ReleaseUpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val htmlUrl: String,
    val isNewer: Boolean,
    val expectedSha256: String = "",
)

internal data class ParsedApkAsset(
    val name: String,
    val downloadUrl: String,
    val approxSizeBytes: Long = 0L,
    val sha256: String = "",
)

/**
 * Lightweight GitHub Releases update checker, in-app APK downloader, and native installer launcher.
 *
 * Uses GitHub web release redirect + `expanded_assets` HTML as the primary path (zero REST API
 * 60 req/hr IP rate limit!) with optional mirror prefix support and REST API fallback.
 */
object GitHubUpdater {

    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private class NoReleaseException(message: String) : IllegalStateException(message)

    suspend fun checkLatestRelease(
        repo: String,
        currentVersion: String = BuildConfig.VERSION_NAME,
        mirrorPrefix: String = "",
    ): Result<ReleaseUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanRepo = repo.trim().trim('/')
            require(cleanRepo.contains('/')) { "GitHub 仓库格式应为 owner/repo" }
            val prefix = normalizeMirrorPrefix(mirrorPrefix)

            val webBases = buildList {
                if (prefix.isNotEmpty()) add("${prefix}https://github.com")
                add("https://github.com")
            }

            var lastError: Throwable? = null
            for (webBase in webBases) {
                try {
                    return@runCatching checkViaWebRelease(
                        webBase = webBase,
                        cleanRepo = cleanRepo,
                        currentVersion = currentVersion,
                    )
                } catch (e: NoReleaseException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                }
            }

            // Secondary fallback: GitHub REST API (only reached if web scraping failed)
            try {
                return@runCatching checkViaRestApi(
                    cleanRepo = cleanRepo,
                    currentVersion = currentVersion,
                )
            } catch (e: NoReleaseException) {
                throw e
            } catch (apiErr: Throwable) {
                throw IllegalStateException(formatCheckError(lastError ?: apiErr), apiErr)
            }
        }
    }

    private fun checkViaWebRelease(
        webBase: String,
        cleanRepo: String,
        currentVersion: String,
    ): ReleaseUpdateInfo {
        val latestUrl = "$webBase/$cleanRepo/releases/latest"
        val req = Request.Builder()
            .url(latestUrl)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) BGOOM/$currentVersion")
            .build()

        val (finalUrl, html) = checkClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 404) {
                throw NoReleaseException("仓库 $cleanRepo 尚未发布正式 Release")
            }
            if (!resp.isSuccessful) {
                throw IllegalStateException("GitHub 页面响应异常 (HTTP ${resp.code})")
            }
            resp.request.url.toString() to body
        }

        val tag = extractTagFromUrl(finalUrl)
            ?: extractTagFromHtml(html, cleanRepo)
            ?: if (finalUrl.trimEnd('/').endsWith("/releases", ignoreCase = true)) {
                throw NoReleaseException("仓库 $cleanRepo 尚未发布正式 Release")
            } else {
                throw IllegalStateException("未能解析最新版本号")
            }

        val version = tag.removePrefix("v").removePrefix("V")
        val notes = extractReleaseNotesFromHtml(html)
        val encodedTag = URLEncoder.encode(tag, "UTF-8")

        val asset = fetchExpandedAsset(webBase, cleanRepo, encodedTag, tag, currentVersion)
            ?: if (webBase != "https://github.com") {
                fetchExpandedAsset("https://github.com", cleanRepo, encodedTag, tag, currentVersion)
            } else {
                null
            }

        val apkName = asset?.name ?: "BGOOM-$tag-release.apk"
        val apkDownloadUrl = asset?.downloadUrl
            ?: "https://github.com/$cleanRepo/releases/download/$tag/$apkName"

        return ReleaseUpdateInfo(
            tagName = tag,
            versionName = version,
            releaseNotes = notes,
            apkName = apkName,
            apkDownloadUrl = apkDownloadUrl,
            apkSizeBytes = asset?.approxSizeBytes ?: 0L,
            htmlUrl = "https://github.com/$cleanRepo/releases/tag/$tag",
            isNewer = isNewerVersion(version, currentVersion),
            expectedSha256 = asset?.sha256.orEmpty(),
        )
    }

    private fun fetchExpandedAsset(
        webBase: String,
        cleanRepo: String,
        encodedTag: String,
        tag: String,
        currentVersion: String,
    ): ParsedApkAsset? {
        val assetsUrl = "$webBase/$cleanRepo/releases/expanded_assets/$encodedTag"
        val req = Request.Builder()
            .url(assetsUrl)
            .header("Accept", "text/html")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) BGOOM/$currentVersion")
            .build()
        return runCatching {
            checkClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                extractApkAssetFromExpandedAssetsHtml(body, cleanRepo, tag)
            }
        }.getOrNull()
    }

    private fun checkViaRestApi(
        cleanRepo: String,
        currentVersion: String,
    ): ReleaseUpdateInfo {
        val url = "https://api.github.com/repos/$cleanRepo/releases/latest"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BGOOM-Android/$currentVersion")
            .build()

        return checkClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 404) {
                throw NoReleaseException("仓库 $cleanRepo 尚未发布正式 Release")
            }
            if (resp.code == 403 && resp.header("x-ratelimit-remaining") == "0") {
                throw IllegalStateException("GitHub API 匿名限流 (HTTP 403)")
            }
            if (!resp.isSuccessful) {
                throw IllegalStateException("检查更新失败 (HTTP ${resp.code})")
            }
            val root = json.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val version = tag.removePrefix("v").removePrefix("V")
            val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val assets = root["assets"]?.jsonArray.orEmpty()
            val apkAsset = assets.firstOrNull { el ->
                val name = el.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                name.endsWith(".apk", ignoreCase = true) && !name.contains("debug", ignoreCase = true)
            } ?: assets.firstOrNull { el ->
                el.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty().endsWith(".apk", ignoreCase = true)
            }

            val apkName = apkAsset?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val apkUrl = apkAsset?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.contentOrNull.orEmpty()
            val apkSize = apkAsset?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull ?: 0L
            val digest = apkAsset?.jsonObject?.get("digest")?.jsonPrimitive?.contentOrNull
                .orEmpty()
                .removePrefix("sha256:")

            ReleaseUpdateInfo(
                tagName = tag,
                versionName = version,
                releaseNotes = notes,
                apkName = apkName,
                apkDownloadUrl = apkUrl,
                apkSizeBytes = apkSize,
                htmlUrl = htmlUrl,
                isNewer = isNewerVersion(version, currentVersion),
                expectedSha256 = digest,
            )
        }
    }

    suspend fun downloadApk(
        context: Context,
        info: ReleaseUpdateInfo,
        mirrorPrefix: String = "",
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(info.apkDownloadUrl.isNotBlank()) { "该 Release 未附带可安装的 APK 文件" }
            val prefix = normalizeMirrorPrefix(mirrorPrefix)
            val urls = buildList {
                if (prefix.isNotEmpty()) add("$prefix${info.apkDownloadUrl}")
                add(info.apkDownloadUrl)
            }

            val outDir = File(context.cacheDir, "updates").apply { mkdirs() }
            outDir.listFiles()?.forEach { runCatching { it.delete() } }
            val dest = File(outDir, info.apkName.ifBlank { "bgoom_${info.versionName}.apk" })

            var lastErr: Throwable? = null
            for (downloadUrl in urls) {
                try {
                    val req = Request.Builder()
                        .url(downloadUrl)
                        .header("User-Agent", "BGOOM-Android/${BuildConfig.VERSION_NAME}")
                        .build()

                    downloadClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("APK 下载失败 (HTTP ${resp.code})")
                        }
                        val body = resp.body ?: throw IllegalStateException("响应体为空")
                        val total = body.contentLength().takeIf { it > 0 } ?: info.apkSizeBytes.coerceAtLeast(1L)
                        val sha256 = MessageDigest.getInstance("SHA-256")
                        body.byteStream().use { input ->
                            FileOutputStream(dest).use { output ->
                                val buf = ByteArray(64 * 1024)
                                var copied = 0L
                                while (true) {
                                    val read = input.read(buf)
                                    if (read <= 0) break
                                    output.write(buf, 0, read)
                                    sha256.update(buf, 0, read)
                                    copied += read
                                    onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                                }
                            }
                        }

                        if (info.expectedSha256.length == 64) {
                            val actualHex = sha256.digest().joinToString("") { "%02x".format(it) }
                            if (!actualHex.equals(info.expectedSha256, ignoreCase = true)) {
                                runCatching { dest.delete() }
                                throw IllegalStateException("安装包完整性校验失败（SHA-256 不匹配），请重试")
                            }
                        }
                    }
                    onProgress(1f)
                    return@runCatching dest
                } catch (e: Throwable) {
                    runCatching { dest.delete() }
                    lastErr = e
                }
            }
            throw lastErr ?: IllegalStateException("APK 下载失败")
        }
    }

    /**
     * Checks install permission and launches Android's native Package Installer directly for the downloaded APK.
     * Returns true if installer was launched, false if user was directed to grant "Allow from this source".
     */
    fun launchInstallerOrRequestPermission(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return false
        }

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        val rParts = parseSemver(remote)
        val lParts = parseSemver(local)
        for (i in 0 until maxOf(rParts.size, lParts.size)) {
            val rv = rParts.getOrElse(i) { 0 }
            val lv = lParts.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    internal fun normalizeMirrorPrefix(mirrorPrefix: String): String {
        val trimmed = mirrorPrefix.trim()
        return if (trimmed.isNotEmpty() && !trimmed.endsWith("/")) "$trimmed/" else trimmed
    }

    internal fun extractTagFromUrl(url: String): String? {
        val match = Regex("/releases/tag/([^/?#]+)", RegexOption.IGNORE_CASE).find(url) ?: return null
        val raw = match.groupValues[1].trim()
        if (raw.isEmpty()) return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    internal fun extractTagFromHtml(html: String, cleanRepo: String): String? {
        val repoPattern = Regex.escape(cleanRepo)
        val match = Regex(
            """/$repoPattern/releases/tag/([^"/?#&<\s]+)""",
            RegexOption.IGNORE_CASE,
        ).find(html) ?: return null
        val raw = match.groupValues[1].trim()
        if (raw.isEmpty()) return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    internal fun extractReleaseNotesFromHtml(html: String): String {
        val openMatch = Regex(
            """<div\b[^>]*class="[^"]*\bmarkdown-body\b[^"]*"[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(html) ?: return ""

        val contentStart = openMatch.range.last + 1
        val divTagRegex = Regex("""<(/?)div\b[^>]*>""", RegexOption.IGNORE_CASE)
        var depth = 1
        var contentEnd = html.length
        for (m in divTagRegex.findAll(html, contentStart)) {
            val isClosing = m.groupValues[1] == "/"
            depth += if (isClosing) -1 else 1
            if (depth == 0) {
                contentEnd = m.range.first
                break
            }
        }
        return stripHtmlToPlainText(html.substring(contentStart, contentEnd))
    }

    internal fun extractApkAssetFromExpandedAssetsHtml(
        html: String,
        cleanRepo: String,
        tag: String,
    ): ParsedApkAsset? {
        val hrefRegex = Regex(
            """href="((?:https?://github\.com)?/[^"]+/releases/download/[^"]+\.apk)"""",
            RegexOption.IGNORE_CASE,
        )
        val matches = hrefRegex.findAll(html).toList()
        if (matches.isEmpty()) return null

        val chosen = matches.firstOrNull { m ->
            !m.groupValues[1].contains("debug", ignoreCase = true)
        } ?: matches.first()

        val rawHref = chosen.groupValues[1]
        val downloadUrl = if (rawHref.startsWith("http", ignoreCase = true)) {
            rawHref
        } else {
            "https://github.com$rawHref"
        }
        val rawName = rawHref.substringAfterLast('/')
        val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)

        // Inspect the surrounding <li> item for SHA-256 digest and human-readable file size
        val liEnd = html.indexOf("</li>", chosen.range.last, ignoreCase = true)
            .takeIf { it > chosen.range.last }
            ?: minOf(html.length, chosen.range.last + 2500)
        val itemSlice = html.substring(chosen.range.first, liEnd)

        val sha256 = Regex("""sha256:([0-9a-fA-F]{64})""").find(itemSlice)?.groupValues?.get(1).orEmpty()
        val approxSize = parseHumanSizeBytes(itemSlice)

        return ParsedApkAsset(
            name = name.ifBlank { "BGOOM-$tag-release.apk" },
            downloadUrl = downloadUrl,
            approxSizeBytes = approxSize,
            sha256 = sha256,
        )
    }

    internal fun parseHumanSizeBytes(slice: String): Long {
        val m = Regex(""">\s*(\d+(?:\.\d+)?)\s*(Bytes|KB|MB|GB)\s*<""", RegexOption.IGNORE_CASE)
            .find(slice) ?: return 0L
        val value = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val mult = when (m.groupValues[2].uppercase()) {
            "GB" -> 1024.0 * 1024.0 * 1024.0
            "MB" -> 1024.0 * 1024.0
            "KB" -> 1024.0
            else -> 1.0
        }
        return (value * mult).toLong()
    }

    internal fun stripHtmlToPlainText(fragment: String): String {
        val formatted = fragment
            .replace(Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE), "\n• ")
            .replace(Regex("""<(?:br|p|h[1-6]|div|tr)\b[^>]*>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</(?:p|h[1-6]|div|ul|ol|li|tr)>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")

        return formatted.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun formatCheckError(err: Throwable): String {
        val msg = err.message.orEmpty()
        return when {
            msg.contains("403") -> "检查更新失败 (HTTP 403 限流)，请稍后重试或切换网络"
            err is IOException -> "无法连接 GitHub（${err.javaClass.simpleName}），国内网络请开启代理或在下方填写加速前缀"
            msg.isNotBlank() -> msg
            else -> "检查更新失败，请检查网络连接"
        }
    }

    private fun parseSemver(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
}
