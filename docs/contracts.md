# 契约：`core:model`

本文件冻结类型、端口和规则。2026-09-30 已同步批准的 `rfc/2026-09-30-m0-contract-clarifications.md`。Codex 按此实现 `core:model`（纯 JVM）。要改枚举、字段或接口，先写 `docs/rfc/`。

包名：`com.stormg.xunbo.core.model`。数据类用 `data class`，枚举用 `enum class`，时间用 `Long` 毫秒。矩形一律为透视矫正后的归一化坐标，原点左上，范围 `[0, 1]`。

---

## 1. 按键与帧格式

```kotlin
enum class RemoteKey {
    POWER, UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, MENU, VOL_UP, VOL_DOWN
}

enum class IrFrameFormat { RAW4_INV, A1F1 }

data class IrCode(
    val name: RemoteKey,
    val userCode1: Int,
    val userCode2: Int,
    val commandCode: Int,
)
```

- `RAW4_INV`：写 `[u1, u2, cmd, (~cmd) and 0xFF]`（旧 App 已验证）。
- `A1F1`：写 `[0xA1, 0xF1, u1, u2, cmd]`（部分 IRTM 文档）。
- 自动探索的动作空间不含 `POWER`、`VOL_UP`、`VOL_DOWN`。这三键只给调试台和用户明确点名的任务。
- `IrCode.name` 与旧库 `ir_keys.name` 的对应：`确定/OK/ENTER` → `OK`，`返回/BACK` → `BACK`，`设置/MENU` → `MENU`，其余按英文名或中文别名解析。导入时写一个 `IrKeyNameParser`，未识别的名字跳过并记日志，不要抛死。

---

## 2. 几何与 UI 元素

```kotlin
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

data class UiElement(
    val id: String,          // "e0", "e1", ... 本帧内稳定
    val text: String,        // 已做空白折叠，保留中文原文
    val bounds: NormRect,
    val row: Int? = null,
    val col: Int? = null,
    val confidence: Float,
)

enum class SelectionKind { RADIO, CHECKBOX, SWITCH }

data class Focus(
    val elementId: String?,
    val text: String,
    val bounds: NormRect?,
    val confidence: Float,
)

data class Selection(
    val elementId: String?,
    val text: String,
    val kind: SelectionKind,
    val on: Boolean,
    val confidence: Float,
)
```

`id` 按阅读顺序分配：先上后下、先左后右。同一帧内不得重复。

---

## 3. 页面与 ScreenState

```kotlin
enum class PageType {
    LAUNCHER, TAB_BAR, SETTINGS_LIST, APP_HOME, DETAIL,
    EPISODE_LIST, SEARCH, KEYBOARD, PLAYER, POPUP, LOADING, UNKNOWN
}

data class ScreenState(
    val pageType: PageType,
    val tabs: List<String> = emptyList(),
    val activeTab: String? = null,
    val focus: Focus? = null,
    val selected: List<Selection> = emptyList(),
    val elements: List<UiElement> = emptyList(),
    val hints: List<String> = emptyList(),
    val fingerprint: String,
    val capturedAtMs: Long,
    val focusConfidence: Float = focus?.confidence ?: 0f,
    val parseConfidence: Float = 0f,
)
```

不变量：

- `focus` 是高亮/焦点框，`selected` 是生效中的单选/勾选/开关。两者不能互相替代。
- `elements` 最多保留 30 个给决策层（实现可以先识别更多，序列化时截断）。截断优先保留：焦点、选中项、tabs、靠近焦点的卡片。
- `fingerprint`：对 `pageType + activeTab + 排序后的 elements.text` 做稳定哈希（UTF-8，SHA-256 取前 16 hex）。不要用整图感知哈希。
- `parseConfidence` / `focus.confidence` / `Selection.confidence` 低于 `PerceptionThresholds` 时，上游必须当“未知”，不能当事实。

```kotlin
data class PerceptionThresholds(
    val minFocus: Float = 0.55f,
    val minSelection: Float = 0.55f,
    val minParse: Float = 0.50f,
    val minOcr: Float = 0.45f,
)
```

