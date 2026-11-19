package com.bgoom.app.ui.ocr

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bgoom.app.data.BgoomDatabase
import com.bgoom.app.data.DeviceStore
import com.bgoom.app.data.NoteItem
import com.bgoom.app.data.OcrEngineMode
import com.bgoom.app.data.OcrOutputFormat
import com.bgoom.app.data.SshDevice
import com.bgoom.app.data.TypstCompileMode
import com.bgoom.app.ocr.LocalPpOcrEngine
import com.bgoom.app.ocr.LocalTexoFormulaEngine
import com.bgoom.app.ocr.OcrFormatConverter
import com.bgoom.app.ocr.RemoteVlmOcrClient
import com.bgoom.app.scanner.BitmapIO
import com.bgoom.app.scanner.PdfExporter
import com.bgoom.app.ssh.SshHubRepository
import com.bgoom.app.typst.NativeTypstEngine
import com.bgoom.app.typst.TypstCompilerService
import com.bgoom.app.util.AndroidFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

@Composable
fun OcrTypstScreen(
    deviceStore: DeviceStore,
    db: BgoomDatabase,
    activeDevice: SshDevice?,
    sshRepo: SshHubRepository,
    localOcrEngine: LocalPpOcrEngine,
    localFormulaEngine: LocalTexoFormulaEngine,
    remoteVlmClient: RemoteVlmOcrClient,
    typstCompiler: TypstCompilerService,
    incomingBitmap: Bitmap?,
    onConsumeIncomingBitmap: () -> Unit,
    onShowActionToast: (message: String, actionLabel: String?, onAction: (() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by deviceStore.settings.collectAsState()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var engineMode by remember { mutableStateOf(settings.defaultOcrEngine) }
    var outputFormat by remember { mutableStateOf(settings.defaultOcrFormat) }
    var typstMode by remember { mutableStateOf(settings.defaultTypstMode) }

    var editorText by remember {
        mutableStateOf(
            "= 大爆 BGOOM Typst 沙盒\n\n支持离线 **PP-OCRv5** 文字、**Texo 20M** 公式识别、**Tylax AST** 亚毫秒 LaTeX↔Typst 互转与离线编译 PDF：\n\n$ integral_0^infinity e^(-x^2) dif x = sqrt(pi) / 2 $"
        )
    }
    var compiledPdfFile by remember { mutableStateOf<File?>(null) }
    var pdfPreviewPages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var statusText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun bitmapToJpegBytes(bmp: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, baos)
        return baos.toByteArray()
    }

    fun runOcr(bmp: Bitmap) {
        scope.launch {
            busy = true
            statusText = "正在运行 ${engineMode.label}…"
            val start = System.currentTimeMillis()
            val result: Result<String> = when (engineMode) {
                OcrEngineMode.LOCAL_PPOCR_V5 -> runCatching {
                    val res = localOcrEngine.recognize(bmp)
                    if (res.lines.isEmpty()) {
                        throw IllegalStateException("未检测到清晰文字，可先用文档扫描去影增白后再识别。")
                    }
                    OcrFormatConverter.assembleLines(res.lines, outputFormat)
                }

                OcrEngineMode.LOCAL_TEXO_FORMULA -> runCatching {
                    val fRes = localFormulaEngine.recognizeFormula(bmp)
                    if (fRes.rawLatex.isBlank()) {
                        throw IllegalStateException("未识别出公式内容，请尽量裁切至单条/块级公式区域。")
                    }
                    when (outputFormat) {
                        OcrOutputFormat.LATEX -> fRes.rawLatex
                        OcrOutputFormat.MARKDOWN -> "$$\n${fRes.rawLatex}\n$$"
                        OcrOutputFormat.TYPST -> withContext(Dispatchers.Default) {
                            // Deterministic sub-ms Rust Tylax AST LaTeX -> Typst conversion
                            NativeTypstEngine.latexToTypst(fRes.rawLatex, cleanStyle = true)
                        }
                    }
                }

                OcrEngineMode.VISION_API -> {
                    val bytes = withContext(Dispatchers.Default) { bitmapToJpegBytes(bmp) }
                    remoteVlmClient.recognize(
                        imageJpegBytes = bytes,
                        baseUrl = settings.visionApiBaseUrl,
                        apiKey = deviceStore.getVisionApiKey(),
                        model = settings.visionApiModel,
                        format = outputFormat,
                    )
                }

                OcrEngineMode.SSH_REMOTE -> {
                    val dev = activeDevice
                    if (dev == null) {
                        Result.failure(IllegalStateException("尚未选择 SSH 设备，请先在顶部切换设备。"))
                    } else {
                        val bytes = withContext(Dispatchers.Default) { bitmapToJpegBytes(bmp) }
                        sshRepo.runRemoteSshOcr(
                            device = dev,
                            imageJpegBytes = bytes,
                            commandTemplate = settings.sshOcrCommandTemplate,
                            formatHint = outputFormat.extension,
                        )
                    }
                }
            }

            result.onSuccess { text ->
                editorText = text
                val ms = System.currentTimeMillis() - start
                statusText = "${engineMode.label} 完成 · ${ms}ms · ${outputFormat.label}"
            }.onFailure { err ->
                statusText = err.message ?: "识别失败"
                onShowActionToast(statusText, null, null)
            }
            busy = false
        }
    }

    LaunchedEffect(incomingBitmap) {
        if (incomingBitmap != null) {
            sourceBitmap = incomingBitmap
            onConsumeIncomingBitmap()
            runOcr(incomingBitmap)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { BitmapIO.loadBitmapOriented(context, uri, 2000) }
                if (bmp != null) {
                    sourceBitmap = bmp
                    runOcr(bmp)
                } else {
                    onShowActionToast("无法载入图片", null, null)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Engine selector
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("识别引擎", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OcrEngineMode.entries.forEach { m ->
                    FilterChip(
                        selected = engineMode == m,
                        onClick = { engineMode = m },
                        label = { Text(m.label) },
                    )
                }
            }
        }

        // Format selector + image picker
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OcrOutputFormat.entries.forEach { f ->
                    FilterChip(
                        selected = outputFormat == f,
                        onClick = { outputFormat = f },
                        label = { Text(f.label) },
                    )
                }
            }
            FilledTonalButton(
                onClick = { imagePicker.launch("image/*") },
                enabled = !busy,
                shape = RoundedCornerShape(22.dp),
            ) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("选图识别")
            }
        }

        // Source thumbnail + re-run button
        sourceBitmap?.let { bmp ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "待识别图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 56.dp, height = 56.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${bmp.width} × ${bmp.height}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            statusText.ifBlank { "切换上方引擎或格式后可重新识别" },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { runOcr(bmp) },
                        enabled = !busy,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重跑")
                    }
                }
            }
        }

        // Editable source code box
        OutlinedTextField(
            value = editorText,
            onValueChange = { editorText = it },
            label = { Text("${outputFormat.label} 源码（可随时编辑）") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            minLines = 7,
            maxLines = 14,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        // Sub-ms Tylax AST LaTeX <-> Typst + Copy + Save Note bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        val t0 = System.nanoTime()
                        val converted = withContext(Dispatchers.Default) {
                            NativeTypstEngine.latexToTypst(editorText, cleanStyle = true)
                        }
                        val us = (System.nanoTime() - t0) / 1000L
                        if (converted.isNotBlank()) {
                            editorText = converted
                            outputFormat = OcrOutputFormat.TYPST
                            statusText = "Tylax AST: LaTeX → Typst (${us}µs)"
                            onShowActionToast(statusText, null, null)
                        }
                    }
                },
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Outlined.Functions, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("LaTeX → Typst")
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val t0 = System.nanoTime()
                        val converted = withContext(Dispatchers.Default) {
                            NativeTypstEngine.typstToLatex(editorText)
                        }
                        val us = (System.nanoTime() - t0) / 1000L
                        if (converted.isNotBlank()) {
                            editorText = converted
                            outputFormat = OcrOutputFormat.LATEX
                            statusText = "Tylax AST: Typst → LaTeX (${us}µs)"
                            onShowActionToast(statusText, null, null)
                        }
                    }
                },
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Typst → LaTeX")
            }

            OutlinedButton(
                onClick = {
                    AndroidFileHelper.copyToClipboard(context, "BGOOM OCR", editorText)
                    onShowActionToast("已复制源码", null, null)
                },
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("复制")
            }

            OutlinedButton(
                onClick = {
                    val title = editorText.lines().firstOrNull { it.isNotBlank() }
                        ?.removePrefix("=")?.removePrefix("#")?.trim()?.take(28) ?: "OCR 便签"
                    db.upsertNote(NoteItem(title = title, content = editorText))
                    onShowActionToast("已存入本地便签", null, null)
                },
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("存便签")
            }
        }

        // Typst -> PDF compilation panel (Dual-mode: Local Offline Rust JNI vs Remote SSH Mac typst)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Typst 一键出 PDF", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TypstCompileMode.entries.forEach { m ->
                            FilterChip(
                                selected = typstMode == m,
                                onClick = { typstMode = m },
                                label = { Text(m.label) },
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val typstSrc = if (outputFormat == OcrOutputFormat.LATEX) {
                                withContext(Dispatchers.Default) {
                                    NativeTypstEngine.latexToTypst(editorText, cleanStyle = true)
                                }
                            } else {
                                editorText
                            }
                            statusText = "正在通过 ${typstMode.label} 编译 PDF…"
                            typstCompiler.compile(typstSrc, typstMode, activeDevice)
                                .onSuccess { res ->
                                    compiledPdfFile = res.pdfFile
                                    pdfPreviewPages = withContext(Dispatchers.IO) {
                                        PdfExporter.renderPdfPages(res.pdfFile, maxPages = 6, targetWidth = 1080)
                                    }
                                    statusText = "PDF 编译完成 · ${res.elapsedMs}ms (${res.modeUsed.label})"
                                }
                                .onFailure { err ->
                                    statusText = "Typst 编译错误：${err.message}"
                                    onShowActionToast(statusText, null, null)
                                }
                            busy = false
                        }
                    },
                    enabled = !busy && editorText.isNotBlank(),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "处理中…" else "一键编译 PDF（${typstMode.label}）")
                }

                if (statusText.isNotBlank()) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                compiledPdfFile?.let { pdf ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val saved = withContext(Dispatchers.IO) {
                                        AndroidFileHelper.savePdfToDownloads(context, pdf)
                                    }
                                    saved.onSuccess { pub ->
                                        onShowActionToast("已存至 ${pub.displayLocation}", "立即打开") {
                                            AndroidFileHelper.openFile(context, pub.uri.toString(), "", pub.mimeType)
                                        }
                                    }.onFailure { onShowActionToast(it.message ?: "保存失败", null, null) }
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("存下载")
                        }
                        FilledTonalButton(
                            onClick = {
                                val dev = activeDevice
                                if (dev == null) {
                                    onShowActionToast("请先选择电脑设备", null, null)
                                    return@FilledTonalButton
                                }
                                scope.launch {
                                    busy = true
                                    sshRepo.uploadFile(dev, pdf, notifyDesktop = settings.notifyDesktopOnSend)
                                        .onSuccess { onShowActionToast("PDF 已加密送达 ${dev.name}", null, null) }
                                        .onFailure { onShowActionToast(it.message ?: "上传失败", null, null) }
                                    busy = false
                                }
                            },
                            enabled = activeDevice != null && !busy,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1.2f),
                        ) {
                            Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (activeDevice != null) "传给 ${activeDevice.name}" else "SSH 直传")
                        }
                    }

                    pdfPreviewPages.forEachIndexed { idx, pageBmp ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Image(
                                bitmap = pageBmp.asImageBitmap(),
                                contentDescription = "PDF 第 ${idx + 1} 页",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
