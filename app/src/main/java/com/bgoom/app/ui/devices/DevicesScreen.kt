package com.bgoom.app.ui.devices

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bgoom.app.data.DevicePlatform
import com.bgoom.app.data.DeviceStore
import com.bgoom.app.data.SshDevice
import com.bgoom.app.ssh.SshHubRepository
import com.bgoom.app.ssh.SshKeyManager
import com.bgoom.app.ssh.SshSessionPool
import com.bgoom.app.util.AndroidFileHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    deviceStore: DeviceStore,
    sshRepo: SshHubRepository,
    sshPool: SshSessionPool,
    onShowToast: (String) -> Unit,
) {
    val devices by deviceStore.devices.collectAsState()
    val settings by deviceStore.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var editingDevice by remember { mutableStateOf<SshDevice?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var testingDeviceId by remember { mutableStateOf<String?>(null) }
    var testMessages by remember { mutableStateOf<Map<String, Pair<Boolean, String>>>(emptyMap()) }
    var pendingDelete by remember { mutableStateOf<SshDevice?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("手机作为加密中转站", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "各设备互不直连，仅接受 SSH 私钥认证（严禁密码登录），私钥经 Android Keystore 加密存储。手机开代理时建议将目标域名设为 DIRECT。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已配置设备 (${devices.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    FilledTonalButton(
                        onClick = {
                            editingDevice = null
                            showEditor = true
                        },
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加设备")
                    }
                }
            }

            if (devices.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Outlined.Key,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("尚未添加任何 SSH 设备", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "点击右上角「添加设备」，填入 SSH 主机地址、端口与私钥即可建立加密通道。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(devices, key = { it.id }) { device ->
                val isActive = settings.activeDeviceId == device.id ||
                    (settings.activeDeviceId == null && devices.firstOrNull()?.id == device.id)
                val isTesting = testingDeviceId == device.id
                val testResult = testMessages[device.id]

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        deviceStore.setActiveDevice(device.id)
                        onShowToast("已切换到 ${device.name}")
                    },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                                    if (isActive) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            Icons.Outlined.CheckCircle,
                                            contentDescription = "当前设备",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(
                                    "${device.username}@${device.host}:${device.port} · ${device.platform.label}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (device.trustedHostFingerprint != null) {
                                    Text(
                                        "主机指纹 ${device.trustedHostFingerprint.take(28)}…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                            IconButton(onClick = {
                                editingDevice = device
                                showEditor = true
                            }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                            }
                            IconButton(onClick = { pendingDelete = device }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除")
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        testingDeviceId = device.id
                                        val res = sshRepo.testConnection(device)
                                        testMessages = testMessages + (device.id to (res.success to res.diagnosticMessage))
                                        testingDeviceId = null
                                    }
                                },
                                enabled = !isTesting,
                                shape = RoundedCornerShape(20.dp),
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(if (isTesting) "测试中…" else "测试连通")
                            }
                            if (!isActive) {
                                TextButton(onClick = {
                                    deviceStore.setActiveDevice(device.id)
                                    onShowToast("已设为当前设备：${device.name}")
                                }) {
                                    Text("设为当前")
                                }
                            }
                        }

                        if (testResult != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = testResult.second,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (testResult.first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showEditor) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEditor = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DeviceEditorSheet(
                initial = editingDevice,
                deviceStore = deviceStore,
                onSaved = { saved ->
                    sshPool.disconnect(saved.id)
                    showEditor = false
                    onShowToast("已保存设备「${saved.name}」")
                },
                onDismiss = { showEditor = false },
                onShowToast = onShowToast,
            )
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除设备「${target.name}」？") },
            text = { Text("将同时从加密存储中永久擦除该设备对应的私钥和口令。") },
            confirmButton = {
                TextButton(onClick = {
                    sshPool.disconnect(target.id)
                    deviceStore.deleteDevice(target.id)
                    pendingDelete = null
                    onShowToast("已删除 ${target.name}")
                }) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DeviceEditorSheet(
    initial: SshDevice?,
    deviceStore: DeviceStore,
    onSaved: (SshDevice) -> Unit,
    onDismiss: () -> Unit,
    onShowToast: (String) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var portText by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var inboxDir by remember { mutableStateOf(initial?.remoteInboxDir ?: "~/BgoomDrop") }
    var notesDir by remember { mutableStateOf(initial?.remoteNotesDir ?: "~/BgoomDrop/notes") }
    var platform by remember { mutableStateOf(initial?.platform ?: DevicePlatform.MACOS) }
    var privateKey by remember { mutableStateOf(initial?.let { deviceStore.getPrivateKey(it.id) } ?: "") }
    var keyPassphrase by remember { mutableStateOf(initial?.let { deviceStore.getPassphrase(it.id) } ?: "") }
    var generatedPubKey by remember { mutableStateOf(initial?.publicKeyPreview ?: "") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val keyFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val txt = AndroidFileHelper.readUriAsText(context, uri)
            if (!txt.isNullOrBlank()) {
                privateKey = txt
                errorMsg = null
                onShowToast("已载入私钥文件")
            } else {
                errorMsg = "无法读取所选私钥文件"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (initial == null) "添加 SSH 设备（仅私钥认证）" else "编辑 SSH 设备",
            style = MaterialTheme.typography.headlineMedium,
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("设备名称") },
            placeholder = { Text("例如 我的 Mac / 工作室主机 / NAS") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("SSH 主机 / 域名") },
                placeholder = { Text("host.example.com") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(2.2f),
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                label = { Text("端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("SSH 用户名") },
            placeholder = { Text("alice") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("目标系统（用于剪贴板与 Typst 路径适配）", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DevicePlatform.entries.forEach { p ->
                FilterChip(
                    selected = platform == p,
                    onClick = { platform = p },
                    label = { Text(p.label, maxLines = 1) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = inboxDir,
                onValueChange = { inboxDir = it },
                label = { Text("文件收件目录") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = notesDir,
                onValueChange = { notesDir = it },
                label = { Text("便签同步目录") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { keyFilePicker.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入私钥文件")
            }
            OutlinedButton(
                onClick = {
                    val generated = SshKeyManager.generateEd25519KeyPair("bgoom@${name.ifBlank { "android" }}")
                    privateKey = generated.privateKeyOpenSsh
                    keyPassphrase = ""
                    generatedPubKey = generated.publicKeyAuthorizedLine
                    AndroidFileHelper.copyToClipboard(context, "BGOOM SSH Public Key", generated.publicKeyAuthorizedLine)
                    errorMsg = null
                    onShowToast("已生成独立 Ed25519 密钥对，公钥已复制，请粘贴到目标设备 ~/.ssh/authorized_keys")
                },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("生成 Ed25519")
            }
        }

        if (generatedPubKey.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("公钥（添加到远端 authorized_keys）", style = MaterialTheme.typography.labelMedium)
                        Text(
                            generatedPubKey,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        AndroidFileHelper.copyToClipboard(context, "SSH Public Key", generatedPubKey)
                        onShowToast("公钥已复制")
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制公钥")
                    }
                }
            }
        }

        OutlinedTextField(
            value = privateKey,
            onValueChange = { privateKey = it; errorMsg = null },
            label = { Text("SSH 私钥内容（必填，严禁密码登录）") },
            placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...") },
            minLines = 4,
            maxLines = 6,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = keyPassphrase,
            onValueChange = { keyPassphrase = it; errorMsg = null },
            label = { Text("私钥解密口令（可选，仅用于加密私钥）") },
            placeholder = { Text("无口令私钥留空") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        if (initial?.trustedHostFingerprint != null) {
            TextButton(onClick = {
                deviceStore.updateTrustedHostFingerprint(initial.id, null)
                onShowToast("已清除旧主机指纹，下次连接将重新校验")
            }) {
                Text("重置已信任的主机指纹 (TOFU)")
            }
        }

        if (errorMsg != null) {
            Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(
                shape = RoundedCornerShape(24.dp),
                onClick = {
                    val cleanName = name.trim()
                    val cleanHost = host.trim()
                    val cleanUser = username.trim()
                    val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 22
                    if (cleanName.isEmpty() || cleanHost.isEmpty() || cleanUser.isEmpty()) {
                        errorMsg = "请完整填写设备名称、主机地址与用户名"
                        return@Button
                    }
                    val validation = SshKeyManager.validatePrivateKey(privateKey, keyPassphrase.ifEmpty { null })
                    validation.onFailure {
                        errorMsg = "私钥校验失败：${it.message}"
                        return@Button
                    }.onSuccess { info ->
                        val device = (initial ?: SshDevice(name = cleanName, host = cleanHost, username = cleanUser)).copy(
                            name = cleanName,
                            host = cleanHost,
                            port = port,
                            username = cleanUser,
                            remoteInboxDir = inboxDir.trim().ifBlank { "~/BgoomDrop" },
                            remoteNotesDir = notesDir.trim().ifBlank { "~/BgoomDrop/notes" },
                            platform = platform,
                            hasPassphrase = keyPassphrase.isNotEmpty(),
                            keyFingerprint = info.fingerprintSha256,
                            publicKeyPreview = generatedPubKey,
                        )
                        deviceStore.upsertDevice(device, privateKey, keyPassphrase)
                        onSaved(device)
                    }
                },
            ) {
                Text("校验并保存")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
