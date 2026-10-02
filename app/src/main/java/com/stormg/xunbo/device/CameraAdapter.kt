package com.stormg.xunbo.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.stormg.xunbo.core.agent.CalibrationGeometry
import com.stormg.xunbo.core.model.CameraSession
import com.stormg.xunbo.core.model.CameraSessionState
import com.stormg.xunbo.core.model.Frame
import com.stormg.xunbo.core.model.NormPoint
import com.stormg.xunbo.core.model.ScreenCalibration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/** Pixel ownership, orientation, display and capture stay inside this Android adapter. */
class CameraAdapter(private val context: Context) : CameraSession {
    private val mutableState = MutableStateFlow(CameraSessionState.STOPPED)
    override val state = mutableState.asStateFlow()
    val info = MutableStateFlow("相机未启动")
    var owner: LifecycleOwner? = null
    private var previewRotation = 0
    private var analysis: ImageAnalysis? = null
    private var provider: ProcessCameraProvider? = null
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var image: Bitmap? = null
    private var rotation = 0
    private var capturedAt = 0L
    private var runId = 0L
    private var calibration: ScreenCalibration? = null
    private val views = mutableSetOf<View>()

    fun useCalibration(value: ScreenCalibration?) {
        synchronized(lock) { calibration = value }
    }

    fun calibrationSnapshot(): ScreenCalibration? = synchronized(lock) { applicableCalibration() }

