package com.bgoom.app.ssh

import android.content.Context
import com.bgoom.app.data.BgoomDatabase
import com.bgoom.app.data.ConnectionCheckResult
import com.bgoom.app.data.DevicePlatform
import com.bgoom.app.data.NoteItem
import com.bgoom.app.data.RemoteEntry
import com.bgoom.app.data.SshDevice
import com.bgoom.app.data.TransferDirection
import com.bgoom.app.data.TransferRecord
import com.bgoom.app.util.AndroidFileHelper
import com.bgoom.app.util.SavedPublicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OutboxDeliveryEvent(
    val isFile: Boolean,
    val title: String,
    val previewOrPath: String,
    val savedFile: SavedPublicFile? = null,
)

/**
 * Central phone-as-hub SSH/SFTP repository:
 * - Multi-device file push / pull (into public /Download/BGOOM) / A->Phone->B relay
 * - Zero-daemon desktop native notification trigger on Mac/Linux/WSL upon arrival
 * - Companion outbox polling (Finder right-click / Windows SendTo -> instant phone delivery)
 * - Ultra-fast pooled SFTP Markdown notes sync & clipboard sync
 * - Remote Typst compilation & remote SSH OCR execution
 */
class SshHubRepository(
    private val pool: SshSessionPool,
    private val db: BgoomDatabase,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun testConnection(device: SshDevice): ConnectionCheckResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            pool.withClient(device) { client, fp ->
                val os = execSimple(client, "uname -s 2>/dev/null || echo Ready").trim()
                val typstPath = execSimple(
                    client,
                    "PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin:\$HOME/.cargo/bin command -v typst 2>/dev/null || true"
                ).trim()
                val latency = System.currentTimeMillis() - start
                ConnectionCheckResult(
                    success = true,
                    latencyMs = latency,
                    hostFingerprint = fp,
                    remoteOs = os.ifBlank { "SSH Ready" },
                    hasTypst = typstPath.isNotBlank(),
                    diagnosticMessage = "连接正常 · ${latency}ms · $os" +
                        if (typstPath.isNotBlank()) " · 远端 Typst 可用" else ""
                )
            }
        } catch (t: Throwable) {
            ConnectionCheckResult(
                success = false,
                latencyMs = System.currentTimeMillis() - start,
                hostFingerprint = device.trustedHostFingerprint.orEmpty(),
                remoteOs = "",
                hasTypst = false,
                diagnosticMessage = SshSessionPool.diagnoseError(t, device)
            )
        }
    }

    suspend fun listRemoteDir(device: SshDevice, rawPath: String): Result<Pair<String, List<RemoteEntry>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                pool.withClient(device) { client, _ ->
                    client.newSFTPClient().use { sftp ->
                        val resolved = resolveRemotePath(sftp, rawPath)
                        if (sftp.statExistence(resolved) == null && rawPath == device.remoteInboxDir) {
                            ensureRemoteDir(sftp, resolved)
                        }
                        val items = sftp.ls(resolved)
                            .filter { it.name != "." && it.name != ".." && !it.name.startsWith("._") }
                            .map { info ->
                                RemoteEntry(
                                    name = info.name,
                                    fullPath = joinRemote(resolved, info.name),
                                    isDirectory = info.isDirectory,
                                    sizeBytes = info.attributes.size,
                                    modifiedEpochSec = info.attributes.mtime,
                                )
                            }
                            .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
                        resolved to items
                    }
                }
            }.recoverCatching { throw IllegalStateException(SshSessionPool.diagnoseError(it, device), it) }
        }

    suspend fun uploadFile(
        device: SshDevice,
        localFile: File,
        remoteDirOverride: String? = null,
        remoteFileName: String = localFile.name,
        localUri: String = "",
        notifyDesktop: Boolean = true,
        onProgress: (Float) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        val targetDirRaw = remoteDirOverride?.takeIf { it.isNotBlank() } ?: device.remoteInboxDir
        val cleanName = sanitizeFileName(remoteFileName)
        var finalRemotePath = ""
        val result = runCatching {
            pool.withClient(device) { client, _ ->
                client.newSFTPClient().use { sftp ->
                    val targetDir = resolveRemotePath(sftp, targetDirRaw)
                    ensureRemoteDir(sftp, targetDir)
                    finalRemotePath = joinRemote(targetDir, cleanName)
                    streamUpload(sftp, localFile, finalRemotePath, onProgress)
                }
                if (notifyDesktop) {
                    runCatching {
                        sendDesktopNotification(
                            client = client,
                            platform = device.platform,
                            title = "大爆 BGOOM · 收到手机文件",
                            body = "$cleanName（已存入 ${targetDirRaw}）",
                        )
                    }
                }
                finalRemotePath
            }
        }
        db.recordTransfer(
            TransferRecord(
                fileName = cleanName,
                sizeBytes = localFile.length(),
                direction = TransferDirection.PUSH,
                deviceSummary = "手机 → ${device.name}",
                remotePath = finalRemotePath.ifBlank { targetDirRaw },
                localPath = localFile.absolutePath,
                localUri = localUri,
                mimeType = AndroidFileHelper.guessMimeType(cleanName),
                success = result.isSuccess,
                message = result.fold(
                    onSuccess = { "已送达电脑" },
                    onFailure = { SshSessionPool.diagnoseError(it, device) }
                )
            )
        )
        result.recoverCatching { throw IllegalStateException(SshSessionPool.diagnoseError(it, device), it) }
    }

    /**
     * Downloads a remote file directly into the public system Downloads/BGOOM directory
     * so Honor File Manager, Gallery, and WPS discover it immediately and one-tap open works.
     */
    suspend fun downloadFileToPublicDownloads(
        context: Context,
        device: SshDevice,
        remotePath: String,
        onProgress: (Float) -> Unit = {},
    ): Result<SavedPublicFile> = withContext(Dispatchers.IO) {
        val fileName = sanitizeFileName(remotePath.substringAfterLast('/').ifBlank { "bgoom_${System.currentTimeMillis()}" })
        val tempFile = File(context.cacheDir, "pull_tmp/${UUID.randomUUID()}_$fileName").apply {
            parentFile?.mkdirs()
        }
        var sizeBytes = 0L
        val result = runCatching {
            pool.withClient(device) { client, _ ->
                client.newSFTPClient().use { sftp ->
                    val resolved = resolveRemotePath(sftp, remotePath)
                    streamDownload(sftp, resolved, tempFile, onProgress)
                }
            }
            sizeBytes = tempFile.length()
            AndroidFileHelper.saveFileToPublicDownloads(context, tempFile, fileName).getOrThrow()
        }
        runCatching { tempFile.delete() }

        val saved = result.getOrNull()
        db.recordTransfer(
            TransferRecord(
                fileName = saved?.fileName ?: fileName,
                sizeBytes = sizeBytes,
                direction = TransferDirection.PULL,
                deviceSummary = "${device.name} → 手机",
                remotePath = remotePath,
                localPath = saved?.displayLocation ?: "",
                localUri = saved?.uri?.toString() ?: "",
                mimeType = saved?.mimeType ?: AndroidFileHelper.guessMimeType(fileName),
                success = result.isSuccess,
                message = result.fold(
                    onSuccess = { "已存至 ${it.displayLocation} · 点击打开" },
                    onFailure = { SshSessionPool.diagnoseError(it, device) }
                )
            )
        )
        result.recoverCatching { throw IllegalStateException(SshSessionPool.diagnoseError(it, device), it) }
    }

    /**
     * Polls ~/BgoomDrop/outbox/events/ for files or selected text queued by the Mac Finder Quick Action
     * or Windows SendTo companion. Zero extra ports required — rides over the existing SSH session.
     */
    suspend fun pollAndConsumeOutbox(
        context: Context,
        device: SshDevice,
        autoCopyText: Boolean,
        notifyDesktopReceipt: Boolean,
        onFileProgress: (fileName: String, progress: Float) -> Unit,
    ): Result<List<OutboxDeliveryEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val delivered = mutableListOf<OutboxDeliveryEvent>()
            pool.withClient(device) { client, _ ->
                client.newSFTPClient().use { sftp ->
                    val inboxBase = resolveRemotePath(sftp, device.remoteInboxDir)
                    val eventsDir = joinRemote(inboxBase, "outbox/events")
                    if (sftp.statExistence(eventsDir) == null) {
                        return@withClient
                    }
                    val eventFiles = sftp.ls(eventsDir)
                        .filter { !it.isDirectory && it.name.endsWith(".json") && !it.name.startsWith("._") }
                        .sortedBy { it.attributes.mtime }

                    for (ev in eventFiles.take(10)) {
                        val evFullPath = joinRemote(eventsDir, ev.name)
                        val rawJson = readRemoteBytes(sftp, evFullPath, maxBytes = 1024 * 1024)
                            .toString(StandardCharsets.UTF_8)
                        val obj = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
                        if (obj == null) {
                            runCatching { sftp.rm(evFullPath) }
                            continue
                        }
                        val kind = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        when (kind) {
                            "note" -> {
                                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                                    ?: "来自 ${device.name}"
                                val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                if (content.isNotBlank()) {
                                    val note = NoteItem(
                                        title = title.take(40),
                                        content = content,
                                        syncedDeviceId = device.id,
                                        syncedAt = System.currentTimeMillis(),
                                    )
                                    db.upsertNote(note)
                                    if (autoCopyText) {
                                        AndroidFileHelper.copyToClipboard(context, title, content)
                                    }
                                    delivered.add(
                                        OutboxDeliveryEvent(
                                            isFile = false,
                                            title = note.title,
                                            previewOrPath = content,
                                        )
                                    )
                                    if (notifyDesktopReceipt) {
                                        runCatching {
                                            sendDesktopNotification(
                                                client, device.platform,
                                                "大爆 BGOOM",
                                                "手机已收到便签：${note.title.take(24)}"
                                            )
                                        }
                                    }
                                }
                                runCatching { sftp.rm(evFullPath) }
                            }

                            "file" -> {
                                val fileName = sanitizeFileName(
                                    obj["fileName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        .ifBlank { "file_${System.currentTimeMillis()}" }
                                )
                                val storedName = obj["storedName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val remoteFile = when {
                                    storedName.isNotBlank() -> joinRemote(inboxBase, "outbox/files/$storedName")
                                    else -> obj["remotePath"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        .ifBlank { joinRemote(inboxBase, "outbox/files/$fileName") }
                                }

                                if (sftp.statExistence(remoteFile) != null) {
                                    val tmp = File(context.cacheDir, "outbox_pull/${UUID.randomUUID()}_$fileName").apply {
                                        parentFile?.mkdirs()
                                    }
                                    try {
                                        streamDownload(sftp, remoteFile, tmp) { p ->
                                            onFileProgress(fileName, p)
                                        }
                                        val saved = AndroidFileHelper.saveFileToPublicDownloads(context, tmp, fileName).getOrThrow()
                                        db.recordTransfer(
                                            TransferRecord(
                                                fileName = saved.fileName,
                                                sizeBytes = tmp.length(),
                                                direction = TransferDirection.PULL,
                                                deviceSummary = "${device.name} → 手机（推送）",
                                                remotePath = remoteFile,
                                                localPath = saved.displayLocation,
                                                localUri = saved.uri.toString(),
                                                mimeType = saved.mimeType,
                                                success = true,
                                                message = "电脑推送到达 · ${saved.displayLocation}",
                                            )
                                        )
                                        delivered.add(
                                            OutboxDeliveryEvent(
                                                isFile = true,
                                                title = saved.fileName,
                                                previewOrPath = saved.displayLocation,
                                                savedFile = saved,
                                            )
                                        )
                                        runCatching { sftp.rm(remoteFile) }
                                        runCatching { sftp.rm(evFullPath) }
                                        if (notifyDesktopReceipt) {
                                            runCatching {
                                                sendDesktopNotification(
                                                    client, device.platform,
                                                    "大爆 BGOOM",
                                                    "手机已接收文件：${saved.fileName}"
                                                )
                                            }
                                        }
                                    } finally {
                                        runCatching { tmp.delete() }
                                    }
                                } else {
                                    runCatching { sftp.rm(evFullPath) }
                                }
                            }

                            else -> runCatching { sftp.rm(evFullPath) }
                        }
                    }
                }
            }
            delivered
        }
    }

    /**
     * Hub relay: Device A -> Phone temp cache -> Device B (devices never connect directly).
     */
    suspend fun relayFile(
        sourceDevice: SshDevice,
        remoteSourcePath: String,
        targetDevice: SshDevice,
        tempCacheDir: File,
        notifyDesktop: Boolean = true,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result<String> = withContext(Dispatchers.IO) {
        val fileName = sanitizeFileName(
            remoteSourcePath.substringAfterLast('/').ifBlank { "relay_${System.currentTimeMillis()}" }
        )
        val tempFile = File(tempCacheDir, "relay_${UUID.randomUUID()}_$fileName")
        var destPath = ""
        val result = runCatching {
            onProgress(0.02f, "从 ${sourceDevice.name} 拉取中…")
            pool.withClient(sourceDevice) { srcClient, _ ->
                srcClient.newSFTPClient().use { sftp ->
                    val resolved = resolveRemotePath(sftp, remoteSourcePath)
                    streamDownload(sftp, resolved, tempFile) { p ->
                        onProgress(p * 0.5f, "从 ${sourceDevice.name} 拉取 ${(p * 100).toInt()}%")
                    }
                }
            }
            onProgress(0.52f, "转发至 ${targetDevice.name}…")
            pool.withClient(targetDevice) { dstClient, _ ->
                dstClient.newSFTPClient().use { sftp ->
                    val targetDir = resolveRemotePath(sftp, targetDevice.remoteInboxDir)
                    ensureRemoteDir(sftp, targetDir)
                    destPath = joinRemote(targetDir, fileName)
                    streamUpload(sftp, tempFile, destPath) { p ->
                        onProgress(0.5f + p * 0.5f, "转发至 ${targetDevice.name} ${(p * 100).toInt()}%")
                    }
                }
                if (notifyDesktop) {
                    runCatching {
                        sendDesktopNotification(
                            dstClient, targetDevice.platform,
                            "大爆 BGOOM · 跨设备中转",
                            "收到来自 ${sourceDevice.name} 的文件：$fileName"
                        )
                    }
                }
            }
            destPath
        }
        val size = if (tempFile.exists()) tempFile.length() else 0L
        runCatching { tempFile.delete() }
        db.recordTransfer(
            TransferRecord(
                fileName = fileName,
                sizeBytes = size,
                direction = TransferDirection.RELAY,
                deviceSummary = "${sourceDevice.name} → 手机 → ${targetDevice.name}",
                remotePath = destPath.ifBlank { remoteSourcePath },
                localPath = "",
                localUri = "",
                mimeType = AndroidFileHelper.guessMimeType(fileName),
                success = result.isSuccess,
                message = result.fold(
                    onSuccess = { "中转完成" },
                    onFailure = { it.message ?: "中转失败" }
                )
            )
        )
        result
    }

    suspend fun syncNoteToDevice(
        device: SshDevice,
        note: NoteItem,
        notifyDesktop: Boolean = true,
    ): Result<Long> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        runCatching {
            val mdContent = buildString {
                if (note.title.isNotBlank()) {
                    append("# ").append(note.title.trim()).append("\n\n")
                }
                append(note.content)
                if (!note.content.endsWith("\n")) append("\n")
            }.toByteArray(StandardCharsets.UTF_8)

            val shortId = note.id.take(8)
            val safeTitle = sanitizeFileName(note.title.ifBlank { "note" }).take(32)
            val fileName = "${safeTitle}_${shortId}.md"

            pool.withClient(device) { client, _ ->
                client.newSFTPClient().use { sftp ->
                    val dir = resolveRemotePath(sftp, device.remoteNotesDir)
                    ensureRemoteDir(sftp, dir)
                    val target = joinRemote(dir, fileName)
                    writeRemoteBytes(sftp, target, mdContent)
                }
                if (notifyDesktop) {
                    runCatching {
                        sendDesktopNotification(
                            client, device.platform,
                            "大爆 BGOOM · 收到手机便签",
                            note.title.ifBlank { note.content.take(48) }
                        )
                    }
                }
            }
            val now = System.currentTimeMillis()
            db.markNoteSynced(note.id, device.id, now)
            now - start
        }.recoverCatching { throw IllegalStateException(SshSessionPool.diagnoseError(it, device), it) }
    }

    suspend fun pullNotesFromDevice(device: SshDevice): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            pool.withClient(device) { client, _ ->
                client.newSFTPClient().use { sftp ->
                    val dir = resolveRemotePath(sftp, device.remoteNotesDir)
                    ensureRemoteDir(sftp, dir)
                    val entries = sftp.ls(dir).filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) && !it.name.startsWith("._") }
                    var imported = 0
                    val existingById = db.notes.value.associateBy { it.id }
                    for (entry in entries.take(50)) {
                        val bytes = readRemoteBytes(sftp, joinRemote(dir, entry.name), maxBytes = 512 * 1024)
                        val text = bytes.toString(StandardCharsets.UTF_8).trim()
                        val lines = text.lines()
                        val title = if (lines.firstOrNull()?.startsWith("# ") == true) {
                            lines.first().removePrefix("# ").trim()
                        } else {
                            entry.name.removeSuffix(".md")
                        }
                        val body = if (lines.firstOrNull()?.startsWith("# ") == true) {
                            lines.drop(1).joinToString("\n").trimStart()
                        } else {
                            text
                        }
                        val noteId = "remote_${device.id.take(6)}_${entry.name}"
                        val mtimeMs = entry.attributes.mtime * 1000L
                        val prev = existingById[noteId]
                        if (prev == null || mtimeMs > prev.updatedAt) {
                            db.upsertNote(
                                NoteItem(
                                    id = noteId,
                                    title = title,
                                    content = body,
                                    updatedAt = if (mtimeMs > 0) mtimeMs else System.currentTimeMillis(),
                                    syncedDeviceId = device.id,
                                    syncedAt = System.currentTimeMillis(),
                                )
                            )
                            imported++
                        }
                    }
                    imported
                }
            }
        }.recoverCatching { throw IllegalStateException(SshSessionPool.diagnoseError(it, device), it) }
    }

    suspend fun pushClipboard(
        device: SshDevice,
        text: String,
        notifyDesktop: Boolean = true,
    ): Result<Long> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        runCatching {
            val cmd = when (device.platform) {
                DevicePlatform.MACOS -> "LANG=en_US.UTF-8 pbcopy"
                DevicePlatform.WINDOWS_WSL -> "/mnt/c/Windows/System32/clip.exe"
                DevicePlatform.LINUX, DevicePlatform.NAS ->
                    "(command -v wl-copy >/dev/null && wl-copy) || (command -v xclip >/dev/null && xclip -selection clipboard) || cat > ~/BgoomDrop/.clipboard.txt"
            }
            pool.withClient(device) { client, _ ->
                client.startSession().use { session ->
                    val exec = session.exec(cmd)
                    exec.outputStream.use { os ->
                        os.write(text.toByteArray(StandardCharsets.UTF_8))
                        os.flush()
                    }
                    exec.join(10, TimeUnit.SECONDS)
                    if (exec.exitStatus != null && exec.exitStatus != 0) {
                        val err = IOUtils.readFully(exec.errorStream).toString(StandardCharsets.UTF_8.name())
                        throw IOException("剪贴板写入失败 (${exec.exitStatus}): $err")
                    }
                }
                if (notifyDesktop) {
                    runCatching {
                        sendDesktopNotification(
                            client, device.platform,
                            "大爆 BGOOM · 剪贴板已同步",
                            text.take(60)
                        )
                    }
                }
            }
            System.currentTimeMillis() - start
        }.recoverCatching { throw IllegalStateException(SshSessionPool.diagnoseError(it, device), it) }
    }

    suspend fun pullClipboard(device: SshDevice): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cmd = when (device.platform) {
                DevicePlatform.MACOS -> "LANG=en_US.UTF-8 pbpaste"
                DevicePlatform.WINDOWS_WSL -> "/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -Command Get-Clipboard"
                DevicePlatform.LINUX, DevicePlatform.NAS ->
                    "(command -v wl-paste >/dev/null && wl-paste) || (command -v xclip >/dev/null && xclip -o -selection clipboard) || cat ~/BgoomDrop/.clipboard.txt 2>/dev/null"
            }
            pool.withClient(device) { client, _ ->
                execSimple(client, cmd)
            }
        }.recoverCatching { throw IllegalStateException(SshSessionPool.diagnoseError(it, device), it) }
    }

    suspend fun compileTypstRemote(device: SshDevice, typstSource: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tag = "bgoom_${UUID.randomUUID().toString().take(8)}"
                val remoteTyp = "/tmp/$tag.typ"
                val remotePdf = "/tmp/$tag.pdf"
                pool.withClient(device) { client, _ ->
                    client.newSFTPClient().use { sftp ->
                        writeRemoteBytes(sftp, remoteTyp, typstSource.toByteArray(StandardCharsets.UTF_8))
                    }
                    val compileCmd =
                        "PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin:\$HOME/.cargo/bin typst compile '$remoteTyp' '$remotePdf'"
                    val (exitCode, stdout, stderr) = execFull(client, compileCmd, timeoutSec = 30)
                    if (exitCode != 0) {
                        execSimple(client, "rm -f '$remoteTyp' '$remotePdf'")
                        throw IllegalArgumentException(stderr.ifBlank { stdout.ifBlank { "远端 typst 编译失败 (code=$exitCode)" } })
                    }
                    val pdfBytes = client.newSFTPClient().use { sftp ->
                        readRemoteBytes(sftp, remotePdf, maxBytes = 64 * 1024 * 1024)
                    }
                    execSimple(client, "rm -f '$remoteTyp' '$remotePdf'")
                    pdfBytes
                }
            }.recoverCatching { throw IllegalStateException(SshSessionPool.diagnoseError(it, device), it) }
        }

    suspend fun runRemoteSshOcr(
        device: SshDevice,
        imageJpegBytes: ByteArray,
        commandTemplate: String,
        formatHint: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val tag = "bgoom_ocr_${UUID.randomUUID().toString().take(8)}"
            val remoteImg = "/tmp/$tag.jpg"
            pool.withClient(device) { client, _ ->
                client.newSFTPClient().use { sftp ->
                    writeRemoteBytes(sftp, remoteImg, imageJpegBytes)
                }
                val cmd = commandTemplate
                    .replace("{IMAGE}", "'$remoteImg'")
                    .replace("{FORMAT}", formatHint)
                val wrapped = "PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin:\$HOME/.local/bin; $cmd"
                val (exitCode, stdout, stderr) = execFull(client, wrapped, timeoutSec = 120)
                execSimple(client, "rm -f '$remoteImg'")
                if (exitCode != 0) {
                    throw IllegalStateException("远端识别返回 $exitCode：\n${stderr.ifBlank { stdout }}")
                }
                stdout.trim()
            }
        }.recoverCatching { throw IllegalStateException(SshSessionPool.diagnoseError(it, device), it) }
    }

    /**
     * Zero-daemon native desktop notification executed right over the existing SSH session.
     */
    private fun sendDesktopNotification(
        client: SSHClient,
        platform: DevicePlatform,
        title: String,
        body: String,
    ) {
        val safeTitle = escapeSingleLine(title).take(60)
        val safeBody = escapeSingleLine(body).take(140)
        val cmd = when (platform) {
            DevicePlatform.MACOS ->
                "osascript -e 'display notification \"$safeBody\" with title \"$safeTitle\" sound name \"Glass\"' >/dev/null 2>&1 &"
            DevicePlatform.LINUX ->
                "command -v notify-send >/dev/null && notify-send '$safeTitle' '$safeBody' >/dev/null 2>&1 &"
            DevicePlatform.WINDOWS_WSL ->
                "/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -Command \"Add-Type -AssemblyName System.Windows.Forms; \$n=New-Object System.Windows.Forms.NotifyIcon; \$n.Icon=[System.Drawing.SystemIcons]::Information; \$n.Visible=\$true; \$n.ShowBalloonTip(4000,'$safeTitle','$safeBody',[System.Windows.Forms.ToolTipIcon]::Info)\" >/dev/null 2>&1 &"
            DevicePlatform.NAS -> return
        }
        execFull(client, cmd, timeoutSec = 4)
    }

    private fun escapeSingleLine(raw: String): String =
        raw.replace("\r", " ")
            .replace("\n", " ")
            .replace("\\", "\\\\")
            .replace("\"", "'")
            .replace("'", "")
            .replace("$", "")
            .replace("`", "")

    private fun resolveRemotePath(sftp: SFTPClient, raw: String): String {
        val trimmed = raw.trim().ifBlank { "~" }
        val home = sftp.canonicalize(".")
        return when {
            trimmed == "~" -> home
            trimmed.startsWith("~/") -> joinRemote(home, trimmed.removePrefix("~/"))
            trimmed.startsWith("/") -> trimmed
            else -> joinRemote(home, trimmed)
        }
    }

    private fun joinRemote(base: String, child: String): String {
        val cleanBase = base.trimEnd('/')
        val cleanChild = child.trimStart('/')
        return if (cleanBase.isEmpty()) "/$cleanChild" else "$cleanBase/$cleanChild"
    }

    private fun ensureRemoteDir(sftp: SFTPClient, path: String) {
        if (sftp.statExistence(path) != null) return
        val parts = path.split('/').filter { it.isNotEmpty() }
        var cur = if (path.startsWith("/")) "" else "."
        for (p in parts) {
            cur = if (cur == ".") p else "$cur/$p"
            if (sftp.statExistence(cur) == null) {
                sftp.mkdir(cur)
            }
        }
    }

    private fun streamUpload(
        sftp: SFTPClient,
        localFile: File,
        remotePath: String,
        onProgress: (Float) -> Unit,
    ) {
        val total = maxOf(1L, localFile.length())
        sftp.open(remotePath, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { handle ->
            FileInputStream(localFile).use { fis ->
                val buf = ByteArray(64 * 1024)
                var offset = 0L
                while (true) {
                    val read = fis.read(buf)
                    if (read <= 0) break
                    handle.write(offset, buf, 0, read)
                    offset += read
                    onProgress((offset.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                }
            }
        }
        onProgress(1f)
    }

    private fun streamDownload(
        sftp: SFTPClient,
        remotePath: String,
        localFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val attrs: FileAttributes = sftp.stat(remotePath)
        val total = maxOf(1L, attrs.size)
        sftp.open(remotePath, EnumSet.of(OpenMode.READ)).use { handle ->
            FileOutputStream(localFile).use { fos ->
                val buf = ByteArray(64 * 1024)
                var offset = 0L
                while (true) {
                    val read = handle.read(offset, buf, 0, buf.size)
                    if (read <= 0) break
                    fos.write(buf, 0, read)
                    offset += read
                    onProgress((offset.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                }
            }
        }
        onProgress(1f)
    }

    private fun writeRemoteBytes(sftp: SFTPClient, remotePath: String, data: ByteArray) {
        sftp.open(remotePath, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { handle ->
            var offset = 0
            while (offset < data.size) {
                val chunk = minOf(64 * 1024, data.size - offset)
                handle.write(offset.toLong(), data, offset, chunk)
                offset += chunk
            }
        }
    }

    private fun readRemoteBytes(sftp: SFTPClient, remotePath: String, maxBytes: Int): ByteArray {
        val attrs = sftp.stat(remotePath)
        if (attrs.size > maxBytes) {
            throw IOException("远端文件过大 (${attrs.size} bytes)")
        }
        val out = ByteArray(attrs.size.toInt())
        sftp.open(remotePath, EnumSet.of(OpenMode.READ)).use { handle ->
            var offset = 0
            while (offset < out.size) {
                val read = handle.read(offset.toLong(), out, offset, minOf(64 * 1024, out.size - offset))
                if (read <= 0) break
                offset += read
            }
        }
        return out
    }

    private fun execSimple(client: SSHClient, command: String): String =
        execFull(client, command, timeoutSec = 15).second

    private fun execFull(client: SSHClient, command: String, timeoutSec: Long): Triple<Int, String, String> {
        client.startSession().use { session ->
            val cmd = session.exec(command)
            val stdout = IOUtils.readFully(cmd.inputStream).toString(StandardCharsets.UTF_8.name())
            val stderr = IOUtils.readFully(cmd.errorStream).toString(StandardCharsets.UTF_8.name())
            cmd.join(timeoutSec, TimeUnit.SECONDS)
            return Triple(cmd.exitStatus ?: 0, stdout, stderr)
        }
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().ifBlank { "file" }
}
