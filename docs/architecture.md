# 寻播架构

## 一句话

手机相机对着电视，把一帧画面解析成 `ScreenState`。M0 用 Stub 选择目标；M3 将验证 Jev 根据自动提取的视觉状态选择一个动作或目标。用户仅提供观看目标与栏目提示，不填写操作 JSON。`FocusNavigator` 按几何关系一次按一颗红外键，并校验焦点是否按预期移动。走通的路径存成 Skill；下次回放，landmark 对不上就重新探索。

## 为什么这样拆

旧项目 bobo 把每一步都交给云端 VLM 看图再写下一串按键，单步约 39 秒，而且会开环连发、误点危险项、把高亮当成已选中。完整复盘见 [lessons-from-bobo.md](lessons-from-bobo.md)。

新链路把四件事分开：

| 角色 | 模块 | 做什么 | 不做什么 |
|---|---|---|---|
| 眼睛 | `perception` | OCR、焦点、选中态、页面指纹 | 不把图发给 Jev |
| 大脑（选目标） | `decision` | 把 `ScreenState` 压成文本，问 Jev 下一目标 | 不输出方向键序列 |
| 手脚（走过去） | `navigation` + `device` | 逐键闭环红外、危险项拦截 | 不猜“连按 11 次 DOWN” |
| 裁判 | `PostconditionChecker` | 用 OCR/选中态判定任务完成 | 不采信模型自述 |

播放和设置是同一套任务模型：导航到目标 → 执行动作 → 校验后置条件 → 可重复 N 次。

## 运行时数据流

```
语音或文字
    → IntentParser → Task(intent, postconditions, limits, repeat)
    → SkillStore 查找 (deviceFingerprint + goalKey)
        ├─ 命中 → ReplayController（逐段 landmark 校验）
        └─ 未命中或校验失败 → ExploreController
              循环：
                ScreenObserver.waitUntilStable()
                // ScreenObserver 内部通过 FrameSource + Perception 返回 ScreenState
                若 popup / 危险项 / 低置信度 → 本地策略（BACK / 停 / 重试）
                否则 Jev.decide(stateText) → next_target
                FocusNavigator.moveTo(target)  // 一键一校验
                记录 TraceStep 与 PageEdge
              arrived 候选 → PostconditionChecker
                通过 → 写 Skill，进入 RepeatLoop
                不通过 → 继续探索或 Stuck
```

性能预算（每个页面，不是每个按键）：

- 帧稳定 400–800ms
- OCR + 焦点 + 选中态 < 400ms
- Jev 原预算 100–500ms；尚未实测。后续允许每次可信画面变化后选择下一动作，不按相机帧率调用。
- 导航平均 3 次按键，每次红外+等待约 0.5s

以上仅是设计预算，不能视为实测或对用户承诺。熟路回放基本不调 Jev。

## Gradle 模块

M0 只建下面这些模块。Android 硬件实现从 M1 起放进 `app`，不要提前拆过多 Android library。

```
:app                 Android 应用。Compose UI、AppContainer、权限。M0 只有一个能跑的空壳和调试入口。
:core:model          JVM。契约类型、端口接口、文字匹配、危险词表。见 contracts.md。
:core:decision       JVM。JevStateBuilder、DecisionPolicy、RuleStubDecider。真 Jev 客户端 M3 再接。
:core:navigation     JVM。FocusNavigator、DangerGuard。M0 实现几何单步导航与串行执行器。
:core:agent          JVM。TaskRunner、ExploreController、ReplayController、PostconditionChecker、RepeatLoop。
:core:testing        JVM。FakeTv、FakeClock、FakeScreenObserver 和集成测试。RuleStubDecider 在 decision。
```

依赖方向只允许向下：

```
app → agent, decision, navigation, model
agent → decision, navigation, model
decision → model
navigation → model
testing → 以上所有 JVM 模块（仅 test 可见）
```

禁止：

- JVM 模块依赖 Android SDK、Compose、CameraX、Room、usb-serial
- UI 或 ViewModel 直接依赖串口、相机、Bitmap
- 新增 Hilt/Koin/Agent 框架

M1 起在 `app` 里实现：`IrtmSerialTransmitter`、`CameraFrameSource`、前台服务、Room（`ir_keys`、Skill、Trace）。接口先定义在 `core:model` 的端口里。

## 两级决策

Jev 一次请求并行问：

- `page_type`：页面类型
- `next_target`：`e0..eN` 或 `scroll_down` / `scroll_right` / `search` / `ok_current` / `back` / `home` / `wait`
- `arrived` / `popup` / `on_path`：noul，0–1

