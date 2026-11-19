package com.bgoom.app.ui.idphoto

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bgoom.app.data.SshDevice
import com.bgoom.app.idphoto.IdPhotoCatalog
import com.bgoom.app.idphoto.IdPhotoComposer
import com.bgoom.app.idphoto.MattingResult
import com.bgoom.app.idphoto.PortraitMattingEngine
import com.bgoom.app.scanner.BitmapIO
import com.bgoom.app.ssh.SshHubRepository
import com.bgoom.app.util.AndroidFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun IdPhotoScreen(
    mattingEngine: PortraitMattingEngine,
    activeDevice: SshDevice?,
    notifyDesktop: Boolean,
    sshRepo: SshHubRepository,
    initialBitmap: Bitmap? = null,
    onConsumeInitialBitmap: () -> Unit = {},
    onShowActionToast: (message: String, actionLabel: String?, onAction: (() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourcePortrait by remember { mutableStateOf<Bitmap?>(null) }
    var matting by remember { mutableStateOf<MattingResult?>(null) }
    var studioLighting by remember { mutableStateOf(true) }
    var selectedSpec by remember { mutableStateOf(IdPhotoCatalog.specs.first()) }
    var selectedColor by remember { mutableStateOf(IdPhotoCatalog.colors[1]) } // Default 证件蓝
    var userScale by remember { mutableFloatStateOf(1.0f) }
    var userOffsetX by remember { mutableFloatStateOf(0.0f) }
    var userOffsetY by remember { mutableFloatStateOf(0.0f) }
    var printSheetMode by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusLabel by remember { mutableStateOf("") }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    fun processPortrait(bmp: Bitmap, polish: Boolean) {
        scope.launch {
            busy = true
            statusLabel = "正在精修发丝抠图与人脸构图…"
            runCatching { mattingEngine.extractForeground(bmp, studioLighting = polish) }
                .onSuccess { res ->
                    sourcePortrait = bmp
                    matting = res
                    userScale = 1.0f
                    userOffsetX = 0.0f
                    userOffsetY = 0.0f
                    val faceHint = if (res.face != null) "已自动锁定人脸居中" else "已自动按轮廓居中"
                    statusLabel = "$faceHint · ${res.elapsedMs}ms"
                }
                .onFailure { err ->
                    onShowActionToast("处理失败：${err.message}", null, null)
                    statusLabel = ""
                }
            busy = false
        }
    }

    LaunchedEffect(initialBitmap) {
        if (initialBitmap != null) {
            onConsumeInitialBitmap()
            processPortrait(initialBitmap, studioLighting)
        }
    }

    LaunchedEffect(matting, selectedSpec, selectedColor, userScale, userOffsetX, userOffsetY, printSheetMode) {
        val m = matting ?: return@LaunchedEffect
        previewBitmap = withContext(Dispatchers.Default) {
            val single = IdPhotoComposer.composeIdPhoto(
                matting = m,
                spec = selectedSpec,
                bgColor = selectedColor,
                userScale = userScale,
                userOffsetX = userOffsetX,
                userOffsetY = userOffsetY,
            )
            if (printSheetMode) {
                val sheet = IdPhotoComposer.composeSixInchPrintSheet(single)
                single.recycle()
                sheet
            } else {
                single
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { BitmapIO.loadBitmapOriented(context, uri, 1800) }
                if (bmp != null) processPortrait(bmp, studioLighting)
                else onShowActionToast("无法读取人像照片", null, null)
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        if (success && file != null && file.exists()) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { BitmapIO.loadFileOriented(file, 1800) }
                runCatching { file.delete() }
                if (bmp != null) processPortrait(bmp, studioLighting)
                else onShowActionToast("拍照读取失败", null, null)
            }
        }
    }

    fun launchPortraitCamera() {
        val file = File(context.cacheDir, "id_cam_${System.currentTimeMillis()}.jpg")
        pendingCameraFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraLauncher.launch(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Preview Canvas with direct touch pan & pinch zoom
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(330.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(matting, printSheetMode) {
                        if (matting == null || printSheetMode) return@pointerInput
                        detectTransformGestures { _, pan, zoom, _ ->
                            userScale = (userScale * zoom).coerceIn(0.65f, 1.85f)
                            userOffsetX = (userOffsetX + pan.x / 450f).coerceIn(-0.4f, 0.4f)
                            userOffsetY = (userOffsetY + pan.y / 550f).coerceIn(-0.4f, 0.4f)
                        }
                    }
            ) {
                val bmp = previewBitmap
                when {
                    busy -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(strokeWidth = 2.6.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(statusLabel, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    bmp != null -> {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "证件照预览",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(18.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }

                    else -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Badge,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("证件照工作室", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "纯离线发丝精修抠图、智能人脸居中与自然打光，支持多尺寸与 6 寸冲印排版。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(18.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { photoPicker.launch("image/*") },
                                    shape = RoundedCornerShape(24.dp),
                                ) {
                                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("选相册人像")
                                }
                                FilledTonalButton(
                                    onClick = { launchPortraitCamera() },
                                    shape = RoundedCornerShape(24.dp),
                                ) {
                                    Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("现场拍摄")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (matting != null && !busy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${selectedSpec.name} · ${selectedSpec.subtitle}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "$statusLabel（可直接在图上双指缩放/拖动）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { photoPicker.launch("image/*") }) { Text("换图") }
                    TextButton(onClick = { launchPortraitCamera() }) { Text("重拍") }
                }
            }
        }

        // Background color swatches
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("底色", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IdPhotoCatalog.colors.forEach { bg ->
                    val active = selectedColor.id == bg.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { selectedColor = bg },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(bg.topColorArgb), Color(bg.bottomColorArgb))
                                    )
                                )
                                .border(
                                    width = if (active) 2.5.dp else 1.dp,
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            bg.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Size specs
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("尺寸规格（300 DPI 标准）", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IdPhotoCatalog.specs.forEach { spec ->
                    FilterChip(
                        selected = selectedSpec.id == spec.id,
                        onClick = { selectedSpec = spec },
                        label = { Text(spec.name) },
                    )
                }
            }
        }

        // Studio Lighting + Print Sheet + Framing Controls
        if (matting != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = studioLighting,
                            onClick = {
                                val next = !studioLighting
                                studioLighting = next
                                sourcePortrait?.let { processPortrait(it, next) }
                            },
                            leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("自然提亮") },
                        )
                        FilterChip(
                            selected = printSheetMode,
                            onClick = { printSheetMode = !printSheetMode },
                            leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("6 寸排版") },
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            userScale = 1.0f
                            userOffsetX = 0.0f
                            userOffsetY = 0.0f
                        }) {
                            Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("复位")
                        }
                    }

                    Text("人像缩放 (${String.format("%.0f%%", userScale * 100)})", style = MaterialTheme.typography.labelMedium)
                    Slider(value = userScale, onValueChange = { userScale = it }, valueRange = 0.7f..1.6f)
                    Text("上下位置微调", style = MaterialTheme.typography.labelMedium)
                    Slider(value = userOffsetY, onValueChange = { userOffsetY = it }, valueRange = -0.3f..0.3f)
                }
            }
        }

        // Save to Gallery (with 1-tap Open!) & SSH Send
        val outBmp = previewBitmap
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    val b = outBmp ?: return@FilledTonalButton
                    scope.launch {
                        val tag = if (printSheetMode) "BGOOM_ID_6inch" else "BGOOM_ID_${selectedSpec.id}"
                        val res = withContext(Dispatchers.IO) {
                            AndroidFileHelper.saveBitmapToGallery(context, b, tag)
                        }
                        res.onSuccess { saved ->
                            onShowActionToast(
                                "已保存到 ${saved.displayLocation}",
                                "立即查看",
                            ) {
                                AndroidFileHelper.openFile(context, saved.uri.toString(), "", saved.mimeType)
                            }
                        }.onFailure {
                            onShowActionToast(it.message ?: "保存失败", null, null)
                        }
                    }
                },
                enabled = outBmp != null && !busy,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("存相册")
            }

            Button(
                onClick = {
                    val b = outBmp ?: return@Button
                    val dev = activeDevice
                    if (dev == null) {
                        onShowActionToast("请先配置并选择电脑设备", null, null)
                        return@Button
                    }
                    scope.launch {
                        busy = true
                        statusLabel = "正在加密发送给 ${dev.name}…"
                        val tag = if (printSheetMode) "BGOOM_ID_6inch" else "BGOOM_ID_${selectedSpec.id}"
                        val file = withContext(Dispatchers.IO) {
                            BitmapIO.saveJpeg(b, File(context.cacheDir, "idphotos/${tag}_${System.currentTimeMillis()}.jpg"), 96)
                        }
                        sshRepo.uploadFile(dev, file, notifyDesktop = notifyDesktop)
                            .onSuccess { onShowActionToast("证件照已加密送达 ${dev.name}", null, null) }
                            .onFailure { onShowActionToast(it.message ?: "上传失败", null, null) }
                        statusLabel = ""
                        busy = false
                    }
                },
                enabled = outBmp != null && !busy && activeDevice != null,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1.2f).height(46.dp),
            ) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (activeDevice != null) "传给 ${activeDevice.name}" else "发往电脑")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
