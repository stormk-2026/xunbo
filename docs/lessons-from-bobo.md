# 旧项目 bobo 复盘

旧工程在 `/Volumes/DevData/Projects/openclaw/bobo`。App 源码已丢失（`~/Bobo_Bot` 不存在）。结论来自 `app-debug.apk` 反编译、`bobo-server/server.js`、OpenClaw 工作区，以及 2026-04-11 Discord「播控专家-播播」会话。

寻播复用已验证的硬件协议和按键学习，不复用 OpenClaw 链路，也不复用“看图写一串键”的决策方式。

## 旧架构

```
Discord / OpenClaw（Gemini 3 Flash 或 Kimi Vision）
    → HTTP localhost:3000
    → Node WebSocket 中转
    → 手机 App（com.stormg.boboautoctr）
        → USB IRTM 发红外
        → CameraX 拍一张 JPEG，Base64 回传
```

App 实际是执行器，没有本地感知和本地决策。

## 已验证、必须复用

### IRTM 串口

`IrSerialManager`：`usb-serial-for-android`，9600 8N1。

- 发射（旧代码，记为 `RAW4_INV`）：4 字节 `[userCode1, userCode2, cmd, (~cmd) & 0xFF]`
- 学习：最多等 5 秒，读 3 字节 `[userCode1, userCode2, cmd]`
- USB 权限：`OpenResult { OPENED, PERMISSION_REQUESTED, NO_DEVICE, FAILED }`；API 31+ 的 PendingIntent 需要可变 flag
- 会话里机顶盒菜单确实移动过，也误触发过重启，说明这块模块和这套发射帧在当时的盒子上有效

YS-IRTM 常见文档发射帧是 `A1 F1 U1 U2 CMD`。M1 必须把帧格式做成可切换，两种都在真机上试，通过后再冻结默认值。未经真机验证不要改默认。

### 已学习按键

Room 库文件名 `ir_keys.db`，表：

```
ir_keys(id INTEGER PK AUTOINCREMENT, name TEXT, userCode1 INT, userCode2 INT, commandCode INT, createdAt INTEGER)
```

旧会话里已录入：上、下、左、右、电源、确定、返回、设置、音量+、音量-。标准名冻结为：

`POWER UP DOWN LEFT RIGHT OK BACK HOME MENU VOL_UP VOL_DOWN`

旧 App 是 debug 包。若 OnePlus `<DEVICE_SERIAL>` 上还装着，导出：

```bash
adb -s <DEVICE_SERIAL> exec-out run-as com.stormg.boboautoctr cat databases/ir_keys.db > fixtures/ir/ir_keys.db
```

寻播的 Room 表结构必须能导入这份库，避免重新对码。

### 必须保留的产品能力

- 按键学习 / 试发 / 删除界面
- 前台服务保活（旧会话里 App 一进后台就断 WebSocket）
- 红外发送串行排队（旧 App 的 `TaskExecutor` 是对的；错在上游一次塞进超长序列）

## 必须丢掉

- OpenClaw、Discord、Node 中转、局域网 WebSocket
- ADB `screencap` 当主感知
- 每步把 JPEG 传给云端 VLM
- `ir_sequence` 作为自动任务原语
- 旧 `SOUL.md` 里的“刷量”目标

## 失败模式 → 新约束

| 现象 | 根因 | 新约束 |
|---|---|---|
| 单步约 39 秒 | 拍照上传 + 云端看图 | 感知在端上；Jev 只吃文本；按可信画面变化决策，不逐相机帧调用（2026-09-30 修订） |
| 焦点漂出目标，循环列表绕回第一项 | 一次下发 11 次甚至 38 次 DOWN | 禁止开环连发；一键一校验；检测循环列表后反向走 |
| 机顶盒被重启 | 进二级菜单后焦点默认在「重启机顶盒」，未核对就 OK | OK 前文字必须匹配；`DangerGuard` 拦截危险项 |
| 谎报已设成 50Hz，实际仍是 60Hz | 把高亮焦点当成单选实心点 | `focus` 与 `selected` 分字段；完成只认 `PostconditionChecker` |
| HOME/UP 丢失或跳两格 | 盒子忙时丢码，或一次键被当成连发 | 自适应等稳定；0 格重试一次后 Stuck；M1 做 50 次可靠性统计 |
| 错误弹窗 10019 | 无弹窗策略 | `popup` 高则先 BACK |
| 照片旋转 90°，电视只占一半 | 手持竖拍 | 横置支架 + 四角标定 + 只 OCR 电视区域 |
| 任务被做成“只会播节目” | 真实需求大量是设置菜单 | `Task` 通用：导航 + 动作 + 后置条件 + 重复 N 次 |
| 后两遍循环乱套、黑屏、关机 | 模型在失败后继续盲打，甚至发 POWER | 步数/时长上限；停止立即清空队列；POWER 不进自动探索动作空间 |

## 旧照片怎么用

`bobo/.openclaw/workspace/` 里的 `bobo_step*.png`、`set_50hz_*.png`、`disp_*.png`、`audio_*.png` 是实拍设置页，文字可读，但带桌面背景且可能旋转。M0/M2 可以拷进 `fixtures/legacy-bobo/` 做标注练习，**不能**当作已经矫正好的相机输入。新 fixtures 必须按横置支架重拍。

根目录那张 launcher 截图是当贝/ZNDS 风格桌面，作为 M0 FakeTv 的默认场景。

## 合规

旧人设把系统写成刷播放量的执行官。批量刷量违反 IPTV 和视频平台用户协议。寻播只帮助用户操作自己的盒子和自己的账号，产品话术和任务设计都不围绕刷量。
