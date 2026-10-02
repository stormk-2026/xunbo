package com.stormg.xunbo.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.stormg.xunbo.core.model.DeviceConnectionState
import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.IrDeviceDescriptor
import com.stormg.xunbo.core.model.IrDeviceSession
import com.stormg.xunbo.core.model.IrDeviceStatus
import com.stormg.xunbo.core.model.IrFrameFormat
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.navigation.IrWire
import com.stormg.xunbo.core.navigation.SerialLearning
import com.stormg.xunbo.storage.DebugDao
import com.stormg.xunbo.storage.SettingRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class UsbIrSession(private val context: Context, private val dao: DebugDao) : IrDeviceSession {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val mutableStatus = MutableStateFlow(IrDeviceStatus(DeviceConnectionState.DISCONNECTED))
    override val status = mutableStatus.asStateFlow()
    private val mutableFormat = MutableStateFlow(IrFrameFormat.RAW4_INV)
    val format = mutableFormat.asStateFlow()
    private val io = Mutex()
    private val epoch = AtomicLong()

    @Volatile private var port: UsbSerialPort? = null

    @Volatile private var selected: String? = null
    var onDisconnected: () -> Unit = {}
    override val isReady: Boolean get() = status.value.connection == DeviceConnectionState.READY && !status.value.learning

    private val detach =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && selected !in manager.deviceList.keys) {
                    invalidate("USB_DETACHED")
                }
            }
        }

    init {
        ContextCompat.registerReceiver(
            context,
            detach,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    suspend fun loadFormat() {
        mutableFormat.value = dao.setting("ir_format")?.let(IrFrameFormat::valueOf) ?: IrFrameFormat.RAW4_INV
    }

    override suspend fun devices(): List<IrDeviceDescriptor> =
        withContext(Dispatchers.IO) {
            UsbSerialProber.getDefaultProber().findAllDrivers(manager).map {
                IrDeviceDescriptor(it.device.deviceName, "USB ${it.device.vendorId}:${it.device.productId} (${it.device.deviceId})")
            }
        }

    override suspend fun connect(deviceId: String) =
        withContext(Dispatchers.IO) {
            disconnect()
            val accepted = epoch.get()
            val driver =
                UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull { it.device.deviceName == deviceId }
                    ?: error("USB_NOT_FOUND")
            selected = deviceId
            val label = "USB ${driver.device.vendorId}:${driver.device.productId} (${driver.device.deviceId}) / 9600 8N1"
            mutableStatus.value = IrDeviceStatus(DeviceConnectionState.CONNECTING, label)
            try {
                if (!manager.hasPermission(driver.device)) {
                    mutableStatus.value = IrDeviceStatus(DeviceConnectionState.PERMISSION_REQUIRED, label)
                    val action = "${context.packageName}.USB_PERMISSION.${UUID.randomUUID()}"
                    val result = CompletableDeferred<Unit>()
                    val receiver =
                        object : BroadcastReceiver() {
                            override fun onReceive(
                                context: Context,
                                intent: Intent,
                            ) {
                                if (intent.action == action) result.complete(Unit)
                            }
                        }
                    ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                    val pending =
                        PendingIntent.getBroadcast(
                            context,
                            0,
                            Intent(action).setPackage(context.packageName),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                        )
                    try {
                        manager.requestPermission(driver.device, pending)
                        withTimeout(60_000) { result.await() }
                    } finally {
                        pending.cancel()
                        context.unregisterReceiver(receiver)
                    }
                }
                check(manager.hasPermission(driver.device)) { "USB_PERMISSION_DENIED" }
                currentCoroutineContext().ensureActive()
                check(epoch.get() == accepted) { "USB_SESSION_CHANGED" }
                val connection = checkNotNull(manager.openDevice(driver.device)) { "USB_OPEN_FAILED" }
                val opened = driver.ports.first()
                try {
                    opened.open(connection)
                    opened.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    check(epoch.get() == accepted) { "USB_SESSION_CHANGED" }
                    port = opened
                    mutableStatus.value = IrDeviceStatus(DeviceConnectionState.READY, label)
                } catch (error: Exception) {
                    runCatching { opened.close() }
                    connection.close()
                    throw error
                }
            } catch (error: Exception) {
                invalidate(if (manager.hasPermission(driver.device)) "USB_CONNECT_FAILED" else "USB_PERMISSION_DENIED")
                throw error
            }
        }

    private fun invalidate(reason: String?) {
        epoch.incrementAndGet()
        val old = port
        port = null
        selected = null
        onDisconnected()
        runCatching { old?.close() }
        mutableStatus.value =
            IrDeviceStatus(
                if (reason == null) DeviceConnectionState.DISCONNECTED else DeviceConnectionState.ERROR, errorCode = reason,
            )
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) { invalidate(null) }

    override suspend fun send(code: IrCode) =
        withContext(Dispatchers.IO) {
            check(io.tryLock()) { "DEVICE_BUSY" }
            try {
                currentCoroutineContext().ensureActive()
                check(isReady) { "USB_NOT_READY" }
                val opened = checkNotNull(port) { "USB_NOT_READY" }
                // A partial/failed write is never retried: the television may already have received it.
                opened.write(IrWire.encode(code, format.value), 500)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                invalidate("USB_WRITE_FAILED")
                throw error
            } finally {
                io.unlock()
            }
        }

    override suspend fun learn(key: RemoteKey): IrCode =
        withContext(Dispatchers.IO) {
            check(io.tryLock()) { "DEVICE_BUSY" }
            val accepted = epoch.get()
            try {
                check(isReady) { "USB_NOT_READY" }
                val opened = checkNotNull(port) { "USB_NOT_READY" }
                mutableStatus.value = status.value.copy(learning = true)
                withTimeout(5_000) {
                    // The budget includes discarding leftovers from cancelled/previous learning.
                    opened.purgeHwBuffers(false, true)
                    currentCoroutineContext().ensureActive()
                    val buffer = ByteArray(64)
                    SerialLearning.read(key) {
                        currentCoroutineContext().ensureActive()
                        check(epoch.get() == accepted) { "USB_SESSION_CHANGED" }
                        val count = opened.read(buffer, 100)
                        buffer.copyOf(count)
                    }
                }
            } finally {
                if (epoch.get() == accepted) mutableStatus.value = status.value.copy(learning = false)
                io.unlock()
            }
        }

    override suspend fun setFrameFormat(format: IrFrameFormat) {
        check(io.tryLock()) { "DEVICE_BUSY" }
        try {
            withContext(NonCancellable) {
                dao.setting(SettingRow("ir_format", format.name))
                mutableFormat.value = format
            }
        } finally {
            io.unlock()
        }
    }
}
