package com.bgoom.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class DevicePlatform(val label: String) {
    MACOS("macOS"),
    LINUX("Linux"),
    WINDOWS_WSL("Windows / WSL"),
    NAS("NAS"),
}

@Serializable
data class SshDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val remoteInboxDir: String = "~/BgoomDrop",
    val remoteNotesDir: String = "~/BgoomDrop/notes",
    val platform: DevicePlatform = DevicePlatform.MACOS,
    val trustedHostFingerprint: String? = null,
    val hasPassphrase: Boolean = false,
    val keyFingerprint: String = "",
    val publicKeyPreview: String = "",
)

@Serializable
enum class OcrEngineMode(val label: String) {
    LOCAL_PPOCR_V5("离线文字 v5"),
    LOCAL_TEXO_FORMULA("离线公式 Texo"),
    VISION_API("视觉 API / OXR"),
    SSH_REMOTE("SSH 远端"),
}

@Serializable
enum class OcrOutputFormat(val label: String, val extension: String) {
    TYPST("Typst", "typ"),
    MARKDOWN("Markdown", "md"),
    LATEX("LaTeX", "tex"),
}

@Serializable
enum class TypstCompileMode(val label: String) {
    LOCAL_OFFLINE("本地离线"),
    REMOTE_SSH("SSH 远端"),
}

@Serializable
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

@Serializable
data class AppSettings(
    val activeDeviceId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultOcrEngine: OcrEngineMode = OcrEngineMode.LOCAL_PPOCR_V5,
    val defaultOcrFormat: OcrOutputFormat = OcrOutputFormat.TYPST,
    val defaultTypstMode: TypstCompileMode = TypstCompileMode.LOCAL_OFFLINE,
    val visionApiBaseUrl: String = "https://api.openai.com/v1",
    val visionApiModel: String = "gpt-4o-mini",
    val sshOcrCommandTemplate: String = "python3 -m oxr.cli --input {IMAGE} --format {FORMAT}",
    val notifyDesktopOnSend: Boolean = true,
    val autoCopyIncomingText: Boolean = true,
    val githubRepo: String = "smartThise/bgoom",
    val githubMirrorPrefix: String = "",
)

data class NoteItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedDeviceId: String? = null,
    val syncedAt: Long? = null,
)

enum class TransferDirection(val label: String) {
    PUSH("发往电脑"),
    PULL("收到手机"),
    RELAY("跨设备中转"),
}

data class TransferRecord(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val sizeBytes: Long,
    val direction: TransferDirection,
    val deviceSummary: String,
    val remotePath: String,
    val localPath: String,
    val localUri: String = "",
    val mimeType: String = "*/*",
    val success: Boolean,
    val message: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class RemoteEntry(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedEpochSec: Long,
)

data class ConnectionCheckResult(
    val success: Boolean,
    val latencyMs: Long,
    val hostFingerprint: String,
    val remoteOs: String,
    val hasTypst: Boolean,
    val diagnosticMessage: String,
)
