package com.stormg.xunbo

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.room.Room
import com.stormg.xunbo.core.agent.DeviceDebugController
import com.stormg.xunbo.core.model.CalibrationStore
import com.stormg.xunbo.core.model.Frame
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.model.ScreenCalibration
import com.stormg.xunbo.core.navigation.SerialKeyExecutor
import com.stormg.xunbo.device.CameraAdapter
import com.stormg.xunbo.device.DebugSessionService
import com.stormg.xunbo.device.UsbIrSession
import com.stormg.xunbo.storage.DebugDatabase
import com.stormg.xunbo.storage.LegacyImport
import com.stormg.xunbo.storage.ManualExport
import com.stormg.xunbo.storage.ManualRecord
import com.stormg.xunbo.storage.RecordRow
import com.stormg.xunbo.storage.RoomCalibration
import com.stormg.xunbo.storage.RoomCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class XunboApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(private val application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val db = Room.databaseBuilder(application, DebugDatabase::class.java, "xunbo.db").build()
    val dao = db.dao()
    val codeStore = RoomCodes(db, scope)
    private val roomCalibration = RoomCalibration(dao)
    val camera = CameraAdapter(application)
    val previewInfo = camera.info
    val calibrationStore =
        object : CalibrationStore {
            override suspend fun load() = roomCalibration.load()

            override suspend fun save(calibration: ScreenCalibration) {
                roomCalibration.save(calibration)
                camera.useCalibration(calibration)
            }
        }
    val usb = UsbIrSession(application, dao)
    val irFormat = usb.format
    private val executor = SerialKeyExecutor(usb) { codeStore.codes.value }
    val debug = DeviceDebugController(usb, executor, codeStore, camera, calibrationStore)
    val importer = LegacyImport(application)
    val exporter = ManualExport(application, dao, roomCalibration)
    private val lifecycleLock = Mutex()
    private var requested = false
    private var sessionOwner: LifecycleOwner? = null
    val running = MutableStateFlow(false)
    val serviceError = MutableStateFlow<String?>(null)
    var sessionId = ""
        private set
    val initialized =
        scope.async {
            codeStore.initialized.await()
            usb.loadFormat()
            camera.useCalibration(calibrationStore.load())
        }

    init {
        usb.onDisconnected = { debug.stop() }
    }

    fun startSession() {
        if (running.value) return
        serviceError.value = null
        requested = true
        running.value = true
        try {
            ContextCompat.startForegroundService(application, Intent(application, DebugSessionService::class.java))
        } catch (error: Exception) {
            running.value = false
            requested = false
            throw error
        }
    }

    fun stopSession() {
        requested = false
        debug.stop()
        if (!application.stopService(Intent(application, DebugSessionService::class.java))) {
            scope.launch {
                debug.end()
                running.value = false
            }
        }
    }

    suspend fun beginSession(owner: LifecycleOwner) =
        lifecycleLock.withLock {
            initialized.await()
            check(requested) { "SESSION_STOPPED" }
            sessionOwner = owner
            camera.owner = owner
            debug.begin()
            sessionId = UUID.randomUUID().toString()
            running.value = true
        }

    suspend fun endSession(owner: LifecycleOwner) =
        lifecycleLock.withLock {
            if (sessionOwner != null && sessionOwner !== owner) return@withLock
            try {
                debug.end()
            } finally {
                camera.owner = null
                sessionOwner = null
                running.value = false
            }
        }

    suspend fun send(
        key: RemoteKey,
        capture: Boolean,
    ) {
        val queuedAt = SystemClock.elapsedRealtime()
        val wallTime = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val session = sessionId
        var started = queuedAt
        var writeMs = 0L
        var before: String? = null
        var after: String? = null
        var error: String? = null
        var writeStarted: Long? = null
        var beforeTime: Long? = null
        var afterTime: Long? = null
        var writeReturned = false
        val device = debug.deviceStatus.value.deviceLabel
        val format = usb.format.value.name
        var calibration = camera.calibrationSnapshot()
        try {
            debug.send(key) { write ->
                started = SystemClock.elapsedRealtime()
                calibration = camera.calibrationSnapshot()
                if (capture) {
                    captureOrNull()?.let {
                        before = it.fixtureId
                        beforeTime = it.capturedAtMs
                    }
                }
                val writeStart = SystemClock.elapsedRealtime()
                writeStarted = writeStart
                try {
                    write()
                    writeReturned = true
                } finally {
                    writeMs = SystemClock.elapsedRealtime() - writeStart
                }
                if (capture) {
                    delay(500) // Sampling delay only; not a stability or success judgment.
                    captureOrNull()?.let {
                        after = it.fixtureId
                        afterTime = it.capturedAtMs
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            error = "CANCELLED_OR_STOPPED"
            throw cancelled
        } catch (_: Exception) {
            error = "SEND_FAILED"
            throw IllegalStateException("SEND_FAILED")
        } finally {
            val record =
                ManualRecord(
                    id, session, wallTime, device, format, key, started - queuedAt, writeMs,
                    before, after, capture, error, calibration = calibration,
                    writeStartedAtElapsedMs = writeStarted,
                    beforeFrameAtElapsedMs = beforeTime,
                    afterFrameAtElapsedMs = afterTime,
                    sampleDelayMs = if (capture) 500 else 0,
                    writeReturned = writeReturned,
                )
            withContext(NonCancellable) { dao.record(RecordRow(id, wallTime, Json.encodeToString(record))) }
        }
    }

    private suspend fun captureOrNull(): Frame? =
        try {
            debug.capture()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
}
