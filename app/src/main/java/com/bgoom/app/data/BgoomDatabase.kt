package com.bgoom.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ultra-lightweight local-first SQLite store for Notes and Transfer records.
 * Seamlessly migrates v1 -> v2 preserving existing notes & transfer history.
 */
class BgoomDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val _notes = MutableStateFlow<List<NoteItem>>(emptyList())
    val notes: StateFlow<List<NoteItem>> = _notes.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferRecord>>(emptyList())
    val transfers: StateFlow<List<TransferRecord>> = _transfers.asStateFlow()

    init {
        refreshNotes()
        refreshTransfers()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE notes (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                synced_device_id TEXT,
                synced_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE transfers (
                id TEXT PRIMARY KEY,
                file_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                direction TEXT NOT NULL,
                device_summary TEXT NOT NULL,
                remote_path TEXT NOT NULL,
                local_path TEXT NOT NULL,
                local_uri TEXT NOT NULL DEFAULT '',
                mime_type TEXT NOT NULL DEFAULT '*/*',
                success INTEGER NOT NULL,
                message TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE transfers ADD COLUMN local_uri TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE transfers ADD COLUMN mime_type TEXT NOT NULL DEFAULT '*/*'") }
        }
    }

    fun refreshNotes() {
        val list = mutableListOf<NoteItem>()
        readableDatabase.rawQuery(
            "SELECT id, title, content, updated_at, synced_device_id, synced_at FROM notes ORDER BY updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    NoteItem(
                        id = c.getString(0),
                        title = c.getString(1),
                        content = c.getString(2),
                        updatedAt = c.getLong(3),
                        syncedDeviceId = if (c.isNull(4)) null else c.getString(4),
                        syncedAt = if (c.isNull(5)) null else c.getLong(5),
                    )
                )
            }
        }
        _notes.value = list
    }

    fun upsertNote(note: NoteItem) {
        val cv = ContentValues().apply {
            put("id", note.id)
            put("title", note.title)
            put("content", note.content)
            put("updated_at", note.updatedAt)
            if (note.syncedDeviceId != null) put("synced_device_id", note.syncedDeviceId) else putNull("synced_device_id")
            if (note.syncedAt != null) put("synced_at", note.syncedAt) else putNull("synced_at")
        }
        writableDatabase.insertWithOnConflict("notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        refreshNotes()
    }

    fun markNoteSynced(noteId: String, deviceId: String, syncedAt: Long = System.currentTimeMillis()) {
        val cv = ContentValues().apply {
            put("synced_device_id", deviceId)
            put("synced_at", syncedAt)
        }
        writableDatabase.update("notes", cv, "id = ?", arrayOf(noteId))
        refreshNotes()
    }

    fun deleteNote(noteId: String) {
        writableDatabase.delete("notes", "id = ?", arrayOf(noteId))
        refreshNotes()
    }

    fun refreshTransfers() {
        val list = mutableListOf<TransferRecord>()
        readableDatabase.rawQuery(
            "SELECT id, file_name, size_bytes, direction, device_summary, remote_path, local_path, local_uri, mime_type, success, message, created_at FROM transfers ORDER BY created_at DESC LIMIT 80",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val dir = runCatching { TransferDirection.valueOf(c.getString(3)) }
                    .getOrDefault(TransferDirection.PUSH)
                list.add(
                    TransferRecord(
                        id = c.getString(0),
                        fileName = c.getString(1),
                        sizeBytes = c.getLong(2),
                        direction = dir,
                        deviceSummary = c.getString(4),
                        remotePath = c.getString(5),
                        localPath = c.getString(6),
                        localUri = c.getString(7) ?: "",
                        mimeType = c.getString(8) ?: "*/*",
                        success = c.getInt(9) == 1,
                        message = c.getString(10),
                        createdAt = c.getLong(11),
                    )
                )
            }
        }
        _transfers.value = list
    }

    fun recordTransfer(record: TransferRecord) {
        val cv = ContentValues().apply {
            put("id", record.id)
            put("file_name", record.fileName)
            put("size_bytes", record.sizeBytes)
            put("direction", record.direction.name)
            put("device_summary", record.deviceSummary)
            put("remote_path", record.remotePath)
            put("local_path", record.localPath)
            put("local_uri", record.localUri)
            put("mime_type", record.mimeType)
            put("success", if (record.success) 1 else 0)
            put("message", record.message)
            put("created_at", record.createdAt)
        }
        writableDatabase.insertWithOnConflict("transfers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        refreshTransfers()
    }

    companion object {
        private const val DB_NAME = "bgoom_local.db"
        private const val DB_VERSION = 2
    }
}
