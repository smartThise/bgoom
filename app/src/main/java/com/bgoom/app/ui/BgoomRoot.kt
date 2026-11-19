package com.bgoom.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bgoom.app.BgoomApp
import com.bgoom.app.BuildConfig
import com.bgoom.app.data.NoteItem
import com.bgoom.app.data.SshDevice
import com.bgoom.app.notify.BgoomNotifier
import com.bgoom.app.scanner.BitmapIO
import com.bgoom.app.ssh.BgoomSyncService
import com.bgoom.app.ui.devices.DevicesScreen
import com.bgoom.app.ui.devices.SettingsScreen
import com.bgoom.app.ui.home.HomeHubScreen
import com.bgoom.app.ui.idphoto.IdPhotoScreen
import com.bgoom.app.ui.notes.NotesScreen
import com.bgoom.app.ui.ocr.OcrTypstScreen
import com.bgoom.app.ui.scanner.ScannerScreen
import com.bgoom.app.ui.theme.BgoomTheme
import com.bgoom.app.ui.transfer.TransferHubScreen
import com.bgoom.app.update.GitHubUpdater
import com.bgoom.app.update.ReleaseUpdateInfo
import com.bgoom.app.util.AndroidFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class BgoomRoute(val title: String, val icon: ImageVector) {
    HOME("主页", Icons.Outlined.Home),
    SCANNER("文档扫描", Icons.Outlined.DocumentScanner),
    OCR_TYPST("文字与公式", Icons.Outlined.Functions),
    ID_PHOTO("证件照", Icons.Outlined.Badge),
    TRANSFER("文件互传", Icons.Outlined.CloudUpload),
    NOTES("文字便签", Icons.Outlined.EditNote),
    DEVICES("我的电脑", Icons.Outlined.Devices),
    SETTINGS("设置与更新", Icons.Outlined.Settings),
}

