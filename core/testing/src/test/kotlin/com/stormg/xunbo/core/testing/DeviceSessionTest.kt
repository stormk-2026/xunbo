package com.stormg.xunbo.core.testing

import com.stormg.xunbo.core.agent.DeviceDebugController
import com.stormg.xunbo.core.agent.ImportRow
import com.stormg.xunbo.core.model.CalibrationStore
import com.stormg.xunbo.core.model.CameraSession
import com.stormg.xunbo.core.model.CameraSessionState
import com.stormg.xunbo.core.model.DeviceConnectionState
import com.stormg.xunbo.core.model.Frame
import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.IrCodeStore
import com.stormg.xunbo.core.model.IrDeviceDescriptor
import com.stormg.xunbo.core.model.IrDeviceSession
import com.stormg.xunbo.core.model.IrDeviceStatus
import com.stormg.xunbo.core.model.IrFrameFormat
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.model.ScreenCalibration
import com.stormg.xunbo.core.navigation.SerialKeyExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSessionTest {
    private class Device : IrDeviceSession {
        override val status = MutableStateFlow(IrDeviceStatus(DeviceConnectionState.READY))
        override val isReady get() = status.value.connection == DeviceConnectionState.READY
        val sent = mutableListOf<IrCode>()
        var beforeWrite: suspend () -> Unit = {}
        var denyPermission = false

        override suspend fun send(code: IrCode) {
            beforeWrite()
            check(isReady)
            sent.add(code)
        }

        override suspend fun devices() = listOf(IrDeviceDescriptor("fake", "Fake USB"))

        override suspend fun connect(deviceId: String) {
            if (denyPermission) {
                status.value = IrDeviceStatus(DeviceConnectionState.ERROR, errorCode = "USB_PERMISSION_DENIED")
                error("USB_PERMISSION_DENIED")
            }
            status.value = IrDeviceStatus(DeviceConnectionState.READY)
        }

        override suspend fun disconnect() {
            status.value = IrDeviceStatus(DeviceConnectionState.DISCONNECTED)
        }

        override suspend fun learn(key: RemoteKey): IrCode {
            delay(5000)
            return IrCode(key, 1, 2, 3)
        }

        override suspend fun setFrameFormat(format: IrFrameFormat) = Unit
    }

    private class Codes : IrCodeStore {
        var failBatch = false
        var batchCalls = 0
        override val codes = MutableStateFlow(mapOf(RemoteKey.UP to IrCode(RemoteKey.UP, 1, 2, 3)))

        override suspend fun save(code: IrCode) {
            codes.value = codes.value + (code.name to code)
        }

        override suspend fun saveAll(codes: List<IrCode>) {
            batchCalls++
            if (failBatch) error("FAKE_TRANSACTION_FAILURE")
            this.codes.value += codes.associateBy { it.name }
        }

        override suspend fun delete(key: RemoteKey) {
            codes.value -= key
        }
    }

    private class Camera : CameraSession {
        var gate: CompletableDeferred<Unit>? = null
        override val state = MutableStateFlow(CameraSessionState.STOPPED)

        override suspend fun start() {
            gate?.await()
            state.value = CameraSessionState.READY
        }

        override suspend fun stop() {
            state.value = CameraSessionState.STOPPED
        }

        override suspend fun latest(): Frame = error("NO_FRAME")
    }

    private val calibration =
        object : CalibrationStore {
            override suspend fun load(): ScreenCalibration? = null

            override suspend fun save(calibration: ScreenCalibration) = Unit
        }

    @Test fun stoppingBeforeDriverWriteCancelsInFlightAndQueuedWork() =
        runTest {
            val device = Device()
            val store = Codes()
            val camera = Camera()
            val controller = DeviceDebugController(device, SerialKeyExecutor(device) { store.codes.value }, store, camera, calibration)
            val gate = CompletableDeferred<Unit>()
            device.beforeWrite = { gate.await() }
            controller.begin()
            val first = launch { controller.send(RemoteKey.UP) }
            yield()
            val second = launch { controller.send(RemoteKey.UP) }
            yield()
            controller.stop()
            gate.complete(Unit)
            first.join()
            second.join()
            assertTrue(device.sent.isEmpty())
            assertEquals(0, controller.pending.value)
            controller.end()
            assertEquals(CameraSessionState.STOPPED, camera.state.value)
            controller.connect("fake")
            controller.begin()
            controller.send(RemoteKey.UP)
            assertEquals(1, device.sent.size)
        }

    @Test fun learningRejectsSendingAndStopNeverSavesPartialResult() =
        runTest {
            val device = Device()
            val store = Codes()
            val controller = DeviceDebugController(device, SerialKeyExecutor(device) { store.codes.value }, store, Camera(), calibration)
            controller.begin()
            val learning = launch { controller.learn(RemoteKey.LEFT) }
            yield()
            var rejected = false
            val sending =
                launch {
                    try {
                        controller.send(RemoteKey.UP)
                    } catch (_: IllegalStateException) {
                        rejected = true
                    }
                }
            yield()
            assertTrue(rejected)
            controller.stop()
            learning.join()
            sending.join()
            assertFalse(RemoteKey.LEFT in store.codes.value)
            assertTrue(device.sent.isEmpty())
        }

    @Test fun learnedCodesAreReadWithoutCreatingAnotherExecutor() =
        runTest {
            val device = Device()
            val store = Codes()
            val executor = SerialKeyExecutor(device) { store.codes.value }
            executor.send(RemoteKey.UP)
            store.save(IrCode(RemoteKey.UP, 4, 5, 6))
            executor.send(RemoteKey.UP)
            assertEquals(listOf(3, 6), device.sent.map { it.commandCode })
        }

    @Test
    fun previewCannotOverwriteAKeyAddedBeforeConfirmation() =
        runTest {
            val device = Device()
            val store = Codes()
            val controller = DeviceDebugController(device, SerialKeyExecutor(device) { store.codes.value }, store, Camera(), calibration)
            val plan = controller.previewImport(listOf(ImportRow("LEFT", 1, 2, 3)))
            store.save(IrCode(RemoteKey.LEFT, 7, 8, 9))
            controller.import(plan, false)
            assertEquals(9, store.codes.value.getValue(RemoteKey.LEFT).commandCode)
        }

    @Test
    fun failedBatchDoesNotFallBackToPartialSaves() =
        runTest {
            val device = Device()
            val store = Codes()
            val controller = DeviceDebugController(device, SerialKeyExecutor(device) { store.codes.value }, store, Camera(), calibration)
            val plan = controller.previewImport(listOf(ImportRow("LEFT", 1, 2, 3), ImportRow("RIGHT", 4, 5, 6)))
            store.failBatch = true
            var failed = false
            try {
                controller.import(plan, false)
            } catch (_: IllegalStateException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(1, store.batchCalls)
            assertEquals(setOf(RemoteKey.UP), store.codes.value.keys)
        }

    @Test
    fun permissionDenialPropagatesWithoutSendingOrSaving() =
        runTest {
            val device = Device().apply { denyPermission = true }
            val store = Codes()
            val controller = DeviceDebugController(device, SerialKeyExecutor(device) { store.codes.value }, store, Camera(), calibration)
            var denied = false
            try {
                controller.connect("fake")
            } catch (_: IllegalStateException) {
                denied = true
            }
            assertTrue(denied)
            assertEquals("USB_PERMISSION_DENIED", controller.deviceStatus.value.errorCode)
            assertTrue(device.sent.isEmpty())
            assertEquals(0, store.batchCalls)
        }

    @Test
    fun stopWhileCameraStartsCannotReactivateSending() =
        runTest {
            val device = Device()
            val store = Codes()
            val camera = Camera().apply { gate = CompletableDeferred() }
            val controller = DeviceDebugController(device, SerialKeyExecutor(device) { store.codes.value }, store, camera, calibration)
            val starting = launch { controller.begin() }
            yield()
            controller.stop()
            camera.gate?.complete(Unit)
            starting.join()
            assertTrue(starting.isCancelled)
            var rejected = false
            try {
                controller.send(RemoteKey.UP)
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
            assertTrue(device.sent.isEmpty())
        }
}
