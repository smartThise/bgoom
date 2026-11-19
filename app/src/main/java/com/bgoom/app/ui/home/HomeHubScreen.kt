package com.bgoom.app.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bgoom.app.data.BgoomDatabase
import com.bgoom.app.data.NoteItem
import com.bgoom.app.data.SshDevice
import com.bgoom.app.notify.BgoomNotifier
import com.bgoom.app.ssh.BgoomSyncService
import com.bgoom.app.ssh.SshHubRepository
import com.bgoom.app.ui.BgoomRoute
import com.bgoom.app.update.ReleaseUpdateInfo
import com.bgoom.app.util.AndroidFileHelper
import com.bgoom.app.util.BatteryOptimizationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeHubScreen(
    activeDevice: SshDevice?,
    notifyDesktop: Boolean,
    availableUpdate: ReleaseUpdateInfo?,
    db: BgoomDatabase,
    sshRepo: SshHubRepository,
    onNavigate: (BgoomRoute) -> Unit,
    onOpenUpdateDialog: () -> Unit,
    onShowActionToast: (message: String, actionLabel: String?, onAction: (() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes by db.notes.collectAsState()
    val syncRunning by BgoomSyncService.isRunning.collectAsState()
    var quickNoteText by remember { mutableStateOf("") }

    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            BgoomSyncService.start(context)
            onShowActionToast("已开启电脑推送实时监听", null, null)
        }
    }

    val quickFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dev = activeDevice
        if (dev == null) {
            onShowActionToast("请先添加一台电脑设备", null, null)
            onNavigate(BgoomRoute.DEVICES)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val file = withContext(Dispatchers.IO) { AndroidFileHelper.copyUriToCacheFile(context, uri) }
            if (file == null) {
                onShowActionToast("无法读取所选文件", null, null)
                return@launch
            }
            onShowActionToast("正在发往 ${dev.name}…", null, null)
            sshRepo.uploadFile(dev, file, localUri = uri.toString(), notifyDesktop = notifyDesktop)
                .onSuccess { onShowActionToast("已送到 ${dev.name}：${file.name}", null, null) }
                .onFailure { onShowActionToast(it.message ?: "上传失败", null, null) }
            runCatching { file.delete() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(14.dp))

            // Warm Gemini-style minimal greeting
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "你好，大爆就绪",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = if (activeDevice != null) {
                        "已选定 ${activeDevice.name}，随时可传文件、同步剪贴板与便签"
                    } else {
                        "本地扫描、抠图、OCR 均可离线使用；点顶部添加电脑即可跨网互传"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Unobtrusive GitHub Release Update Pill (only shown when a newer release exists)
            if (availableUpdate != null && availableUpdate.isNewer) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    onClick = onOpenUpdateDialog,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "发现新版本 ${availableUpdate.tagName}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                "点击应用内一键下载并调起安装器",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            // 2x2 Restrained Tonal Gemini Action Tiles
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GeminiQuickTile(
                        icon = Icons.Outlined.DocumentScanner,
                        title = "文档扫描",
                        subtitle = "去影增白 · 导出 PDF",
                        onClick = { onNavigate(BgoomRoute.SCANNER) },
                        modifier = Modifier.weight(1f),
                    )
                    GeminiQuickTile(
                        icon = Icons.Outlined.Functions,
                        title = "文字与公式",
                        subtitle = "手写识别 · Typst 出 PDF",
                        onClick = { onNavigate(BgoomRoute.OCR_TYPST) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GeminiQuickTile(
                        icon = Icons.Outlined.Badge,
                        title = "证件照",
                        subtitle = "精修抠图 · 一寸/二寸排版",
                        onClick = { onNavigate(BgoomRoute.ID_PHOTO) },
                        modifier = Modifier.weight(1f),
                    )
                    GeminiQuickTile(
                        icon = Icons.Outlined.CloudUpload,
                        title = "文件互传",
                        subtitle = "电脑文件 · 剪贴板 · 中转",
                        onClick = { onNavigate(BgoomRoute.TRANSFER) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Instant Device Quick Actions (Clipboard + Real-time Receive Capsule Toggle)
            if (activeDevice != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            onClick = {
                                val clip = AndroidFileHelper.readClipboard(context)
                                if (clip.isBlank()) {
                                    onShowActionToast("手机剪贴板是空的", null, null)
                                } else {
                                    scope.launch {
                                        sshRepo.pushClipboard(activeDevice, clip, notifyDesktop = notifyDesktop)
                                            .onSuccess { ms -> onShowActionToast("已同步到 ${activeDevice.name} 剪贴板 (${ms}ms)", null, null) }
                                            .onFailure { onShowActionToast(it.message ?: "同步失败", null, null) }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("推剪贴板", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            onClick = {
                                scope.launch {
                                    sshRepo.pullClipboard(activeDevice)
                                        .onSuccess { text ->
                                            if (text.isBlank()) onShowActionToast("${activeDevice.name} 剪贴板为空", null, null)
                                            else {
                                                AndroidFileHelper.copyToClipboard(context, activeDevice.name, text)
                                                onShowActionToast("已复制到手机：${text.take(32)}", null, null)
                                            }
                                        }
                                        .onFailure { onShowActionToast(it.message ?: "拉取失败", null, null) }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text("拉取剪贴板", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (syncRunning) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            onClick = {
                                if (syncRunning) {
                                    BgoomSyncService.stop(context)
                                    onShowActionToast("已关闭后台实时接收", null, null)
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !BgoomNotifier.hasNotificationPermission(context)
                                ) {
                                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    BgoomSyncService.start(context)
                                    if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
                                        onShowActionToast("已开启后台常驻，建议设为免电池优化防杀后台", "防杀后台") {
                                            BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                                        }
                                    } else {
                                        onShowActionToast("已开启后台常驻 · 无需打开 App 也能收件", null, null)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.NotificationsActive,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (syncRunning) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (syncRunning) "监听中" else "实时接收",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (syncRunning) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            // Recent Notes preview
            if (notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("最近便签", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onNavigate(BgoomRoute.NOTES) }) {
                            Text("查看全部 (${notes.size})")
                        }
                    }
                    notes.take(3).forEach { note ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onNavigate(BgoomRoute.NOTES) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        note.title.ifBlank { note.content.take(24) },
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        note.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (note.syncedAt != null) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Outlined.CloudDone,
                                        contentDescription = "已同步",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
        }

        // Signature Gemini Floating Bottom Pill Bar
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { quickFilePicker.launch(arrayOf("*/*")) }) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = "选文件直传电脑",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (quickNoteText.isEmpty()) {
                        Text(
                            text = if (activeDevice != null) "写一条便签，秒存并同步到 ${activeDevice.name}…" else "随手记一条便签…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = quickNoteText,
                        onValueChange = { quickNoteText = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (quickNoteText.isBlank()) {
                    IconButton(onClick = { onNavigate(BgoomRoute.SCANNER) }) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = "快速拍照扫描",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                        onClick = {
                            val text = quickNoteText.trim()
                            if (text.isEmpty()) return@Surface
                            val firstLine = text.lines().first().take(28)
                            val note = NoteItem(title = firstLine, content = text)
                            db.upsertNote(note)
                            quickNoteText = ""
                            val dev = activeDevice
                            if (dev != null) {
                                scope.launch {
                                    sshRepo.syncNoteToDevice(dev, note, notifyDesktop = notifyDesktop)
                                        .onSuccess { ms -> onShowActionToast("便签已同步到 ${dev.name} (${ms}ms)", null, null) }
                                        .onFailure { onShowActionToast("已存本地，同步失败：${it.message}", null, null) }
                                }
                            } else {
                                onShowActionToast("已存入本地便签", null, null)
                            }
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = "保存并同步便签",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeminiQuickTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
