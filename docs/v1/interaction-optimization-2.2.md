# 交互优化 2.2

## 改动

- 全屏按钮现在是切换操作：进入全屏后再次点击原按钮退出；保留“返回图表”。
- 对话和行情页面保留实例，不再在 Tab 切换时销毁重建。取消切回对话时强制滚到底部；恢复滚动使用无动画定位，保留主动阅读位置和图表浏览状态。首次恢复、新会话和新发送仍可以定位最新内容。
- 侧边栏下方独立区域直接展开历史会话列表，支持进行中/归档筛选；保留当前会话改名、导出、归档和删除操作。
- 多系列柱状图按类别中心左右排列，系列顺序与图例一致，不再共用同一个横坐标。边缘柱体纳入轴范围。
- 气泡离开描边模式时明确将边框宽度设为零，防止原生视图残留旧边框。

## 实现位置

- `shared/src/commonMain/kotlin/com/example/talktoai/talk/TalkToAiPager.kt`：页面保留、滚动、气泡、侧边栏。
- `shared/src/commonMain/kotlin/com/example/talktoai/talk/TalkToAiViewModel.kt`：Tab 和会话状态。
- `androidApp/src/main/java/com/example/talktoai/chart/ChartInteractionHost.kt`：全屏切换。
- `androidApp/src/main/java/com/example/talktoai/chart/TalkDataChartView.kt`：分组柱坐标规则。

## 验证

本轮执行：

```sh
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --offline --no-daemon
adb -s emulator-5554 shell am instrument -w com.example.talktoai.test/androidx.test.runner.AndroidJUnitRunner
```

结果：shared 15 项、Android JVM 22 项、instrumentation 7 项全部通过，共 44 项；Lint、APK 构建和 `git diff --check` 通过。新增测试覆盖 1–6 个系列的排列顺序、无重叠、类别中心对齐、非法空系列和全屏按钮再次点击归位。

过程问题：首轮全屏断言未等待 Dialog 异步关闭，调整为等待主线程空闲后通过；手工非空会话验证发现 Kuikly vfor 根节点不能是 vif，增加普通容器后重新构建并通过手工复验。空列表验证不能替代非空列表验证。

模拟器证据目录：`artifacts/runtime-2026-09-08/`。

- `interaction22-scroll-before.png` / `interaction22-scroll-after.png`：在长会话中间位置切至行情再切回，阅读位置一致。
- `interaction22-sessions.png`：底部直接展开非空会话及管理入口。
- `interaction22-outline.png` / `interaction22-soft.png`：描边切回柔和，旧边框清除。
- `interaction22-grouped.png`：两组数值在三个月份下并排显示。
- `interaction22-fullscreen.png` / `interaction22-exit-fullscreen.png`：再次点击“全屏”退出，仍保留柱状模式。
- `interaction22-build.log` / `interaction22-device-tests.log`：构建和设备测试原始记录。

APK：`artifacts/TalkToAI-v1-debug.apk`，已安装到 emulator-5554；SHA-256 为 `e980dbff73b1be7abfe1cbb2e83f59ad40bdf31d0064a2628bb7afe1893cdf88`。

## 验证边界

浏览位置保留范围是同一进程内的 Tab 往返，不承诺进程被杀后恢复像素级滚动位置。验证使用 Android 模拟器，未操作连接的物理设备或其他模拟器；多机型、旋转屏幕与长时间内存压力尚未验证。保留页面实例会保留更多视图内存，需要后续专项测量。

本轮不涉及云端部署、生产资源、股票交易或 Git 推送。图表样例明确为测试数据，不是实时行情验证。
