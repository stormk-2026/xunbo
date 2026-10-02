package com.stormg.xunbo.core.agent

import com.stormg.xunbo.core.model.CalibrationStore
import com.stormg.xunbo.core.model.CameraSession
import com.stormg.xunbo.core.model.Frame
import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.IrCodeStore
import com.stormg.xunbo.core.model.IrDeviceSession
import com.stormg.xunbo.core.model.IrFrameFormat
import com.stormg.xunbo.core.model.KeyExecutor
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.model.ScreenCalibration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** User initiated operations only. The controller never retries or schedules additional keys. */
class DeviceDebugController(
    private val device: IrDeviceSession,
    private val keys: KeyExecutor,
    private val store: IrCodeStore,
    private val camera: CameraSession,
    private val calibrationStore: CalibrationStore,
) {
    val deviceStatus = device.status
    val cameraState = camera.state
    val codes = store.codes
    private val queued = MutableStateFlow(0)
    val pending = queued.asStateFlow()
    private val current = MutableStateFlow<RemoteKey?>(null)
    val transmitting = current.asStateFlow()
    private val generation = AtomicLong()
    private val operation = Mutex()
    private val monitor = Any()
    private var learning: Job? = null
    private var active = false
    private val sends = mutableSetOf<Job>()

    suspend fun devices() = device.devices()

    suspend fun begin() {
        val accepted = generation.get()
        camera.start()
        currentCoroutineContext().ensureActive()
        synchronized(monitor) {
            if (accepted != generation.get()) throw CancellationException("SESSION_STOPPED")
            active = true
        }
    }

    fun stop() {
        synchronized(monitor) {
            active = false
            generation.incrementAndGet()
            keys.cancelPending()
            learning?.cancel()
            sends.toList().forEach { it.cancel() }
        }
    }

    suspend fun end() {
        stop()
        operation.withLock {
            try {
                device.disconnect()
            } finally {
                camera.stop()
            }
        }
    }

    suspend fun connect(id: String) {
        stop()
        operation.withLock { device.connect(id) }
    }

    suspend fun setFormat(format: IrFrameFormat) {
        check(operation.tryLock()) { "DEVICE_BUSY" }
        try {
            device.setFrameFormat(format)
        } finally {
            operation.unlock()
        }
    }

    suspend fun send(
        key: RemoteKey,
        execute: suspend (suspend () -> Unit) -> Unit = { it() },
    ) {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val accepted =
            synchronized(monitor) {
                check(learning == null) { "DEVICE_BUSY" }
                check(active) { "SESSION_STOPPED" }
                sends.add(job)
                queued.value++
                generation.get()
            }
        try {
            operation.withLock {
                currentCoroutineContext().ensureActive()
                synchronized(monitor) { checkActive(accepted) }
                execute {
                    synchronized(monitor) { checkActive(accepted) }
                    current.value = key
                    try {
                        keys.send(key)
                    } finally {
                        current.value = null
                    }
                }
            }
        } finally {
            synchronized(monitor) {
                queued.value--
                sends.remove(job)
            }
        }
    }

    private fun checkActive(accepted: Long) {
        if (!active || accepted != generation.get()) throw CancellationException("SESSION_STOPPED")
    }

    suspend fun learn(key: RemoteKey): IrCode {
        check(operation.tryLock()) { "DEVICE_BUSY" }
        try {
            val job = currentCoroutineContext()[Job]
            synchronized(monitor) {
                check(active) { "SESSION_STOPPED" }
                learning = job
            }
            return device.learn(key)
        } finally {
            synchronized(monitor) { learning = null }
            operation.unlock()
        }
    }

    suspend fun save(code: IrCode) = operation.withLock { store.save(code) }

    suspend fun delete(key: RemoteKey) = operation.withLock { store.delete(key) }

    fun previewImport(rows: List<ImportRow>) = ImportPlanner.preview(rows, codes.value)

    suspend fun import(
        plan: ImportPlan,
        overwrite: Boolean,
    ) = operation.withLock {
        store.saveAll(plan.selected(overwrite).filter { overwrite || it.name !in codes.value })
    }

    suspend fun saveCalibration(value: ScreenCalibration) =
        operation.withLock {
            require(CalibrationGeometry.valid(value)) { "INVALID_CALIBRATION" }
            calibrationStore.save(value)
        }

    suspend fun calibration() = calibrationStore.load()

    suspend fun capture(): Frame = camera.latest()
}
