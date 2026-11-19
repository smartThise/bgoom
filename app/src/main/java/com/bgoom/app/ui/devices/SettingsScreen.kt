package com.bgoom.app.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bgoom.app.BuildConfig
import com.bgoom.app.data.DeviceStore
import com.bgoom.app.data.ThemeMode
import com.bgoom.app.data.TypstCompileMode
import com.bgoom.app.update.GitHubUpdater
import com.bgoom.app.update.ReleaseUpdateInfo
import com.bgoom.app.util.BatteryOptimizationHelper
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    deviceStore: DeviceStore,
    onUpdateFound: (ReleaseUpdateInfo) -> Unit,
    onShowToast: (String) -> Unit,
) {
    val settings by deviceStore.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember(settings.visionApiBaseUrl) { mutableStateOf(settings.visionApiBaseUrl) }
    var modelName by remember(settings.visionApiModel) { mutableStateOf(settings.visionApiModel) }
    var apiKey by remember { mutableStateOf(deviceStore.getVisionApiKey()) }
    var sshOcrCmd by remember(settings.sshOcrCommandTemplate) { mutableStateOf(settings.sshOcrCommandTemplate) }
    var githubRepo by remember(settings.githubRepo) { mutableStateOf(settings.githubRepo) }
    var githubMirror by remember(settings.githubMirrorPrefix) { mutableStateOf(settings.githubMirrorPrefix) }
    var checkingUpdate by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Software Update Card (GitHub Releases)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("版本更新（GitHub Releases）", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "当前版本 v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                checkingUpdate = true
                                GitHubUpdater.checkLatestRelease(
                                    repo = githubRepo.trim(),
                                    mirrorPrefix = githubMirror.trim(),
                                )
                                    .onSuccess { info ->
                                        if (info.isNewer) {
                                            onUpdateFound(info)
                                        } else {
                                            onShowToast("当前已是最新版本 (v${BuildConfig.VERSION_NAME})")
                                        }
                                    }
                                    .onFailure { onShowToast(it.message ?: "检查更新失败") }
                                checkingUpdate = false
                            }
                        },
                        enabled = !checkingUpdate,
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        if (checkingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (checkingUpdate) "检查中…" else "检查更新")
                    }
                }
                OutlinedTextField(
                    value = githubRepo,
                    onValueChange = { githubRepo = it },
                    label = { Text("GitHub 仓库（owner/repo）") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = githubMirror,
                    onValueChange = { githubMirror = it },
                    label = { Text("GitHub 加速前缀（可选，国内直连检查与下载慢时填写）") },
                    placeholder = { Text("例如 https://ghfast.top/") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Notification & Sync preferences
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("双向通知与协作", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("发送后弹出电脑通知", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "手机发文件/便签后自动在 Mac/Linux/Windows 弹出系统通知",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.notifyDesktopOnSend,
                        onCheckedChange = { checked ->
                            deviceStore.updateSettings { it.copy(notifyDesktopOnSend = checked) }
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("收到电脑文字自动复制剪贴板", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "电脑右键发来文字便签时，同步写入手机剪贴板方便直接粘贴",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.autoCopyIncomingText,
                        onCheckedChange = { checked ->
                            deviceStore.updateSettings { it.copy(autoCopyIncomingText = checked) }
                        },
                    )
                }
                val batteryOk = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("后台常驻防休眠白名单", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (batteryOk) "已允许忽略电池优化（配合系统「应用启动管理→手动管理」更稳）"
                            else "未加入白名单：荣耀等系统息屏后可能断开后台收件",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = { BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context) },
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(if (batteryOk) "已开启" else "去开启")
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("外观主题", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { deviceStore.updateSettings { it.copy(themeMode = mode) } },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("默认 Typst 编译引擎", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypstCompileMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.defaultTypstMode == mode,
                            onClick = { deviceStore.updateSettings { it.copy(defaultTypstMode = mode) } },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Text(
                    "本地离线内置 Rust Typst 0.14.2 + Tylax AST + 系统 CJK 字体；SSH 远端可复用电脑全部字体与宏包。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("远端视觉 API（复杂手写 / 整页排版 OCR）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "支持 OpenAI 兼容接口（Gemini / Qwen-VL / MiniCPM / OXR / 本地 LM Studio）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("模型名称") },
                    placeholder = { Text("gpt-4o-mini / qwen2.5-vl / oxr") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（Keystore 加密保存）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SSH 远端 OCR 命令模板", style = MaterialTheme.typography.titleMedium)
                Text(
                    "{IMAGE} 替换为远端图片路径，{FORMAT} 替换为 typ / md / tex，stdout 作为识别结果。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = sshOcrCmd,
                    onValueChange = { sshOcrCmd = it },
                    label = { Text("远端执行命令") },
                    minLines = 2,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Button(
            onClick = {
                deviceStore.updateSettings {
                    it.copy(
                        visionApiBaseUrl = baseUrl.trim(),
                        visionApiModel = modelName.trim(),
                        sshOcrCommandTemplate = sshOcrCmd.trim(),
                        githubRepo = githubRepo.trim().ifBlank { "smartThise/bgoom" },
                        githubMirrorPrefix = githubMirror.trim(),
                    )
                }
                deviceStore.setVisionApiKey(apiKey)
                onShowToast("设置已加密保存")
            },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("保存设置")
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("离线引擎清单（无需联网 · 无 GMS 依赖）", style = MaterialTheme.typography.labelLarge)
                Text("• 文档扫描：纯 C++17 NDK（四角矫正 + 去影增白 / Sauvola 黑白）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• 证件照：Hivision MODNet + C++ 导向滤波发丝精修 + 去溢色 + 人脸定位", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• 离线文字 OCR：PP-OCRv5 Mobile（18,383 字含手写强化）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• 离线公式 OCR：Texo 20M INT8 + Tylax 0.3.8 AST 转译", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• 离线排版：Rust Typst 0.14.2（直接调用系统字体出 PDF）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
