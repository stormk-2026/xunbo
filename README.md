# 寻播（Xunbo）

手机对着电视看画面，用 USB 红外控制机顶盒。用户只给目标，系统自主寻找并逐键验证。

当前状态：**M1 代码已实现，待用户真机验收**。已有 USB 红外单键调试、学习／导入、CameraX 取景与四角标定、前台会话及记录导出；保留 M0 的 FakeTv 离线闭环。尚未接入 OCR、Jev 或自动任务。

## 构建与测试

使用 Android Studio 打开本目录，Gradle JDK 选择内置 JBR（本机为 21），SDK 安装 Android 36。Java/Kotlin 字节码目标为 17，minSdk 26。

```bash
./gradlew ktlintCheck lint test assembleDebug
```

本机命令行没有系统 JDK 时：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew ktlintCheck lint test assembleDebug
```

`local.properties` 的 sdk.dir 由 Android Studio 设置，不提交。首次构建需要下载依赖；单元测试本身不联网，不调用 Jev。

产物：`app/build/outputs/apk/debug/app-debug.apk`。JVM 闭环报告：`core/testing/build/reports/tests/test/index.html`。

不要用模拟器。由用户在 Android Studio 选择已连接的真机并手动 Run；操作顺序见 [M1 验收记录](docs/verification/M1.md)。

## 文档

- [文档入口](docs/README.md)、[编码约束](AGENTS.md)
- [工作链与 Harness](docs/HARNESS.md)、[M0 任务单](docs/tasks/M0.md)
- [M0 验收记录与操作步骤](docs/verification/M0.md)
- [M1 任务单](docs/tasks/M1.md)、[M1 验收记录与操作步骤](docs/verification/M1.md)

用户已批准进入 M1；M0 人工结论仍由用户填写。M1 真机验收通过后才进入 M2。当前产物用于单设备实验，不代表已验证真机或可商业发布。

## 仓库上传范围

仅提交源码、构建配置、Room schema、必要的 Android XML 和项目文档。API key、签名、local.properties、真实红外码库、数据库、相机采集、媒体、测试资源文件、trace、导出包和构建产物均留在本地，不进入 Git。Gradle wrapper JAR 是构建引导程序，保留以便正常构建。

协议回归使用测试源码内的人工合成向量，不使用真实遥控器码表。验收文档中的 evidence 日志及真实采集引用属于本地证据，仓库不包含这些文件。提交不添加自动生成工具署名。
