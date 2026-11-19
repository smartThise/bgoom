package com.bgoom.app.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bgoom.app.data.SshDevice
import com.bgoom.app.scanner.BitmapIO
import com.bgoom.app.scanner.DocFilter
import com.bgoom.app.scanner.NativeDocScanner
import com.bgoom.app.scanner.NormPoint
import com.bgoom.app.scanner.PdfExporter
import com.bgoom.app.ssh.SshHubRepository
import com.bgoom.app.util.AndroidFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot

private enum class ScannerPhase { CAMERA, CROP_QUAD, REVIEW_PAGES }

@Composable
fun ScannerScreen(
    activeDevice: SshDevice?,
    notifyDesktop: Boolean = true,
    sshRepo: SshHubRepository,
    initialBitmap: Bitmap? = null,
    onConsumeInitialBitmap: () -> Unit = {},
    onSendToOcr: (Bitmap) -> Unit,
    onShowActionToast: (message: String, actionLabel: String?, onAction: (() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }

    var phase by remember { mutableStateOf(ScannerPhase.CAMERA) }
    var rawBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var quadPoints by remember { mutableStateOf(NativeDocScanner.defaultQuad()) }
    var quadConfident by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(DocFilter.MAGIC_COLOR) }
    var busy by remember { mutableStateOf(false) }

    val scannedPages = remember { mutableStateListOf<Bitmap>() }
    var selectedPageIdx by remember { mutableIntStateOf(0) }

    fun openRawForCrop(bmp: Bitmap) {
        scope.launch {
            busy = true
            val detected = withContext(Dispatchers.Default) { NativeDocScanner.detectQuad(bmp) }
            rawBitmap = bmp
            quadPoints = detected.corners
            quadConfident = detected.confident
            phase = ScannerPhase.CROP_QUAD
            busy = false
        }
    }

    LaunchedEffect(initialBitmap) {
        if (initialBitmap != null) {
            openRawForCrop(initialBitmap)
            onConsumeInitialBitmap()
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val bmp = withContext(Dispatchers.IO) { BitmapIO.loadBitmapOriented(context, uri, 2400) }
                busy = false
                if (bmp != null) {
                    openRawForCrop(bmp)
                } else {
                    onShowActionToast("无法读取所选图片", null, null)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (phase) {
            ScannerPhase.CAMERA -> {
                CameraCaptureView(
                    hasPermission = hasCameraPermission,
                    pageCount = scannedPages.size,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onCaptured = { file ->
                        scope.launch {
                            busy = true
                            val bmp = withContext(Dispatchers.IO) { BitmapIO.loadFileOriented(file, 2400) }
                            runCatching { file.delete() }
                            busy = false
                            if (bmp != null) openRawForCrop(bmp)
                            else onShowActionToast("拍照解码失败", null, null)
                        }
                    },
                    onPickGallery = { galleryPicker.launch("image/*") },
                    onReviewPages = {
                        if (scannedPages.isNotEmpty()) {
                            selectedPageIdx = scannedPages.lastIndex
                            phase = ScannerPhase.REVIEW_PAGES
                        }
                    },
                )
            }

            ScannerPhase.CROP_QUAD -> {
                val bmp = rawBitmap
                if (bmp != null) {
                    QuadCropEditor(
                        bitmap = bmp,
                        quad = quadPoints,
                        confident = quadConfident,
                        selectedFilter = selectedFilter,
                        onQuadChanged = { quadPoints = it },
                        onFilterSelected = { selectedFilter = it },
                        onResetQuad = {
                            quadPoints = listOf(
                                NormPoint(0f, 0f), NormPoint(1f, 0f),
                                NormPoint(1f, 1f), NormPoint(0f, 1f),
                            )
                        },
                        onCancel = {
                            phase = if (scannedPages.isNotEmpty()) ScannerPhase.REVIEW_PAGES else ScannerPhase.CAMERA
                        },
                        onConfirm = {
                            scope.launch {
                                busy = true
                                val rectified = withContext(Dispatchers.Default) {
                                    NativeDocScanner.warpAndFilter(bmp, quadPoints, selectedFilter, maxSide = 2200)
                                }
                                scannedPages.add(rectified)
                                selectedPageIdx = scannedPages.lastIndex
                                phase = ScannerPhase.REVIEW_PAGES
                                busy = false
                            }
                        },
                    )
                }
            }

            ScannerPhase.REVIEW_PAGES -> {
                if (scannedPages.isEmpty()) {
                    phase = ScannerPhase.CAMERA
                } else {
                    val safeIdx = selectedPageIdx.coerceIn(0, scannedPages.lastIndex)
                    val currentBmp = scannedPages[safeIdx]
                    ScanPagesReview(
                        pages = scannedPages,
                        selectedIdx = safeIdx,
                        activeDevice = activeDevice,
                        busy = busy,
                        onSelectPage = { selectedPageIdx = it },
                        onDeletePage = { idx ->
                            scannedPages.removeAt(idx)
                            if (scannedPages.isEmpty()) phase = ScannerPhase.CAMERA
                            else selectedPageIdx = idx.coerceAtMost(scannedPages.lastIndex)
                        },
                        onAddMore = { phase = ScannerPhase.CAMERA },
                        onApplyFilterToCurrent = { filter ->
                            val origRaw = rawBitmap
                            scope.launch {
                                busy = true
                                val updated = withContext(Dispatchers.Default) {
                                    if (origRaw != null && safeIdx == scannedPages.lastIndex) {
                                        NativeDocScanner.warpAndFilter(origRaw, quadPoints, filter, maxSide = 2200)
                                    } else {
                                        NativeDocScanner.applyFilterCopy(currentBmp, filter)
                                    }
                                }
                                selectedFilter = filter
                                scannedPages[safeIdx] = updated
                                busy = false
                            }
                        },
                        selectedFilter = selectedFilter,
                        onSendCurrentToOcr = { onSendToOcr(currentBmp) },
                        onSaveImage = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    AndroidFileHelper.saveBitmapToGallery(context, currentBmp, "BGOOM_Scan")
                                }
                                res.onSuccess { saved ->
                                    onShowActionToast("已存至 ${saved.displayLocation}", "立即查看") {
                                        AndroidFileHelper.openFile(context, saved.uri.toString(), "", saved.mimeType)
                                    }
                                }.onFailure { onShowActionToast(it.message ?: "保存失败", null, null) }
                            }
                        },
                        onExportPdf = { pushViaSsh ->
                            scope.launch {
                                busy = true
                                val pdfRes = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val name = "BGOOM_Scan_${System.currentTimeMillis()}.pdf"
                                        val tmp = File(context.cacheDir, "scans/$name")
                                        PdfExporter.exportBitmapsToPdf(scannedPages.toList(), tmp)
                                    }
                                }
                                pdfRes.onSuccess { pdfFile ->
                                    if (pushViaSsh) {
                                        val dev = activeDevice
                                        if (dev == null) {
                                            onShowActionToast("请先添加电脑设备", null, null)
                                        } else {
                                            sshRepo.uploadFile(dev, pdfFile, notifyDesktop = notifyDesktop)
                                                .onSuccess { onShowActionToast("PDF 已加密送达 ${dev.name}", null, null) }
                                                .onFailure { onShowActionToast(it.message ?: "上传失败", null, null) }
                                        }
                                    } else {
                                        val saved = withContext(Dispatchers.IO) {
                                            AndroidFileHelper.savePdfToDownloads(context, pdfFile)
                                        }
                                        saved.onSuccess { pub ->
                                            onShowActionToast("已存至 ${pub.displayLocation}", "立即打开") {
                                                AndroidFileHelper.openFile(context, pub.uri.toString(), "", pub.mimeType)
                                            }
                                        }.onFailure { onShowActionToast(it.message ?: "保存 PDF 失败", null, null) }
                                    }
                                }.onFailure {
                                    onShowActionToast(it.message ?: "生成 PDF 失败", null, null)
                                }
                                busy = false
                            }
                        },
                    )
                }
            }
        }

        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.4.dp)
                        Text("正在处理文档…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraCaptureView(
    hasPermission: Boolean,
    pageCount: Int,
    onRequestPermission: () -> Unit,
    onCaptured: (File) -> Unit,
    onPickGallery: () -> Unit,
    onReviewPages: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!hasPermission) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.DocumentScanner,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("极简离线文档扫描", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "纯本地 C++ 畸变校正 + 去阴影白纸增强 / Sauvola 黑白，零 GMS 依赖。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onRequestPermission, shape = RoundedCornerShape(24.dp)) {
                            Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("开启相机")
                        }
                        FilledTonalButton(onClick = onPickGallery, shape = RoundedCornerShape(24.dp)) {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("从相册导入")
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black),
            ) {
                val previewView = remember { PreviewView(context) }
                DisposableEffect(lifecycleOwner) {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    val executor = ContextCompat.getMainExecutor(context)
                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        imageCapture = capture
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture,
                            )
                        }
                    }, executor)
                    onDispose {
                        runCatching { cameraProviderFuture.get().unbindAll() }
                    }
                }

                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

                // Subtle alignment framing guide
                Canvas(modifier = Modifier.fillMaxSize().padding(28.dp)) {
                    val corner = 26.dp.toPx()
                    val stroke = Stroke(width = 2.5.dp.toPx())
                    val c = Color.White.copy(alpha = 0.72f)
                    drawLine(c, Offset(0f, 0f), Offset(corner, 0f), stroke.width)
                    drawLine(c, Offset(0f, 0f), Offset(0f, corner), stroke.width)
                    drawLine(c, Offset(size.width, 0f), Offset(size.width - corner, 0f), stroke.width)
                    drawLine(c, Offset(size.width, 0f), Offset(size.width, corner), stroke.width)
                    drawLine(c, Offset(0f, size.height), Offset(corner, size.height), stroke.width)
                    drawLine(c, Offset(0f, size.height), Offset(0f, size.height - corner), stroke.width)
                    drawLine(c, Offset(size.width, size.height), Offset(size.width - corner, size.height), stroke.width)
                    drawLine(c, Offset(size.width, size.height), Offset(size.width, size.height - corner), stroke.width)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onPickGallery, shape = RoundedCornerShape(24.dp)) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("相册")
                }

                // Minimalist Gemini shutter pill
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(68.dp),
                    onClick = {
                        val cap = imageCapture ?: return@Surface
                        val file = File(context.cacheDir, "raw_scan_${System.currentTimeMillis()}.jpg")
                        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                        cap.takePicture(
                            opts,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    onCaptured(file)
                                }

                                override fun onError(exc: ImageCaptureException) {
                                    runCatching { file.delete() }
                                }
                            }
                        )
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = "拍摄扫描",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                if (pageCount > 0) {
                    FilledTonalButton(onClick = onReviewPages, shape = RoundedCornerShape(24.dp)) {
                        Text("已扫 $pageCount 页")
                    }
                } else {
                    Spacer(Modifier.width(88.dp))
                }
            }
        }
    }
}