---

## 4. 任务

```kotlin
enum class TaskAction { OPEN, PLAY, SET, TOGGLE }

data class TaskIntent(
    val raw: String,
    val title: String? = null,
    val episode: Int? = null,
    val hintTab: String? = null,
    val hintPath: List<String> = emptyList(),
    val action: TaskAction,
    val targetValue: String? = null,
    val repeat: Int = 1,
)

sealed interface Postcondition {
    data class TextVisible(val text: String) : Postcondition
    data class ActiveTab(val text: String) : Postcondition
    data class FocusOn(val text: String) : Postcondition
    data class Selected(val text: String, val on: Boolean) : Postcondition
    data class PageIs(val type: PageType) : Postcondition
}

data class TaskLimits(
    val maxSteps: Int = 60,
    val maxDurationMs: Long = 180_000L,
)

data class Task(
    val id: String,
    val intent: TaskIntent,
    val postconditions: List<Postcondition>,
    val limits: TaskLimits = TaskLimits(),
)
```

`goalKey` 生成规则（Skill 主键的一半）：

```
lowercase(action) + ":" + (title ?: targetValue ?: raw)
    + optional("|ep=" + episode)
    + optional("|tab=" + hintTab)
```

例：`play:小猪佩奇|ep=23|tab=教育`，`set:1080P_50Hz`。

---

## 5. 决策

```kotlin
enum class NextTargetKind {
    ELEMENT,          // id = "e3"
    SCROLL_DOWN,
    SCROLL_RIGHT,
    SEARCH,
    OK_CURRENT,
    BACK,
    HOME,
    WAIT,
}

data class NextTarget(
    val kind: NextTargetKind,
    val elementId: String? = null,
)

data class DecisionResult(
    val pageType: PageType,
    val nextTarget: NextTarget,
    val arrived: Float,
    val popup: Float,
    val onPath: Float,
    val confidence: Float,
    val rawModel: String? = null,   // 如 "jev-1.13.0"，可空
)

interface Decider {
    suspend fun decide(stateText: String): DecisionResult
}

data class DecisionThresholds(
    val arrived: Float = 0.85f,
    val popup: Float = 0.70f,
    val onPathLow: Float = 0.30f,
    val minConfidence: Float = 0.50f,
)
```

`stateText` 由 `JevStateBuilder` 生成，必须包含且只包含：

1. 任务摘要（raw、title、episode、hintTab、action、targetValue、repeat）
2. 固定策略短文（见下）
3. 最近最多 8 步：`key / focus.text / activeTab / pageType`
4. 当前 ScreenState 压缩文本：tabs、activeTab、focus、selected、elements（≤30）

禁止塞入整段原始 OCR 垃圾、图片、API key。

固定策略短文（原文可微调，语义不能少）：

```
不在桌面且任务需要桌面入口时先考虑 HOME。
用户给了栏目 hint 就先对齐 tab 再进内容。
当前页出现目标标题则把焦点移过去，匹配后再 OK。
弹窗优先 BACK。
危险项（重启/恢复出厂/购买/支付等）禁止 OK。
同键一次重试后画面和焦点仍不变则停止并报告卡住。
arrived 只表示候选，最终由后置条件判定。
不要输出方向键，只选择可见元素或 scroll/search/ok_current/back/home/wait。
```

Jev 问题名冻结为：`page_type`、`next_target`、`arrived`、`popup`、`on_path`。`next_target` 的 criteria key 为 `e0..eN` 加上 `scroll_down`、`scroll_right`、`search`、`ok_current`、`back`、`home`、`wait`。解析到未知 key → 视为失败，走保守策略。

---

## 6. 导航与执行结果