    override suspend fun start() =
        withContext(Dispatchers.Main.immediate) {
            if (state.value == CameraSessionState.READY || state.value == CameraSessionState.STARTING) return@withContext
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                mutableState.value = CameraSessionState.PERMISSION_REQUIRED
                error("CAMERA_PERMISSION_REQUIRED")
            }
            val lifecycleOwner = checkNotNull(owner) { "CAMERA_SESSION_REQUIRED" }
            val accepted = synchronized(lock) { ++runId }
            mutableState.value = CameraSessionState.STARTING
            try {
                val future = ProcessCameraProvider.getInstance(context)
                val cameraProvider =
                    suspendCancellableCoroutine { continuation ->
                        future.addListener({
                            try {
                                continuation.resume(future.get())
                            } catch (error: Exception) {
                                continuation.resumeWithException(error)
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }
                check(synchronized(lock) { runId == accepted }) { "CAMERA_SESSION_CHANGED" }
                provider = cameraProvider
                val analysis =
                    ImageAnalysis.Builder()
                        .setTargetRotation(previewRotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                this@CameraAdapter.analysis = analysis
                analysis.setAnalyzer(analyzerExecutor) { frame -> receive(frame, accepted) }
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (error: Exception) {
                mutableState.value = CameraSessionState.ERROR
                info.value = "相机启动失败，请结束后重新启动"
                throw error
            }
        }

    private fun receive(
        frame: ImageProxy,
        accepted: Long,
    ) {
        try {
            if (synchronized(lock) { accepted != runId || SystemClock.elapsedRealtime() - capturedAt < 100 }) return
            // CameraX supplies RGBA; copy row-by-row to discard row padding before applying rotation.
            val plane = frame.planes[0]
            val buffer = plane.buffer
            val packed = java.nio.ByteBuffer.allocateDirect(frame.width * frame.height * 4)
            for (row in 0 until frame.height) {
                buffer.position(row * plane.rowStride)
                val line = ByteArray(frame.width * 4)
                buffer.get(line)
                packed.put(line)
            }
            packed.rewind()
            val raw = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(packed)
            val degrees = frame.imageInfo.rotationDegrees
            val corrected =
                if (degrees == 0) {
                    raw
                } else {
                    Bitmap.createBitmap(
                        raw,
                        0,
                        0,
                        raw.width,
                        raw.height,
                        Matrix().apply { postRotate(degrees.toFloat()) },
                        true,
                    ).also { raw.recycle() }
                }
            synchronized(lock) {
                if (accepted != runId) {
                    corrected.recycle()
                    return
                }
                image?.recycle()
                image = corrected
                rotation = degrees
                capturedAt = SystemClock.elapsedRealtime()
                mutableState.value = CameraSessionState.READY
                info.value = "${corrected.width}×${corrected.height} / $degrees° / " +
                    if (applicableCalibration() != null) "标定可用" else "需要四角标定"
                views.forEach { it.postInvalidate() }
            }
        } catch (_: Exception) {
            mutableState.value = CameraSessionState.ERROR
            info.value = "相机帧处理失败"
        } finally {
            frame.close()
        }
    }

    override suspend fun stop() =
        withContext(Dispatchers.Main.immediate) {
            synchronized(lock) { runId++ }
            provider?.unbindAll()
            synchronized(lock) {
                image?.recycle()
                image = null
                capturedAt = 0
                views.forEach { it.postInvalidate() }
            }
            mutableState.value = CameraSessionState.STOPPED
            info.value = "相机已停止"
        }

    private fun applicableCalibration(): ScreenCalibration? {
        val bitmap = image ?: return null
        return calibration?.takeIf {
            CalibrationGeometry.valid(it) && it.imageWidth == bitmap.width && it.imageHeight == bitmap.height &&
                it.rotationDegrees == rotation
        }
    }

    private fun rectified(
        bitmap: Bitmap,
        c: ScreenCalibration,
    ): Bitmap {
        val points = listOf(c.topLeft, c.topRight, c.bottomRight, c.bottomLeft)
        val source = points.flatMap { listOf(it.x * bitmap.width, it.y * bitmap.height) }.toFloatArray()

        fun distance(
            a: NormPoint,
            b: NormPoint,
        ): Int =
            kotlin.math.hypot(
                (a.x - b.x) * bitmap.width,
                (a.y - b.y) * bitmap.height,
            ).toInt().coerceAtLeast(1)
        val width = maxOf(distance(c.topLeft, c.topRight), distance(c.bottomLeft, c.bottomRight))
        val height = maxOf(distance(c.topLeft, c.bottomLeft), distance(c.topRight, c.bottomRight))
        val matrix = Matrix()
        check(
            matrix.setPolyToPoly(
                source,
                0,
                floatArrayOf(
                    0f,
                    0f,
                    width.toFloat(),
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    0f,
                    height.toFloat(),
                ),
                0,
                4,
            ),
        ) { "CALIBRATION_TRANSFORM_FAILED" }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    override suspend fun latest(): Frame =
        withContext(Dispatchers.IO) {
            val (snapshot, time) =
                synchronized(lock) {
                    check(state.value == CameraSessionState.READY && SystemClock.elapsedRealtime() - capturedAt < 2_000) { "NO_LIVE_FRAME" }
                    val bitmap = checkNotNull(image) { "NO_LIVE_FRAME" }
                    val c = applicableCalibration()
                    (if (c == null) bitmap.copy(Bitmap.Config.ARGB_8888, false) else rectified(bitmap, c)) to capturedAt
                }
            val name = "${UUID.randomUUID()}.jpg"
            val directory = File(context.filesDir, "frames").apply { mkdirs() }
            try {
                File(directory, name).outputStream().use { check(snapshot.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally {
                snapshot.recycle()
            }
            object : Frame {
                override val fixtureId = name
                override val capturedAtMs = time
            }
        }

    fun preview(context: Context): Preview = Preview(context)

    inner class Preview(context: Context) : View(context) {
        var calibrating = false
        var showRectified = false
        var onCalibration: (ScreenCalibration) -> Unit = {}
        private val points = mutableListOf<NormPoint>()
        private var frozen: Bitmap? = null
        private var frozenRotation = 0
        private val destination = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            previewRotation = display?.rotation ?: 0
            analysis?.targetRotation = previewRotation
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            synchronized(lock) { views.add(this) }
        }

        override fun onDetachedFromWindow() {
            synchronized(lock) { views.remove(this) }
            reset()
            super.onDetachedFromWindow()
        }

        fun reset() {
            points.clear()
            frozen?.recycle()
            frozen = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            synchronized(lock) {
                val bitmap = frozen ?: image ?: return
                val c = applicableCalibration()
                val displayed = if (showRectified && !calibrating && c != null) rectified(bitmap, c) else bitmap
                val scale = min(width.toFloat() / displayed.width, height.toFloat() / displayed.height)
                val left = (width - displayed.width * scale) / 2
                val top = (height - displayed.height * scale) / 2
                destination.set(left, top, left + displayed.width * scale, top + displayed.height * scale)
                canvas.drawBitmap(displayed, null, destination, paint)
                if (displayed !== bitmap) displayed.recycle()
                paint.color = Color.GREEN
                paint.textSize = 36f
                for ((i, point) in points.withIndex()) {
                    val x = left + point.x * bitmap.width * scale
                    val y = top + point.y * bitmap.height * scale
                    canvas.drawCircle(x, y, 9f, paint)
                    canvas.drawText("${i + 1}", x + 12, y, paint)
                }
            }
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!calibrating) return false
            if (event.action != MotionEvent.ACTION_UP) return true
            performClick()
            synchronized(lock) {
                if (frozen == null) {
                    frozen = image?.copy(Bitmap.Config.ARGB_8888, false) ?: return true
                    frozenRotation = this@CameraAdapter.rotation
                }
                val bitmap = frozen ?: return true
                val point = CalibrationGeometry.fromPreview(event.x, event.y, width, height, bitmap.width, bitmap.height) ?: return true
                points.add(point)
                if (points.size == 4) {
                    val value = ScreenCalibration(points[0], points[1], points[2], points[3], bitmap.width, bitmap.height, frozenRotation)
                    onCalibration(value)
                    reset()
                }
                invalidate()
            }
            return true
        }
    }
}