@Composable
private fun QuadCropEditor(
    bitmap: Bitmap,
    quad: List<NormPoint>,
    confident: Boolean,
    selectedFilter: DocFilter,
    onQuadChanged: (List<NormPoint>) -> Unit,
    onFilterSelected: (DocFilter) -> Unit,
    onResetQuad: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var activeCorner by remember { mutableIntStateOf(-1) }
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (confident) "已自动识别纸张四角（可拖动微调）" else "拖动四角对齐纸张边缘",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onResetQuad, shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Outlined.CropFree, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("全图")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat())
                    .onSizeChanged { boxSize = it }
                    .pointerInput(quad) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                if (boxSize.width <= 0 || boxSize.height <= 0) return@detectDragGestures
                                val w = boxSize.width.toFloat()
                                val h = boxSize.height.toFloat()
                                var bestIdx = -1
                                var bestDist = 96f
                                quad.forEachIndexed { idx, p ->
                                    val d = hypot(pos.x - p.x * w, pos.y - p.y * h)
                                    if (d < bestDist) {
                                        bestDist = d
                                        bestIdx = idx
                                    }
                                }
                                activeCorner = bestIdx
                            },
                            onDragEnd = { activeCorner = -1 },
                            onDragCancel = { activeCorner = -1 },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val idx = activeCorner
                                if (idx in 0..3 && boxSize.width > 0 && boxSize.height > 0) {
                                    val updated = quad.toMutableList()
                                    val cur = updated[idx]
                                    updated[idx] = NormPoint(
                                        x = (cur.x + dragAmount.x / boxSize.width).coerceIn(0f, 1f),
                                        y = (cur.y + dragAmount.y / boxSize.height).coerceIn(0f, 1f),
                                    )
                                    onQuadChanged(updated)
                                }
                            },
                        )
                    }
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "待校正文档",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val pts = quad.map { Offset(it.x * size.width, it.y * size.height) }
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        lineTo(pts[1].x, pts[1].y)
                        lineTo(pts[2].x, pts[2].y)
                        lineTo(pts[3].x, pts[3].y)
                        close()
                    }
                    drawPath(path, color = primaryColor.copy(alpha = 0.14f))
                    drawPath(path, color = primaryColor, style = Stroke(width = 2.5.dp.toPx()))
                    pts.forEach { p ->
                        drawCircle(Color.White, radius = 12.dp.toPx(), center = p)
                        drawCircle(primaryColor, radius = 9.dp.toPx(), center = p)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DocFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { onFilterSelected(filter) },
                    label = { Text(filter.label) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重拍")
            }
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1.4f).height(46.dp),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("校正并增强")
            }
        }
    }
}

