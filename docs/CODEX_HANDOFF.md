# 交给 Codex

当前只开 **M0**。开工前 RFC 已于 2026-09-30 获用户授权并同步；必读 docs/HARNESS.md。自主逐键 Jev 试验放在 M3，不要求用户填写操作 JSON。把下面这些发过去即可（整仓也行，因为它会读根目录 `AGENTS.md`）。

必读：

1. `AGENTS.md`
2. `docs/architecture.md`
3. `docs/contracts.md`
4. `docs/lessons-from-bobo.md`
5. `docs/tasks/M0.md`（含可粘贴提示）

不要发、也不要让它做：M1–M7、旧 `bobo` 工程里的 OpenClaw 配置、从 APK 反编译出来的 Java。

用户侧仍可并行准备（不阻塞 M0）：

```bash
adb -s <DEVICE_SERIAL> exec-out run-as com.stormg.boboautoctr cat databases/ir_keys.db > fixtures/ir/ir_keys.db
```

以及确认 USB 红外模块还是同一块 IRTM。