```kotlin
enum class MoveOutcome {
    ALREADY_THERE,
    STEPPED,          // 焦点按预期移动 1 格
    NO_CHANGE,        // 本次按键后可信观察为 0 格；重试归控制器
    OVERSHOOT,        // ≥2 格或方向错误
    WRAPPED,          // 循环列表绕回
    DANGER_BLOCKED,
    OK_MISMATCH,      // 焦点文字不匹配，拒绝 OK
    STUCK,
}

data class NavStepResult(
    val outcome: MoveOutcome,
    val key: RemoteKey?,
    val before: Focus?,
    val after: Focus?,
    val waitedMs: Long,
)

enum class TaskStatus {
    RUNNING, SUCCEEDED, FAILED, STUCK, STOPPED, LIMIT_REACHED
}

data class TaskResult(
    val status: TaskStatus,
    val steps: Int,
    val elapsedMs: Long,
    val reason: String? = null,
)
```

`FocusNavigator` 端口：

```kotlin
interface FocusNavigator {
    suspend fun moveTo(target: UiElement, observe: ScreenObserver): NavStepResult
    suspend fun pressOkIfSafe(expectedText: String, observe: ScreenObserver): NavStepResult
}
```

`pressOkIfSafe`：文字不匹配或 `DangerGuard.isDanger(focus.text)` 为真时，不得发 OK。

---

## 7. 文字匹配与危险项

```kotlin
object TextMatch {
    fun normalize(s: String): String
    fun matches(expected: String, actual: String): Boolean
    fun matchesEpisode(expected: Int, actual: String): Boolean
}

interface DangerGuard {
    fun isDanger(text: String): Boolean
}
```

`normalize`：去空白、全角转半角、英文小写、去掉常见标点。

`matches`：

1. normalize 后非空且完全相等 → true
2. 一方包含另一方，且较短一方长度 ≥ 2、不是纯数字 → true（避免单字和数字子串误匹配）
3. 数字编号由独立 `matchesEpisode` 完整解析 `第23集` / `23` / `23集` 并比较数值，仅当 `TaskIntent.episode != null` 时调用；23 不匹配 123
4. 其他 → false

默认危险子串（包含即危险，normalize 后再比）：

`重启, 恢复出厂, 出厂设置, 关机, 关闭系统, 格式化, 清除, 清空, 删除, 卸载, 重置, 购买, 订购, 开通, 支付, 付费, 续费, 会员`

另：匹配到 `¥`、`￥`，或正则 `\d+(\.\d+)?\s*元`、`元/月`、`元/季` → 危险。

---

## 8. 技能、图、轨迹

```kotlin
data class SkillStep(
    val key: RemoteKey,
    val landmark: String?,
)

data class Skill(
    val deviceFingerprint: String,
    val goalKey: String,
    val hint: String? = null,
    val steps: List<SkillStep>,
    val createdAtMs: Long,
)

data class PageEdge(
    val fromFingerprint: String,
    val key: RemoteKey,
    val toFingerprint: String,
)

data class TraceStep(
    val index: Int,
    val capturedAtMs: Long,
    val screen: ScreenState,
    val decision: DecisionResult?,
    val key: RemoteKey?,
    val nav: NavStepResult?,
    val perceiveMs: Long = 0,
    val decideMs: Long = 0,
    val irMs: Long = 0,
    val waitMs: Long = 0,
    val note: String? = null,
)
```

M0 不落盘。M1 起 `TraceStep` 可附缩略图路径，字段以后用 RFC 加，不要在 M0 引入 Android Uri。

---

## 9. 端口（硬件与感知，M0 用 Fake）

```kotlin
interface IrTransmitter {
    val isReady: Boolean
    suspend fun send(code: IrCode)
}

interface KeyExecutor {
    suspend fun send(key: RemoteKey)
    fun cancelPending()
}

interface Frame {
    val capturedAtMs: Long
    // 像素不进 JVM 契约。Fake 用 id 表示一帧逻辑画面。
    val fixtureId: String
}

interface FrameSource {
    suspend fun latest(): Frame
}

interface ScreenObserver {
    suspend fun waitUntilStable(): ScreenState
    suspend fun current(): ScreenState
}

interface Perception {
    fun parse(frame: Frame): ScreenState
}

interface SkillStore {
    suspend fun find(deviceFingerprint: String, goalKey: String): Skill?
    suspend fun save(skill: Skill)
}

interface TraceSink {
    fun append(step: TraceStep)
    fun all(): List<TraceStep>
}

interface Clock {
    fun nowMs(): Long
    suspend fun sleep(ms: Long)
}
```

