package com.stormg.xunbo.storage

import com.stormg.xunbo.core.model.RemoteKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ManualArchiveTest {
    private fun record() =
        ManualRecord(
            "r1", "s1", 100, "fake", "RAW4_INV", RemoteKey.UP, 1, 2,
            "before.jpg", "after.jpg", true, null,
        )

    @Test fun zipHasExplicitVersionManualSourceAndMissingEvidence() {
        val plan = ExportPlan.create(listOf(record()), null) { it == "before.jpg" }
        assertEquals(listOf("after.jpg"), plan.missingAttachments)
        val output = ByteArrayOutputStream()
        ManualArchive.write(output, plan) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val json = Json.parseToJsonElement(entries.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
        assertEquals("1", json.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("MANUAL_DEBUG", json.getValue("source").jsonPrimitive.content)
        assertTrue("frames/before.jpg" in entries)
        assertFalse("frames/after.jpg" in entries)
        assertTrue(entries.getValue("manifest.json").toString(Charsets.UTF_8).contains("UNLABELED"))
    }

    @Test fun invalidAttachmentNeverEscapesPrivateFrames() {
        assertThrows(IllegalArgumentException::class.java) {
            ExportPlan.create(listOf(record().copy(before = "../secret.jpg")), null) { true }
        }
    }

    @Test fun noFramesIsExplicitlyMissingWhenCaptureRequested() {
        val plan = ExportPlan.create(listOf(record().copy(before = null, after = null)), null) { true }
        assertEquals(listOf("r1"), plan.recordsWithoutFrames)
        assertTrue(plan.attachments.isEmpty())
    }
}
