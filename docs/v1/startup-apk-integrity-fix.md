# 真机启动闪退：APK 不完整

## 证据与恢复

2026-09-09，vivo V2507A 真机在 20:29:13、20:29:15 启动 `com.example.talktoai` 时崩溃。异常为 `Unable to get provider androidx.core.content.FileProvider`，原因是 `ClassNotFoundException`。异常发生在系统安装 ContentProvider 阶段，早于看板页面初始化。

从手机实际安装路径拉取 APK：

| APK | 字节数 | SHA-256 |
| --- | ---: | --- |
| 闪退包 | 1156777 | `735dce6aa600ec5208a2d7cbd85f7b9cd4b07ff59069d464f427242f48884104` |
| 覆盖安装的完整包 | 8290639 | `c55d54f34b5711efd84ef2fcb9ea00066ff96bcf44c0acdc1bab9697ccef6f15` |

`apkanalyzer dex packages --defined-only` 确认闪退包缺少 `FileProvider`、`AppCompatDelegate`、`TalkDashboardView`、SDK `DashboardView` 的类定义。DEX 含类名引用并不表示该类已被打包。闪退包的具体生成与安装路径未确认，不能据此认定是某个 IDE 或构建工具导致。

使用此前已完成提交快照验证的完整 APK（App `fc3d683`、SDK `e348e84` / v0.2.0）执行 `adb -s 10AG7H0CSP00C0D install -r`，保留用户数据。覆盖后冷启动 `am start -W` 返回 `Status: ok`、`LaunchState: COLD`、`TotalTime: 753` ms。随后设备进程存活，新看板界面可读取；安装后的 APK 再次拉取，哈希与完整包一致。检查覆盖安装时刻之后的 crash buffer，未发现 TalkToAI 新崩溃。本次没有以更换包名或清除数据规避问题。

## 防止再次交付不完整包

新增 `scripts/verify-android-apk.sh`，检查 APK 中实际定义的启动组件和新版看板类，任一缺失即失败。GitHub Android workflow 在 APK 构建之后、上传产物之前执行该检查。此检查用于拦截已知缺包问题，不能取代全部运行时、设备或传递依赖验证。

实际执行：

- `bash -n scripts/verify-android-apk.sh`：通过。
- 对完整 APK 执行脚本：通过。
- 对从真机提取的原始闪退 APK 执行脚本：返回非零，正确报告上述四个缺失类。
- 真机覆盖安装与冷启动：成功；新看板可见。

脚本需要 `JAVA_HOME` 与 Android SDK；可通过 `APKANALYZER` 显式指定 SDK 的 apkanalyzer。示例：`./scripts/verify-android-apk.sh androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

本次未改动 App/SDK 业务代码，未纳入工作区其他未提交变更。完整包此前通过的测试记录见 [看板交付记录](editable-dashboard-delivery.md)；这些历史测试不作为本轮重新执行的测试报告。
