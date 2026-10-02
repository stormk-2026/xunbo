# RFC：M0 开工前契约与执行语义澄清

- 日期：2026-09-30
- 状态：已批准，M0 实施中
- 批准人／日期：用户，2026-09-30；会话指令“好的 那就来实施吧”
- 关联：[工作链与 Harness](../HARNESS.md)、[现行契约](../contracts.md)、[M0](../tasks/M0.md)

用户已批准实施。本文规则同步进入契约、架构和任务单。后续逐键 Jev 试验不在 M0 调用真实 API。

## 1. 明确“进入 tab”后置条件

建议给 Postcondition 增加 `data class ActiveTab(val text: String) : Postcondition`。

`进入教育` 使用 `ActiveTab("教育")`。checker 要求 parseConfidence 达标、activeTab 非空、规范化后与目标完全一致。已有 TextVisible、FocusOn 保持各自语义，不掺入任务特判。所有后置条件都成立才能成功；空后置条件不应自动成功。

原因：教育从初始帧即已可见，焦点到教育也不等于按 OK 激活。现有类型不能直接表达本阶段硬验收。

测试：初始可见为 false；焦点到达但 activeTab=精品 为 false；OK 后激活教育为 true；低解析置信度为 false；JSON 往返保留新类型。

## 2. 区分普通文字与集数匹配

建议保留 `matches(expected, actual)`，新增 `matchesEpisode(expected: Int, actual: String): Boolean`，仅在 TaskIntent.episode 非空时由调用方启用。

- 普通匹配保留规范化后完全相等、非纯数字短串长度至少 2 的包含匹配；规范化后空串不匹配。
- 纯数字不得依靠子串包含判定；单字完全相等仍匹配，单字包含不匹配。
- 集数采用完整格式解析 `23`、`23集`、`第23集`，数值必须相等；23 不得匹配 123，也不得从任意标题中摘数字。
- DangerGuard 对金额特征先检查原始／全半角统一后的文本，再做危险词归一化，避免标点清理删掉货币符号。

测试补充：23 对 123、空串、单字相等与包含、全角金额、普通模式不把第23集等同于纯数字23。不得调整现有置信度阈值。

## 3. 冻结单步、重试、计数和停止语义

建议现有端口签名不变，补充以下行为约定：

- `moveTo` 每次最多发送一键，发后等待稳定并返回；已在目标返回 ALREADY_THERE。不在内部走完整条路径或隐藏重试。
- NO_CHANGE 表示本次观察到零移动，修正目前“重试后仍 0 格”的注释。ExploreController 管理同一状态／目标／按键的最多一次重试，再次无变化即返回任务 STUCK；未能可信观察不等于零移动。
- 每次实际调用发送都计一步，包括重试、OK、BACK、HOME。任务前置检查不能消耗剩余发送预算后再补发。WAIT 和纯观察不增加按键数，但占用时长预算。
- 每键一条 TraceStep，重试单独记一条；零发送的观察记录 key=null。失败不能凭已有导航结果伪造发送成功。
- 单任务使用单调时钟衡量持续时间，墙上时间只用于记录。观察、决策和导航都受剩余任务时间限制；超时返回 LIMIT_REACHED，不再发送。
- stop 先使本次任务失效并清空待发队列，阻止并发生产者继续入队；仅已开始的发送可收尾。挂起观察／决策需可取消。新 run 建立新任务生命周期；不允许两个 run 并行操作设备。
- `cancelPending` 除清空队列外，必须与任务失效检查协作；不能只清队列、随后让旧协程再次塞键。
- 每次 OK 重新读取可信焦点，核对预期文字并执行 DangerGuard；不能把当前焦点文字本身无条件作为 expectedText。无可信目标即不发 OK。
- M0 对无法可靠确认的 OVERSHOOT／WRAPPED 保守停止并记 trace；不自动尝试无证据的纠偏路径。

固定决策策略里“两步不变优先 BACK 再 HOME”同步改为“一次重试后仍不变则停止并报告卡住”，避免与硬护栏相冲突。

测试：最多两次发送后 STUCK、最后一步禁止额外重试、队列停止与再次入队竞争、挂起调用超时、WAIT 到时退出、停止后晚到的决策不得发键、重复运行生命周期隔离、危险／低置信度／错误目标不发 OK。

## 4. 页面身份与稳定检测分开

ScreenState.fingerprint 继续用于页面身份，不把它作为焦点是否移动的充分证据。真实稳定检测由 Android 感知适配使用连续帧变化；导航比较前后可信焦点位置与文字。M0 使用 Fake 逻辑画面及 FakeClock 模拟稳定等待。

ScreenObserver 返回已经解析的 ScreenState，内部组合 FrameSource 和 Perception；agent 不再在其后额外 parse。达到等待上限但仍不稳定的画面不能作为允许下一键的可信证据。

测试：页面指纹相同但焦点变化仍可判定移动；低置信度帧不能判定稳定成功；帧源挂起受到任务超时约束。M1/M2 再用真实 fixtures 校验帧稳定，M0 不假称验证视觉算法。

## 5. Fake 与实现模块统一

- FakeTv 按契约承担 IrTransmitter、FrameSource、Perception；另用 Fake ScreenObserver 组合感知。发送经过同一个串行 KeyExecutor 实现，不由 FakeTv 绕过队列。
- 串行执行器可在 navigation 模块实现，依赖 model 中 IrTransmitter 和注入的按键码映射；M0 使用合成按键码，不读取硬件码库。
- FakeClock／FakeTv 在 testing；RuleStubDecider 在 decision；InMemorySkillStore／InMemoryTraceSink 在 agent。
- M0 AppContainer 仅保留容器入口，不引用 testing 或运行 Fake 演示；完整离线组装在 testing 集成测试内完成。App 仍显示 M0 无硬件提示。
- M0 Stub 只服务教育导航场景：activeTab 已到教育时报告 arrived 候选；否则可信焦点到教育时选 OK_CURRENT；否则选择教育目标。任意任务是否成功仍由 checker 判断。

这是对架构和任务单的统一，不新增 Gradle 模块、不引入 Android 依赖到 JVM 核心。

## 6. 影响与验证

| 层 | 影响 |
|---|---|
| 感知 | 明确稳定和页面身份的区别，保留现有阈值 |
| 决策 | Stub 优先级固定，模型候选不代替成功判定，卡住策略一致 |
| 导航 | 单次一键，重试交控制器，执行队列可测 |
| Agent | ActiveTab 检查、任务预算、取消和状态生命周期明确 |
| 记忆／轨迹 | 新后置条件可序列化；每次实际发送可审计；不提前实现 Skill 回放 |
| App | M0 保留空壳，不依赖测试模块 |

保留原 M0 七组测试并补上述失败路径与现行契约要求的四类 JSON 往返测试。离线交付仍运行 `./gradlew ktlintCheck lint test assembleDebug`；本 RFC 不变更任何验收阈值。M0 无真实图片 fixtures 回放；M1/M2 收集用户手动导出的数据后另开任务。

若不澄清：成功判定可能提前、集数匹配缺上下文、App 测试依赖冲突、隐式重试可能漏计，停止行为也无法获得一致的验收标准。