data class IncomingSharePayload(
    val text: String? = null,
    val uris: List<Uri> = emptyList(),
    val mimeType: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BgoomRoot(
    app: BgoomApp,
    incomingShare: IncomingSharePayload?,
    onClearIncomingShare: () -> Unit,
    requestedRoute: BgoomRoute? = null,
    onConsumeRequestedRoute: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices by app.deviceStore.devices.collectAsState()
    val settings by app.deviceStore.settings.collectAsState()
    val syncRunning by BgoomSyncService.isRunning.collectAsState()
    val activeDevice: SshDevice? = remember(devices, settings.activeDeviceId) {
        devices.firstOrNull { it.id == settings.activeDeviceId } ?: devices.firstOrNull()
    }

    var currentRoute by remember { mutableStateOf(BgoomRoute.HOME) }
    LaunchedEffect(requestedRoute) {
        if (requestedRoute != null) {
            currentRoute = requestedRoute
            onConsumeRequestedRoute()
        }
    }
    var pendingOcrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingScanBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingIdPhotoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var deviceMenuExpanded by remember { mutableStateOf(false) }

    // GitHub Releases Auto-Update state
    var availableUpdate by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableFloatStateOf(0f) }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }
    var updateStatusMsg by remember { mutableStateOf("") }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    fun showActionToast(msg: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val res = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = actionLabel,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (res == SnackbarResult.ActionPerformed && onAction != null) {
                onAction()
            }
        }
    }

    // Silent once-per-launch GitHub Releases update check
    LaunchedEffect(Unit) {
        val repo = settings.githubRepo
        if (repo.isNotBlank()) {
            GitHubUpdater.checkLatestRelease(repo).onSuccess { info ->
                if (info.isNewer) {
                    availableUpdate = info
                    BgoomNotifier.showUpdateAlert(context, info.tagName, info.releaseNotes.lines().firstOrNull().orEmpty())
                }
            }
        }
    }

    BgoomTheme(themeMode = settings.themeMode) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.width(284.dp),
                ) {
                    Spacer(Modifier.height(20.dp))
                    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
                        Text("大爆 BGOOM", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    val primaryRoutes = listOf(
                        BgoomRoute.HOME,
                        BgoomRoute.SCANNER,
                        BgoomRoute.OCR_TYPST,
                        BgoomRoute.ID_PHOTO,
                        BgoomRoute.TRANSFER,
                        BgoomRoute.NOTES,
                    )
                    primaryRoutes.forEach { route ->
                        NavigationDrawerItem(
                            icon = { Icon(route.icon, contentDescription = null) },
                            label = { Text(route.title) },
                            selected = currentRoute == route,
                            onClick = {
                                currentRoute = route
                                scope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }

                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                    listOf(BgoomRoute.DEVICES, BgoomRoute.SETTINGS).forEach { route ->
                        NavigationDrawerItem(
                            icon = { Icon(route.icon, contentDescription = null) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(route.title)
                                    if (route == BgoomRoute.SETTINGS && availableUpdate?.isNewer == true) {
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                            },
                            selected = currentRoute == route,
                            onClick = {
                                currentRoute = route
                                scope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            },
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (currentRoute == BgoomRoute.HOME) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Outlined.Menu, contentDescription = "菜单")
                            }
                        } else {
                            IconButton(onClick = { currentRoute = BgoomRoute.HOME }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回主页")
                            }
                        }

                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                onClick = { deviceMenuExpanded = true },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    syncRunning -> Color(0xFF188038)
                                                    activeDevice != null -> MaterialTheme.colorScheme.primary
                                                    else -> MaterialTheme.colorScheme.outline
                                                }
                                            )
                                    )
                                    Text(
                                        text = if (currentRoute == BgoomRoute.HOME) {
                                            activeDevice?.name ?: "添加电脑设备"
                                        } else {
                                            "${currentRoute.title} · ${activeDevice?.name ?: "离线"}"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Icon(
                                        Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = "切换设备",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = deviceMenuExpanded,
                                onDismissRequest = { deviceMenuExpanded = false },
                            ) {
                                devices.forEach { dev ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(dev.name, style = MaterialTheme.typography.bodyLarge)
                                                Text(
                                                    "${dev.username}@${dev.host}:${dev.port}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            app.deviceStore.setActiveDevice(dev.id)
                                            deviceMenuExpanded = false
                                            showActionToast("已切换到 ${dev.name}")
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("管理 / 添加电脑设备…", color = MaterialTheme.colorScheme.primary) },
                                    onClick = {
                                        deviceMenuExpanded = false
                                        currentRoute = BgoomRoute.DEVICES
                                    },
                                )
                            }
                        }

                        IconButton(onClick = { currentRoute = BgoomRoute.SETTINGS }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "设置")
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    when (currentRoute) {
                        BgoomRoute.HOME -> HomeHubScreen(
                            activeDevice = activeDevice,
                            notifyDesktop = settings.notifyDesktopOnSend,
                            availableUpdate = availableUpdate,
                            db = app.database,
                            sshRepo = app.sshRepo,
                            onNavigate = { currentRoute = it },
                            onOpenUpdateDialog = { showUpdateDialog = true },
                            onShowActionToast = ::showActionToast,
                        )

                        BgoomRoute.SCANNER -> ScannerScreen(
                            activeDevice = activeDevice,
                            notifyDesktop = settings.notifyDesktopOnSend,
                            sshRepo = app.sshRepo,
                            initialBitmap = pendingScanBitmap,
                            onConsumeInitialBitmap = { pendingScanBitmap = null },
                            onSendToOcr = { bmp ->
                                pendingOcrBitmap = bmp
                                currentRoute = BgoomRoute.OCR_TYPST
                            },
                            onShowActionToast = ::showActionToast,
                        )

                        BgoomRoute.OCR_TYPST -> OcrTypstScreen(
                            deviceStore = app.deviceStore,
                            db = app.database,
                            activeDevice = activeDevice,
                            sshRepo = app.sshRepo,
                            localOcrEngine = app.localOcrEngine,
                            localFormulaEngine = app.localFormulaEngine,
                            remoteVlmClient = app.remoteVlmClient,
                            typstCompiler = app.typstCompiler,
                            incomingBitmap = pendingOcrBitmap,
                            onConsumeIncomingBitmap = { pendingOcrBitmap = null },
                            onShowActionToast = ::showActionToast,
                        )

                        BgoomRoute.ID_PHOTO -> IdPhotoScreen(
                            mattingEngine = app.mattingEngine,
                            activeDevice = activeDevice,
                            notifyDesktop = settings.notifyDesktopOnSend,
                            sshRepo = app.sshRepo,
                            initialBitmap = pendingIdPhotoBitmap,
                            onConsumeInitialBitmap = { pendingIdPhotoBitmap = null },
                            onShowActionToast = ::showActionToast,
                        )

                        BgoomRoute.TRANSFER -> TransferHubScreen(
                            devices = devices,
                            activeDevice = activeDevice,
                            notifyDesktop = settings.notifyDesktopOnSend,
                            db = app.database,
                            sshRepo = app.sshRepo,
                            onGoDevices = { currentRoute = BgoomRoute.DEVICES },
                            onShowActionToast = ::showActionToast,
                        )

                        BgoomRoute.NOTES -> NotesScreen(
                            db = app.database,
                            activeDevice = activeDevice,
                            sshRepo = app.sshRepo,
                            onShowToast = { showActionToast(it) },
                        )

                        BgoomRoute.DEVICES -> DevicesScreen(
                            deviceStore = app.deviceStore,
                            sshRepo = app.sshRepo,
                            sshPool = app.sshPool,
                            onShowToast = { showActionToast(it) },
                        )

                        BgoomRoute.SETTINGS -> SettingsScreen(
                            deviceStore = app.deviceStore,
                            onUpdateFound = { info ->
                                availableUpdate = info
                                downloadedApkFile = null
                                updateStatusMsg = ""
                                showUpdateDialog = true
                            },
                            onShowToast = { showActionToast(it) },
                        )
                    }
                }
            }
        }

        // System Share Sheet Receiver (Honor Gallery / Downloads / Files / WeChat -> BGOOM)
        if (incomingShare != null) {
            var chosenDeviceId by remember(incomingShare, activeDevice?.id) {
                mutableStateOf(activeDevice?.id ?: devices.firstOrNull()?.id)
            }
            val chosenDevice = devices.firstOrNull { it.id == chosenDeviceId } ?: activeDevice

            ModalBottomSheet(
                onDismissRequest = onClearIncomingShare,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("分享到大爆", style = MaterialTheme.typography.headlineMedium)

                    if (devices.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            devices.forEach { dev ->
                                FilterChip(
                                    selected = chosenDevice?.id == dev.id,
                                    onClick = {
                                        chosenDeviceId = dev.id
                                        app.deviceStore.setActiveDevice(dev.id)
                                    },
                                    label = { Text(dev.name) },
                                )
                            }
                        }
                    }

                    val singleUri = incomingShare.uris.singleOrNull()
                    val isImage = singleUri != null && (
                        incomingShare.mimeType?.startsWith("image/") == true ||
                            AndroidFileHelper.guessMimeType(AndroidFileHelper.queryDisplayName(context, singleUri)).startsWith("image/")
                        )

                    if (!incomingShare.text.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                incomingShare.text.take(220),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val txt = incomingShare.text
                                    val note = NoteItem(title = txt.lines().first().take(28), content = txt)
                                    app.database.upsertNote(note)
                                    onClearIncomingShare()
                                    if (chosenDevice != null) {
                                        scope.launch {
                                            app.sshRepo.syncNoteToDevice(chosenDevice, note, notifyDesktop = settings.notifyDesktopOnSend)
                                                .onSuccess { ms -> showActionToast("已存入便签并同步到 ${chosenDevice.name} (${ms}ms)") }
                                                .onFailure { showActionToast("已存本地便签，同步失败：${it.message}") }
                                        }
                                    } else {
                                        showActionToast("已存入本地便签")
                                    }
                                },
                                shape = RoundedCornerShape(22.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("存便签并同步") }

                            if (chosenDevice != null) {
                                FilledTonalButton(
                                    onClick = {
                                        val txt = incomingShare.text
                                        onClearIncomingShare()
                                        scope.launch {
                                            app.sshRepo.pushClipboard(chosenDevice, txt, notifyDesktop = settings.notifyDesktopOnSend)
                                                .onSuccess { ms -> showActionToast("已送到 ${chosenDevice.name} 剪贴板 (${ms}ms)") }
                                                .onFailure { showActionToast(it.message ?: "推送失败") }
                                        }
                                    },
                                    shape = RoundedCornerShape(22.dp),
                                    modifier = Modifier.weight(1f),
                                ) { Text("发到电脑剪贴板") }
                            }
                        }
                    }

                    if (incomingShare.uris.isNotEmpty()) {
                        val previewNames = incomingShare.uris.take(3).joinToString("、") {
                            AndroidFileHelper.queryDisplayName(context, it)
                        }
                        Text(
                            "共 ${incomingShare.uris.size} 个文件：$previewNames",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Button(
                            onClick = {
                                val uris = incomingShare.uris
                                onClearIncomingShare()
                                if (chosenDevice == null) {
                                    showActionToast("请先添加一台电脑设备")
                                    currentRoute = BgoomRoute.DEVICES
                                    return@Button
                                }
                                scope.launch {
                                    var sent = 0
                                    for (u in uris) {
                                        val f = withContext(Dispatchers.IO) { AndroidFileHelper.copyUriToCacheFile(context, u) }
                                            ?: continue
                                        app.sshRepo.uploadFile(
                                            device = chosenDevice,
                                            localFile = f,
                                            localUri = u.toString(),
                                            notifyDesktop = settings.notifyDesktopOnSend,
                                            onProgress = { p ->
                                                val pct = (p * 100).toInt()
                                                BgoomNotifier.showTransferCapsule(
                                                    context = context,
                                                    title = "正在发送 · ${f.name}",
                                                    subtitle = "发往 ${chosenDevice.name} ($pct%)",
                                                    shortChip = "发送 $pct%",
                                                    progressPercent = pct,
                                                )
                                            }
                                        ).onSuccess { sent++ }
                                        runCatching { f.delete() }
                                    }
                                    BgoomNotifier.cancelTransferCapsule(context)
                                    showActionToast("已加密发送 $sent/${uris.size} 个文件给 ${chosenDevice.name}")
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text(if (chosenDevice != null) "直接发送到 ${chosenDevice.name}" else "前往添加电脑设备")
                        }

                        if (isImage) {
                            val imgUri = singleUri!!
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        onClearIncomingShare()
                                        scope.launch {
                                            val bmp = withContext(Dispatchers.IO) { BitmapIO.loadBitmapOriented(context, imgUri, 2400) }
                                            if (bmp != null) {
                                                pendingScanBitmap = bmp
                                                currentRoute = BgoomRoute.SCANNER
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(22.dp),
                                    modifier = Modifier.weight(1f),
                                ) { Text("文档扫描") }

                                OutlinedButton(
                                    onClick = {
                                        onClearIncomingShare()
                                        scope.launch {
                                            val bmp = withContext(Dispatchers.IO) { BitmapIO.loadBitmapOriented(context, imgUri, 2000) }
                                            if (bmp != null) {
                                                pendingOcrBitmap = bmp
                                                currentRoute = BgoomRoute.OCR_TYPST
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(22.dp),
                                    modifier = Modifier.weight(1f),
                                ) { Text("OCR 识别") }

                                OutlinedButton(
                                    onClick = {
                                        onClearIncomingShare()
                                        scope.launch {
                                            val bmp = withContext(Dispatchers.IO) { BitmapIO.loadBitmapOriented(context, imgUri, 1800) }
                                            if (bmp != null) {
                                                pendingIdPhotoBitmap = bmp
                                                currentRoute = BgoomRoute.ID_PHOTO
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(22.dp),
                                    modifier = Modifier.weight(1f),
                                ) { Text("证件照") }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }

        // In-App GitHub Releases Auto-Update & Native APK Installer Dialog
        val updateInfo = availableUpdate
        if (showUpdateDialog && updateInfo != null) {
            AlertDialog(
                onDismissRequest = { if (!downloadingUpdate) showUpdateDialog = false },
                title = { Text("发现新版本 ${updateInfo.tagName}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "当前 v${BuildConfig.VERSION_NAME} → 最新 v${updateInfo.versionName}" +
                                if (updateInfo.apkSizeBytes > 0) " (${AndroidFileHelper.formatBytes(updateInfo.apkSizeBytes)})" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (updateInfo.releaseNotes.isNotBlank()) {
                            Text(
                                updateInfo.releaseNotes.take(400),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (downloadingUpdate) {
                            LinearProgressIndicator(
                                progress = { updateProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "正在下载安装包 ${(updateProgress * 100).toInt()}%…",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (updateStatusMsg.isNotBlank()) {
                            Text(updateStatusMsg, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                confirmButton = {
                    val readyApk = downloadedApkFile
                    if (readyApk != null && readyApk.exists()) {
                        Button(
                            onClick = {
                                val launched = GitHubUpdater.launchInstallerOrRequestPermission(context, readyApk)
                                if (!launched) {
                                    updateStatusMsg = "请先在系统设置中开启「允许来自此来源的应用」，返回后再次点击安装。"
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                        ) { Text("调起系统安装器") }
                    } else {
                        Button(
                            enabled = !downloadingUpdate && updateInfo.apkDownloadUrl.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    downloadingUpdate = true
                                    updateProgress = 0f
                                    updateStatusMsg = ""
                                    GitHubUpdater.downloadApk(
                                        context = context,
                                        info = updateInfo,
                                        mirrorPrefix = settings.githubMirrorPrefix,
                                        onProgress = { p -> updateProgress = p }
                                    ).onSuccess { apk ->
                                        downloadedApkFile = apk
                                        val launched = GitHubUpdater.launchInstallerOrRequestPermission(context, apk)
                                        updateStatusMsg = if (launched) {
                                            "下载完成，已调起系统安装器"
                                        } else {
                                            "下载完成！请在系统设置允许大爆安装应用后，返回点击「调起系统安装器」。"
                                        }
                                    }.onFailure { err ->
                                        updateStatusMsg = "下载失败：${err.message}（可在设置中填入 GitHub 加速前缀）"
                                    }
                                    downloadingUpdate = false
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                        ) { Text(if (downloadingUpdate) "下载中…" else "立即下载并安装") }
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !downloadingUpdate,
                        onClick = { showUpdateDialog = false }
                    ) { Text("稍后再说") }
                },
            )
        }
    }
}
