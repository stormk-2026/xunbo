package com.stormg.xunbo.core.model

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class DeviceConnectionState {
    DISCONNECTED,
    PERMISSION_REQUIRED,
    CONNECTING,
    READY,
    ERROR,
}

@Serializable
data class IrDeviceStatus(
    val connection: DeviceConnectionState,
    val deviceLabel: String? = null,
    val learning: Boolean = false,
    val errorCode: String? = null,
)

@Serializable
data class IrDeviceDescriptor(val id: String, val label: String)

interface IrDeviceSession : IrTransmitter {
    val status: StateFlow<IrDeviceStatus>

    suspend fun devices(): List<IrDeviceDescriptor>

    suspend fun connect(deviceId: String)

    suspend fun disconnect()

    suspend fun learn(key: RemoteKey): IrCode

    suspend fun setFrameFormat(format: IrFrameFormat)
}

interface IrCodeStore {
    val codes: StateFlow<Map<RemoteKey, IrCode>>

    suspend fun save(code: IrCode)

    suspend fun saveAll(codes: List<IrCode>)

    suspend fun delete(key: RemoteKey)
}

@Serializable
data class NormPoint(val x: Float, val y: Float)

@Serializable
data class ScreenCalibration(
    val topLeft: NormPoint,
    val topRight: NormPoint,
    val bottomRight: NormPoint,
    val bottomLeft: NormPoint,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
)

interface CalibrationStore {
    suspend fun load(): ScreenCalibration?

    suspend fun save(calibration: ScreenCalibration)
}

@Serializable
enum class CameraSessionState {
    STOPPED,
    PERMISSION_REQUIRED,
    STARTING,
    READY,
    ERROR,
}

interface CameraSession : FrameSource {
    val state: StateFlow<CameraSessionState>

    suspend fun start()

    suspend fun stop()
}
