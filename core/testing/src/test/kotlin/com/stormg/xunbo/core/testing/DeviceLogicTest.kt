package com.stormg.xunbo.core.testing

import com.stormg.xunbo.core.agent.CalibrationGeometry
import com.stormg.xunbo.core.agent.ImportPlanner
import com.stormg.xunbo.core.agent.ImportRow
import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.IrFrameFormat
import com.stormg.xunbo.core.model.NormPoint
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.model.ScreenCalibration
import com.stormg.xunbo.core.navigation.IrWire
import com.stormg.xunbo.core.navigation.LearnedBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLogicTest {
    @Test fun formatsUseUnsignedBytes() {
        val code = IrCode(RemoteKey.UP, 0, 255, 128)
        assertEquals(listOf(0, 255, 128, 127), IrWire.encode(code, IrFrameFormat.RAW4_INV).map { it.toInt() and 255 })
        assertEquals(listOf(161, 241, 0, 255, 128), IrWire.encode(code, IrFrameFormat.A1F1).map { it.toInt() and 255 })
        assertThrows(IllegalArgumentException::class.java) { IrWire.encode(code.copy(commandCode = 256), IrFrameFormat.RAW4_INV) }
        assertThrows(IllegalArgumentException::class.java) { IrWire.encode(code.copy(userCode1 = -1), IrFrameFormat.A1F1) }
    }

    @Test fun fragmentedLearningRequiresExactlyThreeBytes() {
        val bytes = LearnedBytes(RemoteKey.LEFT)
        assertNull(bytes.accept(byteArrayOf(0)))
        assertNull(bytes.accept(byteArrayOf(-1)))
        assertEquals(IrCode(RemoteKey.LEFT, 0, 255, 12), bytes.accept(byteArrayOf(12)))
        assertThrows(IllegalArgumentException::class.java) { LearnedBytes(RemoteKey.UP).accept(byteArrayOf(1, 2, 3, 4)) }
    }

    private fun calibration() =
        ScreenCalibration(
            NormPoint(.1f, .1f),
            NormPoint(.9f, .1f),
            NormPoint(.9f, .9f),
            NormPoint(.1f, .9f),
            1920,
            1080,
            90,
        )

    @Test fun rejectsBowTieNanAndWrongOrder() {
        val c = calibration()
        assertTrue(CalibrationGeometry.valid(c))
        assertFalse(CalibrationGeometry.valid(c.copy(topRight = c.bottomRight, bottomRight = c.topRight)))
        assertFalse(CalibrationGeometry.valid(c.copy(topLeft = NormPoint(Float.NaN, 0f))))
        assertFalse(CalibrationGeometry.valid(c.copy(topLeft = c.bottomLeft, bottomLeft = c.topLeft)))
        assertFalse(CalibrationGeometry.valid(c.copy(rotationDegrees = 45)))
        assertFalse(CalibrationGeometry.valid(c.copy(imageWidth = 0)))
    }

    @Test fun fitCenterRejectsLetterboxAndMapsImageCenter() {
        assertNull(CalibrationGeometry.fromPreview(50f, 5f, 100, 100, 200, 100))
        assertEquals(NormPoint(.5f, .5f), CalibrationGeometry.fromPreview(50f, 50f, 100, 100, 200, 100))
        assertEquals(NormPoint(0f, 0f), CalibrationGeometry.fromPreview(0f, 25f, 100, 100, 200, 100))
    }

    @Test fun importDefaultsToRetainingExistingAndLastDuplicateIsConflict() {
        val old = IrCode(RemoteKey.UP, 1, 2, 3)
        val rows = listOf(ImportRow("上", 4, 5, 6), ImportRow("UP", 7, 8, 9), ImportRow("陌生", 1, 2, 3), ImportRow("左", -1, 0, 0))
        val plan = ImportPlanner.preview(rows, mapOf(RemoteKey.UP to old))
        assertEquals(1, plan.unknown)
        assertEquals(1, plan.invalid)
        assertEquals(2, plan.conflicts)
        assertTrue(plan.selected(false).isEmpty())
        assertEquals(listOf(IrCode(RemoteKey.UP, 7, 8, 9)), plan.selected(true))
    }

    @Test
    fun repeatedSourceNamesKeepFirstUnlessOverwriteIsExplicit() {
        val plan = ImportPlanner.preview(listOf(ImportRow("UP", 1, 2, 3), ImportRow("上", 4, 5, 6)), emptyMap())
        assertEquals(3, plan.selected(false).single().commandCode)
        assertEquals(6, plan.selected(true).single().commandCode)
    }
}