`KeyExecutor` 必须串行。`cancelPending()` 在用户停止时调用：队列清空，正在发送的键允许发完，之后不再发。

`waitUntilStable`：页面 fingerprint 不含焦点，不得用它单独判断视觉稳定；真实实现比较连续帧变化，Fake 使用逻辑画面和 FakeClock。M0 的 Fake 用“按键后推进一帧”模拟。真实实现（M1/M2）用帧差及可信焦点，超时仍返回当前帧并降低 `parseConfidence`。

---

## 10. Agent 端口

```kotlin
interface IntentParser {
    fun parse(raw: String): Task
}

interface PostconditionChecker {
    fun satisfied(task: Task, screen: ScreenState): Boolean
}

interface TaskRunner {
    suspend fun run(task: Task): TaskResult
    fun stop()
}
```

M0 的 `IntentParser` 可以是规则：包含“教育”则 `hintTab=教育`；包含“小猪佩奇”则 title 对应；否则用 raw。完整中文意图解析是 M6 的事。

`PostconditionChecker` 对非空列表中的每条 `Postcondition` 都要成立才算成功。ActiveTab 要求 parseConfidence 达标，activeTab 与目标规范化后非空且完全相同。`Selected` 只看 `screen.selected`，不看 `focus`。

---

## 11. FakeTv（测试场景）

`core:testing` 提供一个可编程的机顶盒：

- 页面是稀疏网格：每个格子有 `text`、可选 `selected`、可选 `danger`
- 焦点在格子上，方向键按 1 格移动；越出边界时：若 `wrap=true` 则循环，否则停在原地
- 默认场景 `DangbeiHome`：顶栏 tabs = `我的, 发现, 精品, 影音, 抖音专区, 教育, 游戏, 应用, 管理, 福利`；当前 `精品`；焦点在一张内容卡片上。左右在顶栏移动（当焦点在 tab 上时），OK 进入该 tab
- `SettingsList` 场景留给 M3 测试，M0 可以先放数据类，不必做完

Fake 实现 `IrTransmitter + FrameSource + Perception`：send 改变内部焦点，`parse` 直接吐 `ScreenState`，不解码图片。

---

## 12. 序列化

对需要写入 trace 或将来入 Room 的类型，用 `kotlinx.serialization` 加 `@Serializable`。枚举用名字，不靠 ordinal。M0 至少能把 `ScreenState`、`DecisionResult`、`TraceStep`、`Skill` 编成 JSON 再解开，字段一致。

## 13. M0 执行语义

- moveTo 一次最多发送一键并等待观察；NO_CHANGE 重试由控制器管理，最多一次。每次发送（含重试）计步并独立写 trace。
- 所有挂起操作受任务剩余时长约束；Clock.nowMs 为单调时钟，捕获时间独立使用墙上时间。WAIT 不计按键步数但占时长。
- stop 使当前任务失效并取消挂起观察／决策、清空队列；已开始发送允许收尾，旧协程不能重新入队；不允许并行 run。
- OK 前重新获取可信焦点，使用任务或已确定目标的预期文字，不允许无条件使用当前焦点自证匹配。
- 低置信度不推断成功；OVERSHOOT／WRAPPED 在 M0 停止；循环依据位置和历史，不仅依据同名文字。
- DangerGuard 在去标点前检查金额符号。
- FakeTv 实现 IrTransmitter／FrameSource／Perception；FakeScreenObserver 组合感知；真实串行执行器驱动 Fake。
- 当前 NextTargetKind 用于 M0 目标导航。M3 的逐键 Jev 实验按 architecture.md 另行冻结单动作协议，不允许方向键序列。

## M1 设备调试端口（2026-10-01 已批准）

按 [M1 RFC](rfc/2026-10-01-m1-device-debug-ports.md) 增补设备会话、码表、归一化标定、相机会话端口。原有接口保持不变；类型定义在 `DevicePorts.kt`，行为边界以 RFC 为准。
