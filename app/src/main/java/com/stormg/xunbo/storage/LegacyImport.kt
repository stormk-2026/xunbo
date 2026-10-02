package com.stormg.xunbo.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.stormg.xunbo.core.agent.ImportRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads a user-selected copy only; never opens the old app's live database. */
class LegacyImport(private val context: Context) {
    suspend fun read(uri: Uri): List<ImportRow> =
        withContext(Dispatchers.IO) {
            val copy = File.createTempFile("ir-import-", ".db", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "IMPORT_OPEN_FAILED" }
                    copy.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 16 * 1024 * 1024) { "IMPORT_TOO_LARGE" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val table =
                        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='ir_keys'", null).use {
                            it.moveToFirst()
                        }
                    require(table) { "IMPORT_TABLE_MISSING" }
                    db.rawQuery("SELECT name,userCode1,userCode2,commandCode FROM ir_keys LIMIT 10001", null).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                require(size < 10000) { "IMPORT_TOO_MANY_ROWS" }
                                val name = if (cursor.getType(0) == Cursor.FIELD_TYPE_STRING) cursor.getString(0).take(100) else ""

                                fun byteAt(i: Int): Int =
                                    if (cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER &&
                                        cursor.getLong(i) in 0L..255L
                                    ) {
                                        cursor.getInt(i)
                                    } else {
                                        -1
                                    }
                                add(ImportRow(name, byteAt(1), byteAt(2), byteAt(3)))
                            }
                        }
                    }
                }
            } finally {
                copy.delete()
                File(copy.path + "-wal").delete()
                File(copy.path + "-shm").delete()
            }
        }
}
