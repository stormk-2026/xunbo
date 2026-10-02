package com.stormg.xunbo.storage

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class ManualExport(private val context: Context, private val dao: DebugDao, private val calibration: RoomCalibration) {
    suspend fun write(uri: Uri) =
        withContext(Dispatchers.IO) {
            val records = dao.allRecords().map { Json.decodeFromString<ManualRecord>(it.json) }
            val frames = File(context.filesDir, "frames")

            fun file(name: String): File {
                require(Regex("[a-zA-Z0-9_-]+\\.jpg").matches(name)) { "INVALID_ATTACHMENT" }
                return File(frames, name)
            }
            val plan = ExportPlan.create(records, calibration.load()) { file(it).isFile }
            context.contentResolver.openOutputStream(uri, "wt").use { output ->
                requireNotNull(output) { "EXPORT_OPEN_FAILED" }
                ManualArchive.write(output, plan) { file(it).inputStream() }
            }
        }
}
