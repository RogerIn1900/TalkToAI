# 启动失败诊断（2026-09-09）

## 已确认原因

Android Studio 日志 `/Users/jerry/Library/Logs/Google/AndroidStudio2025.3.2/idea.log`：

- 45629 行，16:53:08：Launching on device vivo-v2507a（连接的物理设备），不是约定模拟器。
- 45695–45701 行，16:53:09：`INSTALL_FAILED_ABORTED: User rejected permissions`；失败发生在安装阶段。

因此本次报错不是已证实的应用崩溃。没有绕过手机安装权限，没有更改启动生产代码或清除用户数据。

## 处理

使用显式目标 `adb -s emulator-5554` 构建、安装和验证；Android Studio 运行时应选择 `Medium_Phone_API_36.1`。需要真机时由用户在手机端允许安装。

## 验证边界

- Gradle 构建成功；`:market-ui:testDebugUnitTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest` 均为 UP-TO-DATE，本次复用已有 48 项 JVM 通过结果，不称为重新执行。
- 应用 APK 和测试 APK 均 `install --no-streaming -r` 成功；不卸载、不清数据。
- 修复前显式冷启动已返回 `Status: ok`，2756ms 是 Activity 启动耗时，不是完整页面渲染性能；已查看实际对话页面。
- 最终设备测试与冷启动证据见 `artifacts/runtime-2026-09-09/startup-*.log/png`。
- 不证明真机可安装，不证明所有设备启动正常；原真机权限拒绝需用户处理。
