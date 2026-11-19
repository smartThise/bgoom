package com.bgoom.app.ui.transfer

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bgoom.app.data.BgoomDatabase
import com.bgoom.app.data.RemoteEntry
import com.bgoom.app.data.SshDevice
import com.bgoom.app.notify.BgoomNotifier
import com.bgoom.app.ssh.BgoomSyncService
import com.bgoom.app.ssh.SshHubRepository
import com.bgoom.app.util.AndroidFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TransferHubScreen(
    devices: List<SshDevice>,
    activeDevice: SshDevice?,
    notifyDesktop: Boolean,
    db: BgoomDatabase,
    sshRepo: SshHubRepository,
    onGoDevices: () -> Unit,
    onShowActionToast: (message: String, actionLabel: String?, onAction: (() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfers by db.transfers.collectAsState()
    val syncRunning by BgoomSyncService.isRunning.collectAsState()

    var currentRemotePath by remember(activeDevice?.id) {
        mutableStateOf(activeDevice?.remoteInboxDir ?: "~/BgoomDrop")
    }
    var resolvedPath by remember(activeDevice?.id) { mutableStateOf("") }
    var remoteEntries by remember(activeDevice?.id) { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var relayCandidate by remember { mutableStateOf<RemoteEntry?>(null) }

    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            BgoomSyncService.start(context)
            onShowActionToast("已开启后台实时接收（状态栏实况胶囊就绪）", null, null)
        } else {
            onShowActionToast("请授予通知权限以便弹出实况胶囊与来件提醒", null, null)
        }
    }

    fun toggleLiveReceive(enable: Boolean) {
        if (!enable) {
            BgoomSyncService.stop(context)
            onShowActionToast("已关闭后台实时接收", null, null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !BgoomNotifier.hasNotificationPermission(context)) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            BgoomSyncService.start(context)
            onShowActionToast("已开启实时接收，电脑右键发文件/文字将秒达手机", null, null)
        }
    }

    fun loadDir(path: String) {
        val dev = activeDevice ?: return
        scope.launch {
            busy = true
            statusText = "正在打开 ${dev.name}:$path …"
            sshRepo.listRemoteDir(dev, path)
                .onSuccess { (resolved, entries) ->
                    resolvedPath = resolved
                    currentRemotePath = resolved
                    remoteEntries = entries
                    statusText = ""
                }
                .onFailure { err ->
                    statusText = err.message ?: "无法读取该目录"
                    remoteEntries = emptyList()
                }
            busy = false
        }
    }

    LaunchedEffect(activeDevice?.id) {
        if (activeDevice != null) {
            loadDir(activeDevice.remoteInboxDir)
        }
    }

    val multiFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val dev = activeDevice
        if (dev == null) {
            onShowActionToast("请先添加并选择电脑设备", null, null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            var okCount = 0
            for ((idx, uri) in uris.withIndex()) {
                val cached = withContext(Dispatchers.IO) { AndroidFileHelper.copyUriToCacheFile(context, uri) }
                if (cached == null) continue
                statusText = "正在发送 (${idx + 1}/${uris.size}) ${cached.name}…"
                sshRepo.uploadFile(
                    device = dev,
                    localFile = cached,
                    remoteDirOverride = resolvedPath.ifBlank { dev.remoteInboxDir },
                    localUri = uri.toString(),
                    notifyDesktop = notifyDesktop,
                    onProgress = { p ->
                        progress = p
                        val pct = (p * 100).toInt()
                        BgoomNotifier.showTransferCapsule(
                            context = context,
                            title = "正在发送 · ${cached.name}",
                            subtitle = "发往 ${dev.name} ($pct%)",
                            shortChip = "发送 $pct%",
                            progressPercent = pct,
                        )
                    }
                ).onSuccess { okCount++ }
                    .onFailure { onShowActionToast(it.message ?: "上传失败", null, null) }
                runCatching { cached.delete() }
            }
            BgoomNotifier.cancelTransferCapsule(context)
            busy = false
            progress = 0f
            statusText = ""
            if (okCount > 0) {
                onShowActionToast("已加密送达 $okCount 个文件至 ${dev.name}", null, null)
                loadDir(currentRemotePath)
            }
        }
    }

    if (activeDevice == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("还没有连接任何电脑", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "填入你的电脑 SSH 地址与私钥后，手机和电脑之间就能随时跨外网传文件、同步剪贴板与互弹通知。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onGoDevices, shape = RoundedCornerShape(24.dp)) {
                Text("添加第一台设备")
            }
        }
        return
    }

    val otherDevices = devices.filter { it.id != activeDevice.id }
    val quickFolders = listOf(
        "收件箱" to activeDevice.remoteInboxDir,
        "桌面" to "~/Desktop",
        "下载" to "~/Downloads",
        "文稿" to "~/Documents",
        "主目录" to "~",
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Live Receive Capsule Toggle Card
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (syncRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.NotificationsActive,
                        contentDescription = null,
                        tint = if (syncRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("实时接收电脑右键推送", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (syncRunning) "监听中 · ${activeDevice.name} 右键发来文件/文字将弹胶囊与通知"
                            else "开启后可在电脑端右键直发文件或选中文字到手机",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = syncRunning, onCheckedChange = ::toggleLiveReceive)
                }
            }
        }

        // Send File + Bidirectional Clipboard Card
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { multiFilePicker.launch(arrayOf("*/*")) },
                        enabled = !busy,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("选手机文件发往 ${activeDevice.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val text = AndroidFileHelper.readClipboard(context)
                                if (text.isBlank()) {
                                    onShowActionToast("手机剪贴板为空", null, null)
                                    return@FilledTonalButton
                                }
                                scope.launch {
                                    busy = true
                                    sshRepo.pushClipboard(activeDevice, text, notifyDesktop = notifyDesktop)
                                        .onSuccess { ms -> onShowActionToast("剪贴板已送到 ${activeDevice.name} (${ms}ms)", null, null) }
                                        .onFailure { onShowActionToast(it.message ?: "推送失败", null, null) }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("推剪贴板")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    sshRepo.pullClipboard(activeDevice)
                                        .onSuccess { text ->
                                            if (text.isBlank()) {
                                                onShowActionToast("${activeDevice.name} 剪贴板为空", null, null)
                                            } else {
                                                AndroidFileHelper.copyToClipboard(context, activeDevice.name, text)
                                                onShowActionToast("已复制：${text.take(36)}", null, null)
                                            }
                                        }
                                        .onFailure { onShowActionToast(it.message ?: "拉取失败", null, null) }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("拉剪贴板")
                        }
                    }

                    if (busy && progress > 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    if (statusText.isNotBlank()) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Quick Remote Folder Chips
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            val parent = currentRemotePath.trimEnd('/').substringBeforeLast('/', "/").ifBlank { "/" }
                            loadDir(parent)
                        },
                        enabled = !busy && currentRemotePath != "/",
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "上级目录")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${activeDevice.name} 的文件", style = MaterialTheme.typography.titleMedium)
                        Text(
                            currentRemotePath,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { loadDir(currentRemotePath) }, enabled = !busy) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quickFolders.forEach { (label, path) ->
                        FilterChip(
                            selected = currentRemotePath.endsWith(path.removePrefix("~/")) ||
                                (path == "~" && currentRemotePath.count { it == '/' } <= 2),
                            onClick = { loadDir(path) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        if (remoteEntries.isEmpty() && !busy) {
            item {
                Text(
                    "这个文件夹目前是空的，可以直接点上方快捷目录切换，或从手机传一个文件进来。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(remoteEntries, key = { it.fullPath }) { entry ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (entry.isDirectory) {
                        loadDir(entry.fullPath)
                    } else if (!busy) {
                        // Tap file directly -> download into public Downloads/BGOOM and offer instant open!
                        scope.launch {
                            busy = true
                            statusText = "正在下载 ${entry.name} 到手机「下载/BGOOM」…"
                            sshRepo.downloadFileToPublicDownloads(context, activeDevice, entry.fullPath) { p ->
                                progress = p
                                val pct = (p * 100).toInt()
                                BgoomNotifier.showTransferCapsule(
                                    context = context,
                                    title = "正在下载 · ${entry.name}",
                                    subtitle = "来自 ${activeDevice.name} ($pct%)",
                                    shortChip = "下载 $pct%",
                                    progressPercent = pct,
                                )
                            }.onSuccess { saved ->
                                onShowActionToast(
                                    "已存到 ${saved.displayLocation}",
                                    "立即打开",
                                ) {
                                    AndroidFileHelper.openFile(context, saved.uri.toString(), "", saved.mimeType)
                                        .onFailure { onShowActionToast("无法打开：${it.message}", null, null) }
                                }
                            }.onFailure {
                                onShowActionToast(it.message ?: "下载失败", null, null)
                            }
                            BgoomNotifier.cancelTransferCapsule(context)
                            busy = false
                            progress = 0f
                            statusText = ""
                        }
                    }
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = fileIconFor(entry.name, entry.isDirectory),
                        contentDescription = null,
                        tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!entry.isDirectory) {
                            Text(
                                "${AndroidFileHelper.formatBytes(entry.sizeBytes)} · 点击下载到手机",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!entry.isDirectory && otherDevices.isNotEmpty()) {
                        IconButton(
                            onClick = { relayCandidate = entry },
                            enabled = !busy,
                        ) {
                            Icon(Icons.Outlined.SwapHoriz, contentDescription = "中转给其他设备", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // Recent Transfer History — DIRECTLY CLICKABLE TO OPEN DOWNLOADED FILES!
        if (transfers.isNotEmpty()) {
            item {
                Spacer(Modifier.height(6.dp))
                Text("最近传输（点击已下载文件直接打开）", style = MaterialTheme.typography.titleMedium)
            }
            items(transfers.take(15), key = { it.id }) { rec ->
                val canOpen = rec.success && (rec.localUri.isNotBlank() || (rec.localPath.isNotBlank() && File(rec.localPath).exists()))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (canOpen) {
                            AndroidFileHelper.openFile(context, rec.localUri, rec.localPath, rec.mimeType)
                                .onFailure { onShowActionToast("打不开文件：${it.message}", null, null) }
                        } else {
                            onShowActionToast(rec.message, null, null)
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = fileIconFor(rec.fileName, isDir = false),
                            contentDescription = null,
                            tint = if (rec.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                rec.fileName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${rec.direction.label} · ${AndroidFileHelper.formatBytes(rec.sizeBytes)} · ${AndroidFileHelper.formatRelativeTime(rec.createdAt)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                rec.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (rec.success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (canOpen) {
                            IconButton(
                                onClick = {
                                    AndroidFileHelper.openFile(context, rec.localUri, rec.localPath, rec.mimeType)
                                        .onFailure { onShowActionToast("打开失败：${it.message}", null, null) }
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "直接打开", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = {
                                    AndroidFileHelper.shareUriOrFile(context, rec.localUri, rec.localPath, rec.mimeType)
                                        .onFailure { onShowActionToast("分享失败：${it.message}", null, null) }
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Outlined.Share, contentDescription = "分享", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // Hub relay dialog (Device A -> Phone -> Device B)
    relayCandidate?.let { entry ->
        AlertDialog(
            onDismissRequest = { relayCandidate = null },
            title = { Text("经手机中转「${entry.name}」") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "设备间互不直连，将先拉取到手机再加密转发给目标设备：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    otherDevices.forEach { target ->
                        FilledTonalButton(
                            onClick = {
                                relayCandidate = null
                                scope.launch {
                                    busy = true
                                    sshRepo.relayFile(
                                        sourceDevice = activeDevice,
                                        remoteSourcePath = entry.fullPath,
                                        targetDevice = target,
                                        tempCacheDir = File(context.cacheDir, "relay").apply { mkdirs() },
                                        notifyDesktop = notifyDesktop,
                                    ) { p, label ->
                                        progress = p
                                        statusText = label
                                    }.onSuccess {
                                        onShowActionToast("中转完成：${activeDevice.name} → ${target.name}", null, null)
                                    }.onFailure { err ->
                                        onShowActionToast("中转失败：${err.message}", null, null)
                                    }
                                    busy = false
                                    progress = 0f
                                    statusText = ""
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("发给 ${target.name}")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { relayCandidate = null }) { Text("取消") } },
        )
    }
}

private fun fileIconFor(name: String, isDir: Boolean) = when {
    isDir -> Icons.Outlined.Folder
    name.endsWith(".pdf", ignoreCase = true) -> Icons.Outlined.PictureAsPdf
    name.endsWith(".jpg", ignoreCase = true) ||
        name.endsWith(".jpeg", ignoreCase = true) ||
        name.endsWith(".png", ignoreCase = true) ||
        name.endsWith(".webp", ignoreCase = true) -> Icons.Outlined.Image
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}
