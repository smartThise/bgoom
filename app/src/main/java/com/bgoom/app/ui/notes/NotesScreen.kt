package com.bgoom.app.ui.notes

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bgoom.app.data.BgoomDatabase
import com.bgoom.app.data.NoteItem
import com.bgoom.app.data.SshDevice
import com.bgoom.app.ssh.SshHubRepository
import com.bgoom.app.util.AndroidFileHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    db: BgoomDatabase,
    activeDevice: SshDevice?,
    sshRepo: SshHubRepository,
    onShowToast: (String) -> Unit,
) {
    val notes by db.notes.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editingNote by remember { mutableStateOf<NoteItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var busySync by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = {
                    editingNote = null
                    showEditor = true
                },
                shape = RoundedCornerShape(22.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建便签")
            }
            OutlinedButton(
                onClick = {
                    val dev = activeDevice
                    if (dev == null) {
                        onShowToast("请先配置并选择一个 SSH 设备")
                        return@OutlinedButton
                    }
                    scope.launch {
                        busySync = true
                        sshRepo.pullNotesFromDevice(dev)
                            .onSuccess { count -> onShowToast("已从 ${dev.name} 拉取 $count 条 Markdown 便签") }
                            .onFailure { onShowToast(it.message ?: "拉取失败") }
                        busySync = false
                    }
                },
                enabled = !busySync && activeDevice != null,
                shape = RoundedCornerShape(22.dp),
            ) {
                if (busySync) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("拉取远端")
            }
        }

        if (notes.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无便签", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "本地 SQLite 0ms 秒存，连接 SSH 后一键同步为远端纯文本 .md 文件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(notes, key = { it.id }) { note ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            editingNote = note
                            showEditor = true
                        },
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    note.title.ifBlank { "无标题便签" },
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (note.syncedAt != null) {
                                    Icon(
                                        Icons.Outlined.CloudDone,
                                        contentDescription = "已同步",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                note.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    AndroidFileHelper.formatRelativeTime(note.updatedAt),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        AndroidFileHelper.copyToClipboard(context, note.title, note.content)
                                        onShowToast("已复制便签内容")
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制", modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = {
                                        val dev = activeDevice
                                        if (dev == null) {
                                            onShowToast("请先选择 SSH 设备")
                                            return@IconButton
                                        }
                                        scope.launch {
                                            sshRepo.syncNoteToDevice(dev, note)
                                                .onSuccess { ms -> onShowToast("已同步到 ${dev.name} (${ms}ms)") }
                                                .onFailure { onShowToast(it.message ?: "同步失败") }
                                        }
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Outlined.CloudUpload, contentDescription = "同步", modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { db.deleteNote(note.id) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showEditor) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEditor = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            var title by remember { mutableStateOf(editingNote?.title ?: "") }
            var body by remember { mutableStateOf(editingNote?.content ?: "") }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (editingNote == null) "新建便签" else "编辑便签", style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Markdown 内容") },
                    minLines = 6,
                    maxLines = 12,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showEditor = false }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        shape = RoundedCornerShape(22.dp),
                        onClick = {
                            if (title.isBlank() && body.isBlank()) return@OutlinedButton
                            val item = (editingNote ?: NoteItem(title = "", content = "")).copy(
                                title = title.trim().ifBlank { body.trim().lines().first().take(24) },
                                content = body,
                                updatedAt = System.currentTimeMillis(),
                            )
                            db.upsertNote(item)
                            showEditor = false
                            onShowToast("已存本地")
                        },
                    ) { Text("仅存本地") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        shape = RoundedCornerShape(22.dp),
                        onClick = {
                            if (title.isBlank() && body.isBlank()) return@Button
                            val item = (editingNote ?: NoteItem(title = "", content = "")).copy(
                                title = title.trim().ifBlank { body.trim().lines().first().take(24) },
                                content = body,
                                updatedAt = System.currentTimeMillis(),
                            )
                            db.upsertNote(item)
                            showEditor = false
                            val dev = activeDevice
                            if (dev != null) {
                                scope.launch {
                                    sshRepo.syncNoteToDevice(dev, item)
                                        .onSuccess { ms -> onShowToast("已保存并同步到 ${dev.name} (${ms}ms)") }
                                        .onFailure { onShowToast("已存本地，同步失败：${it.message}") }
                                }
                            } else {
                                onShowToast("已存本地（未选 SSH 设备）")
                            }
                        },
                    ) { Text("保存并同步") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
