package com.stormg.xunbo.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RemoteKey {
    POWER,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    OK,
    BACK,
    HOME,
    MENU,
    VOL_UP,
    VOL_DOWN,
}

@Serializable
enum class IrFrameFormat { RAW4_INV, A1F1 }

@Serializable
data class IrCode(
    val name: RemoteKey,
    val userCode1: Int,
    val userCode2: Int,
    val commandCode: Int,
)

@Serializable
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

@Serializable
data class UiElement(
    // "e0", "e1", ... 本帧内稳定
    val id: String,
    // 已做空白折叠，保留中文原文
    val text: String,
    val bounds: NormRect,
    val row: Int? = null,
    val col: Int? = null,
    val confidence: Float,
)

@Serializable
enum class SelectionKind { RADIO, CHECKBOX, SWITCH }

@Serializable
data class Focus(
    val elementId: String?,
    val text: String,
    val bounds: NormRect?,
    val confidence: Float,
)

@Serializable
data class Selection(
    val elementId: String?,
    val text: String,
    val kind: SelectionKind,
    val on: Boolean,
    val confidence: Float,
)

@Serializable
enum class PageType {
    LAUNCHER,
    TAB_BAR,
    SETTINGS_LIST,
    APP_HOME,
    DETAIL,
    EPISODE_LIST,
    SEARCH,
    KEYBOARD,
    PLAYER,
    POPUP,
    LOADING,
    UNKNOWN,
}

@Serializable
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

@Serializable
data class PerceptionThresholds(
    val minFocus: Float = 0.55f,
    val minSelection: Float = 0.55f,
    val minParse: Float = 0.50f,
    val minOcr: Float = 0.45f,
)

@Serializable
enum class TaskAction { OPEN, PLAY, SET, TOGGLE }

@Serializable
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

@Serializable
sealed interface Postcondition {
    @Serializable
    data class TextVisible(val text: String) : Postcondition

    @Serializable
    data class ActiveTab(val text: String) : Postcondition

    @Serializable
    data class FocusOn(val text: String) : Postcondition

    @Serializable
    data class Selected(val text: String, val on: Boolean) : Postcondition

    @Serializable
    data class PageIs(val type: PageType) : Postcondition
}

@Serializable
data class TaskLimits(
    val maxSteps: Int = 60,
    val maxDurationMs: Long = 180_000L,
)

@Serializable
data class Task(
    val id: String,
    val intent: TaskIntent,
    val postconditions: List<Postcondition>,
    val limits: TaskLimits = TaskLimits(),
)

@Serializable
enum class NextTargetKind {
    ELEMENT, // id = "e3"
    SCROLL_DOWN,
    SCROLL_RIGHT,
    SEARCH,
    OK_CURRENT,
    BACK,
    HOME,
    WAIT,
}

@Serializable
data class NextTarget(
    val kind: NextTargetKind,
    val elementId: String? = null,
)

@Serializable
data class DecisionResult(
    val pageType: PageType,
    val nextTarget: NextTarget,
    val arrived: Float,
    val popup: Float,
    val onPath: Float,
    val confidence: Float,
    // 如 "jev-1.13.0"，可空
    val rawModel: String? = null,
)

interface Decider {
    suspend fun decide(stateText: String): DecisionResult
}

@Serializable
data class DecisionThresholds(
    val arrived: Float = 0.85f,
    val popup: Float = 0.70f,
    val onPathLow: Float = 0.30f,
    val minConfidence: Float = 0.50f,
)

@Serializable
enum class MoveOutcome {
    ALREADY_THERE,
    STEPPED, // 焦点按预期移动 1 格
    NO_CHANGE, // 本次按键后可信观察为 0 格；重试归控制器
    OVERSHOOT, // ≥2 格或方向错误
    WRAPPED, // 循环列表绕回
    DANGER_BLOCKED,
    OK_MISMATCH, // 焦点文字不匹配，拒绝 OK
    STUCK,
}

@Serializable
data class NavStepResult(
    val outcome: MoveOutcome,
    val key: RemoteKey?,
    val before: Focus?,
    val after: Focus?,
    val waitedMs: Long,
)

@Serializable
enum class TaskStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    STUCK,
    STOPPED,
    LIMIT_REACHED,
}

@Serializable
data class TaskResult(
    val status: TaskStatus,
    val steps: Int,
    val elapsedMs: Long,
    val reason: String? = null,
)

interface FocusNavigator {
    suspend fun moveTo(
        target: UiElement,
        observe: ScreenObserver,
    ): NavStepResult

    suspend fun pressOkIfSafe(
        expectedText: String,
        observe: ScreenObserver,
    ): NavStepResult
}

@Serializable
data class SkillStep(
    val key: RemoteKey,
    val landmark: String?,
)

@Serializable
data class Skill(
    val deviceFingerprint: String,
    val goalKey: String,
    val hint: String? = null,
    val steps: List<SkillStep>,
    val createdAtMs: Long,
)

@Serializable
data class PageEdge(
    val fromFingerprint: String,
    val key: RemoteKey,
    val toFingerprint: String,
)

@Serializable
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
    suspend fun find(
        deviceFingerprint: String,
        goalKey: String,
    ): Skill?

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

interface IntentParser {
    fun parse(raw: String): Task
}

interface PostconditionChecker {
    fun satisfied(
        task: Task,
        screen: ScreenState,
    ): Boolean
}

interface TaskRunner {
    suspend fun run(task: Task): TaskResult

    fun stop()
}

interface DangerGuard {
    fun isDanger(text: String): Boolean
}
