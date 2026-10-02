package com.stormg.xunbo.core.agent

import com.stormg.xunbo.core.model.NormPoint
import com.stormg.xunbo.core.model.ScreenCalibration
import kotlin.math.min

object CalibrationGeometry {
    fun valid(c: ScreenCalibration): Boolean {
        if (c.imageWidth <= 0 || c.imageHeight <= 0 || c.rotationDegrees !in listOf(0, 90, 180, 270)) return false
        val points = listOf(c.topLeft, c.topRight, c.bottomRight, c.bottomLeft)
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % 4]
            val d = points[(i + 2) % 4]
            if ((b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x) <= .0001f) return false
        }
        return c.topLeft.x < c.topRight.x && c.bottomLeft.x < c.bottomRight.x &&
            c.topLeft.y < c.bottomLeft.y && c.topRight.y < c.bottomRight.y
    }

    fun fromPreview(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): NormPoint? {
        if (min(min(width, height), min(imageWidth, imageHeight)) <= 0) return null
        val scale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val point =
            NormPoint(
                (x - (width - imageWidth * scale) / 2) / (imageWidth * scale),
                (y - (height - imageHeight * scale) / 2) / (imageHeight * scale),
            )
        return point.takeIf { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }
    }
}
