# RFC：M1 设备调试端口

- 日期：2026-10-01
- 状态：已批准（用户在本会话回复“已确认”）
- 关联：[M1 任务单](../tasks/M1.md)
- 批准人／日期：用户，2026-10-01

## 问题

现有 IrTransmitter 只能发射，FrameSource 只能取帧，无法表达连接状态、学习、按键存储、标定与相机会话。若直接让 ViewModel 依赖 UsbSerialPort、CameraX 或 Room，会违反分层约束。

拟在 core:model 增加以下纯 JVM 类型与端口；保留现有 IrTransmitter、KeyExecutor、Frame、ScreenObserver、TraceStep 签名和全部 M0 行为。

## 提议的契约

```kotlin
enum class DeviceConnectionState {
    DISCONNECTED, PERMISSION_REQUIRED, CONNECTING, READY, ERROR
}

data class IrDeviceStatus(
    val connection: DeviceConnectionState,
    val deviceLabel: String? = null,
    val learning: Boolean = false,
    val errorCode: String? = null,
)

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

data class NormPoint(val x: Float, val y: Float)

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

enum class CameraSessionState {
    STOPPED, PERMISSION_REQUIRED, STARTING, READY, ERROR
}

interface CameraSession : FrameSource {
    val state: StateFlow<CameraSessionState>
    suspend fun start()
    suspend fun stop()
}
```

StateFlow 使用现有 kotlinx.coroutines，不引入 Android。需要存储的 value 类型加 Serializable，序列化枚举用名称。

## 行为约定

- deviceId 是 Android 适配层列举的设备标识，core 不解析 USB VID/PID；系统权限弹窗由 Android 层处理，core 只读取状态。connect 不意味着已获权限，不自动发键。
- learn 最多 5 秒；取消即取消读取；获得完整三字节才返回 IrCode，不自动保存。学习与发射在同一驱动会话互斥，断开后不能复发缓存操作。
- setFrameFormat 仅在无进行中的发射／学习时允许切换；成功设置才持久化，失败保留旧值。
- IrCodeStore.save 是明确的保存／覆盖动作；导入预览和用户冲突选择由调试用例先完成。saveAll 必须全量事务成功或全部回滚，调用前已完成冲突选择，不能逐条覆盖到一半后假称成功。
- KeyExecutor 的已学习码映射通过注入可更新快照读取；签名不变，运行时继续只有一个执行器。学习／导入不能靠重建执行器形成两条发射队列。
- ScreenCalibration 四点对应旋转后图像，rotationDegrees 取 0/90/180/270；imageWidth/Height 为该坐标系尺寸。适配器负责相机旋转、预览裁切和透视变换，不把 Bitmap 放进契约。
- CameraSession.start 必须在用户授权并启动的 Android 生命周期内运行；未有帧、权限缺失或会话停止时 latest 失败，不能返回过期帧并称实时。
- M1 不实现真实 ScreenObserver／Perception，不伪造 OCR 或高置信度状态。预览渲染和帧附件保存在 app 的 Android 适配层。
- 错误信息使用不含路径、密钥或请求头的稳定错误码；Coroutine CancellationException 不能吞掉后继续发送。

## 实现位置与影响

- core:agent 增加调试用例，组合以上端口及唯一 KeyExecutor；ViewModel 只调用用例。
- app 实现串口、CameraX、Room、系统权限／文档选择器和前台服务适配；预览组件只承载适配器提供的画面。
- core:navigation 保持全局串行执行、补真实会话的取消竞争测试。
- core:testing 提供设备／学习／相机 Fake，已有 24 个 M0 测试必须保留。
- 记忆层不改 Skill 契约；手动 trace 的附件关系用 app 存储模型，不污染自动 TraceStep。

## 验证

新增端口的授权拒绝、连接失败、学习分片／超时／取消、停止／断开、码表热更新、标定非法值与序列化回归均需测试。真实 USB 与 CameraX 只有用户真机验收后才能声称可用，具体步骤见 M1 任务单。

不批准这些端口时，不能通过绕过 core 用例把硬件直接接到 ViewModel。可继续讨论接口，但本提案批准前不修改 contracts.md 或代码。