Jev **不能**输出按键序列。M0 仍以可见目标 + 本地几何导航验证闭环；后续 M3 对比“模型选目标”和“模型选一个方向／OK／BACK／WAIT”。具体新增枚举和协议在 M3 RFC 冻结，不提前接云。位置关系由手机计算，模型的 OK 必须经过本地目标匹配及危险项检查。

M3 接入门槛：在用户网络上至少 100 次代表性文本状态调用，记录模型版本、输入样本、P50/P95 延迟、超时率、动作正确率和 input_tokens；随后用真机测完整任务。云端 API key 置于自用 debug App 的方案不是商用密钥方案，商业发布另行设计，不扩大 M0 范围。

本地护栏（写死，不让模型改）：

- `popup > 0.7` → 先 BACK，再重新感知
- `next_target` 置信度 `< 0.5` 或解析失败 → BACK 或停止，不猜
- `on_path < 0.3` 连续两次 → 回退
- `arrived > 0.85` 仍必须过 `PostconditionChecker`
- 危险项禁止 OK
- 单任务最多 60 步，默认 180 秒

## 逐键闭环

`FocusNavigator.moveTo(target)`：

1. 若当前焦点已是目标且文字匹配 → 返回已到达，不按键
2. 按几何选一个方向（优先同行/同列）
3. `KeyExecutor.send(key)`，等待 `ScreenObserver.waitUntilStable()`
4. 比较焦点：期望“沿该方向移动 1 格”
   - 0 格：本次返回 NO_CHANGE；控制器单独重试一次并计步，仍 0 格 → Stuck
   - 1 格且方向正确：继续
   - ≥2 格或方向反了：OVERSHOOT，M0 停止并记录
   - 结合焦点位置与已观察路径确认绕回：WRAPPED，M0 停止；不能仅凭重名文字判断
5. 到达目标后，调用方若要按 OK，必须再跑一遍文字匹配和 `DangerGuard`

自动任务禁止调用“按键序列”接口。调试台允许用户手动连按，但那不是 Agent 路径。

## 记忆

三层，都不假设全国 launcher 长一样：

1. **设备指纹**：OCR 到的市场名/桌面 tab 集合 + 分辨率，不靠整图感知哈希
2. **Skill**：`deviceFingerprint + goalKey` → 有序步骤 + 每段 landmark
3. **PageGraph**：这台盒子上真实走过的 `fingerprint --key--> fingerprint`

回放不是盲打。每段先确认 landmark 还在；不在就从该段重新探索，成功后覆盖 Skill。

## 调试与 Trace

从 M1 开始，每一步写 `TraceStep`：矫正缩略图、ScreenState、决策输入输出、发出的键、按键前后焦点、各段耗时。App 提供导出 zip。用户把 zip 放到仓库外或 `traces/`（`traces/` 不进 Git 大文件）供审查。

M0 用内存里的 `InMemoryTraceSink`，至少能在单元测试里断言“从精品到教育”的按键序列。

## 里程碑

只做当前任务单。后面阶段列在这里是为了防止提前耦合。

| 阶段 | 目标 | 硬验收 |
|---|---|---|
| M0 | 多模块骨架、契约、FakeTv、mock 闭环 | `./gradlew test` 全绿；20 步内从精品走到教育 |
| M1 | IRTM 驱动、按键学习、CameraX、标定、前台服务、调试台 | 方向键 50 次中 ≥98% 正好移 1 格 |
| M2 | OCR、焦点、选中态、布局、指纹 | fixtures 焦点+选中态 ≥85%，单帧 <400ms |
| M3 | 真 Jev、逐键导航、危险护栏、后置条件 | 真机“设成 1080P 50Hz”10 次 9 次成功 |
| M4 | 探索循环 | “教育下播放小猪佩奇”10 次 7 次，平均 <60s |
| M5 | Skill 回放与自愈 | 同一目标第二次 <15s |
| M6 | 语音、选集、RepeatLoop | 播放和设置类各无人干预跑完 3 遍 |
| M7 | 搜索键盘、弹窗、第二套 launcher、可选 VLM | 任务单另开 |

## App 最小界面（分阶段出现）

- M0：能安装的空壳，设置页只显示“M0 mock，无硬件”
- M1：取景框、四角标定、红外学习/试按、实时“USB / 相机 / 队列”状态、停止按钮、导出 trace
- M3 起：任务输入框、当前焦点、下一目标、步数
- M6：语音按钮

不要在 M0 做完整产品 UI。
