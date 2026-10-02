# 寻播文档

开工前先审阅 [工作链与 Harness 提案](HARNESS.md) 和其中链接的 RFC。两者已获用户确认并同步规范；当前实施 M0，不开放 M1–M7。

Codex 实现代码前先读这些文件，顺序如下。

1. [../AGENTS.md](../AGENTS.md) — 编码约束和不变量
2. [architecture.md](architecture.md) — 模块、数据流、里程碑
3. [contracts.md](contracts.md) — `core:model` 类型和接口
4. [lessons-from-bobo.md](lessons-from-bobo.md) — 旧项目失败教训
5. [tasks/](tasks/) — 当前里程碑任务单；一次只做一份
6. [verification/](verification/) — 验收记录
7. [rfc/](rfc/) — 契约变更提案

本轮用户已授权 Codex 同步规范；通常 Cursor 维护本目录和根目录 `AGENTS.md`。代码改动由 Codex 按任务单提交。
