# TalkToAI 交互优化 2.1 验证记录

日期：2026-09-08。范围：Android 测试版；保留既有未提交修改，本轮未提交或推送 Git，未部署云端。

## 本轮实现

| 需求 | 实现与边界 | 实现文件 |
|---|---|---|
| 图表单选 Chip | 折线、柱状、饼状使用同一选中值；背景和文字独立读取实时状态；数据表显示开关独立于图形类型 | TalkToAiPager.kt |
| 旧按钮文字未复原 | 外观选项的勾选、文字和背景使用实时状态，不再捕获旧选择 | TalkToAiPager.kt |
| 点赞反馈 | 点击立即变为“已赞”并高亮，再点取消；点踩同样处理 | TalkToAiPager.kt、TalkToAiViewModel.kt |
| 点击图例显隐 | 折线/柱状/K线切换系列；饼图切换分类，可全部隐藏再恢复 | ChartInteractionHost.kt |
| 全屏与归位 | 共用原生图表容器；全屏有返回；退出还原原位置；归位解除横轴缩放限制并清除高亮 | ChartInteractionHost.kt |
| 行情图复用 | 继续使用现有 MPAndroidChart，K线与成交量同步窗口；成交量紧凑轴仅保留两个刻度，起点为零 | TalkMarketChartView.kt |
| 附件返回 | “添加附件”选择图片或 CSV/TXT，提供明确返回按钮；后续进入系统文件选择器 | KuiklyRenderActivity.kt |
| 输入框下方模型 | 展示当前 hy3，点击打开单选模型窗口；当前测试后端只开放一个模型，不虚构其他可用模型 | TalkToAiPager.kt、KRBridgeModule.kt |
| 侧边栏刷新 | 复用 Kuikly Refresh，下拉重新读取会话、插件及诊断状态，结束刷新并提示结果 | TalkToAiPager.kt、TalkToAiViewModel.kt |
| 长会话到底部 | 内容/视口变化时计算最新位置；恢复会话自动滚到底部；用户主动向上阅读时停止跟随 | TalkToAiPager.kt |

图表库官方依据：[MPAndroidChart 官方仓库](https://github.com/PhilJay/MPAndroidChart)，沿用项目已有依赖，无新增付费组件。

滚动修复依据：本地 Kuikly 2.27.0 原生 KRRecyclerView 的 canScrollImmediately 对超过最大滚动距离的指令做延后处理。原算法额外加 2 dp 导致越界；新算法在有效范围内留出 2 dp 像素取整余量。已新增正常、短内容、未布局、NaN 和无穷值单测。

## 自动化验证

执行命令（项目根目录）：

```sh
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug :androidApp:connectedDebugAndroidTest --no-daemon
```

后端目录 `backend/functions/talktoai-api` 执行 `npm test && npm run build`。

结果：Gradle BUILD SUCCESSFUL（41 秒），shared 15 项、Android JVM 20 项、模拟器 instrumentation 6 项全部通过；后端 28 项测试和 TypeScript 构建通过，共 69 项，失败 0。Lint 成功；`git diff --check` 通过。不得把测试夹具成功等同于实时行情授权或生产环境验证。

构建原始记录：`artifacts/runtime-2026-09-08/interaction-build-tests.log`。JUnit XML 位于 `shared/build/test-results/testDebugUnitTest`、`androidApp/build/test-results/testDebugUnitTest` 和 `androidApp/build/outputs/androidTest-results/connected/debug`。

交付：`artifacts/TalkToAI-v1-debug.apk`，已通过 ADB 安装启动；SHA-256：`9c9ce45fb7a54026773434fdca12d228d3b7ccb6d7a25d8319c77d9ce0997da2`。

## 模拟器交互证据

设备：emulator-5554 / Medium_Phone_API_36.1，Android 16，1080×2400。不是物理真机验证。

证据位于 `artifacts/runtime-2026-09-08/`：

- `interaction-single-selection.png`：主题、气泡、头像选中样式。
- `interaction-liked-single-chart.png`：已赞反馈、柱状单选。
- `interaction-chart-fullscreen.png`、`interaction-chart-hidden.png`：全屏返回、图例隐藏。
- `interaction-attachment-return.png`：附件返回入口。
- `interaction-model-picker.png`：单模型选择入口。
- `interaction-sidebar-refresh.png`：手势刷新完成提示。
- `interaction-restored-bottom.png`：恢复内容后自动显示末尾状态栏。

## 剩余边界

- 当前仅 hy3 可用；真正跨模型切换需后端开放并验证额外模型。
- 行情仍可能为带明确标记的固定测试数据，不是实时生产数据源验收。
- K线与成交量保持同步固定窗口，暂未实现两图联动手势缩放；归位显示全部数据可用。
- 点赞是本地当前会话 UI 状态，不代表模型训练反馈上传或跨设备持久化。
- 本轮未完成物理真机、多机型、无障碍或长时间性能压力验证。调试模拟器首次启动存在掉帧日志，不能据此宣称达到发布性能标准。
