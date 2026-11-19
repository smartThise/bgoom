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
)

/**
 * Lightweight GitHub Releases update checker, in-app APK downloader, and native installer launcher.
 */
object GitHubUpdater {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkLatestRelease(
        repo: String,
        currentVersion: String = BuildConfig.VERSION_NAME,
    ): Result<ReleaseUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanRepo = repo.trim().trim('/')
            require(cleanRepo.contains('/')) { "GitHub 仓库格式应为 owner/repo" }
            val url = "https://api.github.com/repos/$cleanRepo/releases/latest"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BGOOM-Android/$currentVersion")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 404) {
                    throw IllegalStateException("仓库 $cleanRepo 尚未发布正式 Release")
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

                ReleaseUpdateInfo(
                    tagName = tag,
                    versionName = version,
                    releaseNotes = notes,
                    apkName = apkName,
                    apkDownloadUrl = apkUrl,
                    apkSizeBytes = apkSize,
                    htmlUrl = htmlUrl,
                    isNewer = isNewerVersion(version, currentVersion),
                )
            }
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
            val prefix = mirrorPrefix.trim().let { p ->
                if (p.isNotEmpty() && !p.endsWith("/")) "$p/" else p
            }
            val downloadUrl = if (prefix.isNotEmpty()) "$prefix${info.apkDownloadUrl}" else info.apkDownloadUrl

            val outDir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Clean previous downloaded update APKs
            outDir.listFiles()?.forEach { runCatching { it.delete() } }
            val dest = File(outDir, info.apkName.ifBlank { "bgoom_${info.versionName}.apk" })

            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "BGOOM-Android/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("APK 下载失败 (HTTP ${resp.code})")
                }
                val body = resp.body ?: throw IllegalStateException("响应体为空")
                val total = body.contentLength().takeIf { it > 0 } ?: info.apkSizeBytes.coerceAtLeast(1L)
                body.byteStream().use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            copied += read
                            onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress(1f)
            dest
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

    private fun parseSemver(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
}
