package com.stormg.xunbo.storage

import com.stormg.xunbo.core.model.ScreenCalibration
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class ExportManifest(
    val schemaVersion: Int = 1,
    val source: String = "MANUAL_DEBUG",
    val records: List<ManualRecord>,
    val calibration: ScreenCalibration?,
    val attachments: List<String>,
    val missingAttachments: List<String>,
    val recordsWithoutFrames: List<String>,
    val note: String = "UNLABELED is not success; USB write does not prove TV movement.",
)

object ExportPlan {
    fun create(
        records: List<ManualRecord>,
        calibration: ScreenCalibration?,
        exists: (String) -> Boolean,
    ): ExportManifest {
        val referenced = records.flatMap { listOfNotNull(it.before, it.after) }.distinct()
        require(referenced.all { Regex("[a-zA-Z0-9_-]+\\.jpg").matches(it) }) { "INVALID_ATTACHMENT" }
        return ExportManifest(
            records = records,
            calibration = calibration,
            attachments = referenced.filter(exists),
            missingAttachments = referenced.filterNot(exists),
            recordsWithoutFrames = records.filter { it.captureRequested && (it.before == null || it.after == null) }.map { it.id },
        )
    }
}

object ManualArchive {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun write(
        output: OutputStream,
        manifest: ExportManifest,
        open: (String) -> InputStream,
    ) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (name in manifest.attachments) {
                require(Regex("[a-zA-Z0-9_-]+\\.jpg").matches(name)) { "INVALID_ATTACHMENT" }
                zip.putNextEntry(ZipEntry("frames/$name"))
                open(name).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