@Composable
private fun ScanPagesReview(
    pages: List<Bitmap>,
    selectedIdx: Int,
    activeDevice: SshDevice?,
    busy: Boolean,
    selectedFilter: DocFilter,
    onSelectPage: (Int) -> Unit,
    onDeletePage: (Int) -> Unit,
    onAddMore: () -> Unit,
    onApplyFilterToCurrent: (DocFilter) -> Unit,
    onSendCurrentToOcr: () -> Unit,
    onSaveImage: () -> Unit,
    onExportPdf: (pushViaSsh: Boolean) -> Unit,
) {
    val current = pages[selectedIdx]
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "已扫描页面",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            ) {
                Text(
                    "${selectedIdx + 1} / ${pages.size}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // Filter switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DocFilter.entries.forEach { f ->
                FilterChip(
                    selected = selectedFilter == f,
                    onClick = { onApplyFilterToCurrent(f) },
                    label = { Text(f.label) },
                )
            }
        }

        // Multi-page thumbnail strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEachIndexed { idx, bmp ->
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            width = if (idx == selectedIdx) 2.dp else 1.dp,
                            color = if (idx == selectedIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelectPage(idx) }
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    IconButton(
                        onClick = { onDeletePage(idx) },
                        modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "删除页", tint = Color.Red, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(width = 48.dp, height = 64.dp),
                onClick = onAddMore,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AddAPhoto, contentDescription = "续扫下一页", modifier = Modifier.size(20.dp))
                }
            }
        }

        // Action pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onSendCurrentToOcr,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("送入 OCR", maxLines = 1)
            }
            OutlinedButton(
                onClick = onSaveImage,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("存图", maxLines = 1)
            }
            FilledTonalButton(
                onClick = { onExportPdf(false) },
                enabled = !busy,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("存 PDF", maxLines = 1)
            }
        }

        Button(
            onClick = { onExportPdf(true) },
            enabled = !busy && activeDevice != null,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (activeDevice != null) "合成 PDF 并加密发往 ${activeDevice.name}"
                else "配置 SSH 设备后可一键直传 PDF"
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
