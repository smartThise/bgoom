package com.bgoom.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedPublicFile(
    val uri: Uri,
    val displayLocation: String,
    val fileName: String,
    val mimeType: String,
)

object AndroidFileHelper {

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun readClipboard(context: Context): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
    }

    fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
            ?: "file_${System.currentTimeMillis()}"
    }

    fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "md", "markdown", "typ", "tex", "txt", "log", "json", "yaml", "yml", "toml", "csv" -> "text/plain"
            "html", "htm" -> "text/html"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }

    fun copyUriToCacheFile(context: Context, uri: Uri): File? {
        val name = queryDisplayName(context, uri)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dir = File(context.cacheDir, "shared_in").apply { mkdirs() }
        val dst = File(dir, name)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return null
            dst
        }.getOrNull()
    }

    fun readUriAsText(context: Context, uri: Uri, maxBytes: Int = 256 * 1024): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readNBytes(maxBytes).toString(Charsets.UTF_8)
        }
    }.getOrNull()

    /**
     * Saves any file directly into the public system Downloads/BGOOM folder so Honor File Manager,
     * WeChat, WPS, and Gallery immediately discover it, and returns an directly openable content:// URI.
     */
    fun saveFileToPublicDownloads(
        context: Context,
        sourceFile: File,
        displayName: String = sourceFile.name,
    ): Result<SavedPublicFile> = runCatching {
        val cleanName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "bgoom_file" }
        val mime = guessMimeType(cleanName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BGOOM")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入系统下载目录")
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { inp -> inp.copyTo(out, bufferSize = 64 * 1024) }
            } ?: throw IllegalStateException("无法打开下载输出流")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            SavedPublicFile(
                uri = uri,
                displayLocation = "下载 / BGOOM / $cleanName",
                fileName = cleanName,
                mimeType = mime,
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BGOOM")
            dir.mkdirs()
            val dst = File(dir, cleanName)
            sourceFile.copyTo(dst, overwrite = true)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dst)
            SavedPublicFile(
                uri = uri,
                displayLocation = "下载 / BGOOM / $cleanName",
                fileName = cleanName,
                mimeType = mime,
            )
        }
    }

    /**
     * Saves an image directly into public Pictures/BGOOM so it appears in Honor Gallery immediately.
     */
    fun saveBitmapToGallery(context: Context, bitmap: Bitmap, baseName: String): Result<SavedPublicFile> = runCatching {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${baseName}_$ts.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BGOOM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法写入系统相册")
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
        } ?: throw IllegalStateException("无法打开相册输出流")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        SavedPublicFile(
            uri = uri,
            displayLocation = "相册 / BGOOM / $fileName",
            fileName = fileName,
            mimeType = "image/jpeg",
        )
    }

    fun savePdfToDownloads(context: Context, pdfFile: File, displayName: String = pdfFile.name): Result<SavedPublicFile> =
        saveFileToPublicDownloads(context, pdfFile, displayName)

    /**
     * Directly opens a saved/downloaded file with the system's native viewer (PDF / Image / Office / Text).
     */
    fun openFile(context: Context, uriString: String, fallbackLocalPath: String, mimeType: String): Result<Unit> =
        runCatching {
            val uri = resolveViewableUri(context, uriString, fallbackLocalPath)
                ?: throw IllegalStateException("找不到本地文件（可能已被清理）")
            val resolvedMime = if (mimeType.isBlank() || mimeType == "*/*") {
                guessMimeType(fallbackLocalPath.ifBlank { queryDisplayName(context, uri) })
            } else mimeType

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(viewIntent)
            } catch (_: Exception) {
                val chooser = Intent.createChooser(viewIntent, "选择打开方式").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        }

    fun shareUriOrFile(context: Context, uriString: String, fallbackLocalPath: String, mimeType: String): Result<Unit> =
        runCatching {
            val uri = resolveViewableUri(context, uriString, fallbackLocalPath)
                ?: throw IllegalStateException("找不到本地文件")
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "分享文件").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }

    fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "分享文件").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun resolveViewableUri(context: Context, uriString: String, fallbackLocalPath: String): Uri? {
        if (uriString.isNotBlank()) {
            val parsed = runCatching { Uri.parse(uriString) }.getOrNull()
            if (parsed != null) return parsed
        }
        if (fallbackLocalPath.isNotBlank()) {
            val file = File(fallbackLocalPath)
            if (file.exists()) {
                return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        }
        return null
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun formatRelativeTime(epochMs: Long): String {
        val diff = (System.currentTimeMillis() - epochMs).coerceAtLeast(0L) / 1000L
        return when {
            diff < 60 -> "刚刚"
            diff < 3600 -> "${diff / 60} 分钟前"
            diff < 86400 -> "${diff / 3600} 小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
        }
    }
}
