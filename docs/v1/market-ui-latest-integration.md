# 最新行情组件接入（2026-09-14）

主工程 `TalkToAI` 现使用本地 `kuikly-market-ui` 的可组合组件 API，SDK 版本标记为 `0.2.0`，含 2026-09-13 的主题、卡片、胶囊控件与榜单视觉优化。迁入了已有 `TalkToAI-market-ui-0.2` 工作目录中的数据与原生图表适配，保留主工程已有的对话页视觉修改。

## 依赖选择

上游合并后，`settings.gradle.kts` 按以下顺序选择源码：

1. `-PmarketUiDir=...` 显式指定的目录。
2. `market-ui` Git 子模块，固定到 `f8853f99dbc92c3b0b9fe7031c0d93c5516fb2bf`（已推送至上游 `codex/ui-polish-20260914` 分支）。

两个宿主模块均声明 `io.github.rogerin1900:market-ui:0.2.0`，由 composite build 替换为源码工程。前一轮视觉优化已合入上游 `main`；本轮指标单位布局与整数家数优化在上述新分支。默认子模块固定到已推送提交，不隐式使用同级未提交源码。需要联调时显式传入 `-PmarketUiDir=../kuikly-market-ui`。

独立 SDK 构建需要 `ANDROID_HOME`，或在所选 SDK 目录配置被 Git 忽略的 `local.properties`。本机已为同级 SDK 配置 Android SDK 路径。

## 页面接入

- 行情总览：`MarketDashboardView`，宿主注入真实页面宽度、字体缩放、亮暗主题、图表和 typed action。
- 个股详情：价格和成交量采用 `MarketMetricPanel`，区分加载、空、失败和成功，支持重试。
- AI 行情数据：经 `AiMarketDashboardAdapter` 转换；缺失总览区块使用 SDK 模拟示例并标注来源。
- Android：注册 SDK 原生控件与缓存面板，沿用宿主原生图表桥。

旧可编辑看板的专用宿主类及对应测试已移除，相关编辑、CSV 导入、布局保存入口由新行情页替代；设备上的历史文件未删除，旧实现可从 Git 历史恢复。

## 验证结果

以下命令使用 JDK 17 和本机 Android SDK 执行，通过：

```sh
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug --offline
bash scripts/verify-android-apk.sh androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :shared:dependencyInsight --dependency market-ui --configuration androidDebugCompileClasspath --offline
git diff --check
```

Lint 成功但存在既有弃用等警告。APK 验证检查了真实 DEX 类定义，包含 `MarketNativeControl` 和 `MarketNativeCachedPanel`。

APK：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

向已连接 V2507A 手机进行保留数据的覆盖安装时，系统返回 `INSTALL_FAILED_ABORTED: User rejected permissions`。未绕过确认、卸载应用或清除数据，因此本轮未验证设备端页面和交互。

## 原型布局对齐（2026-09-14）

根据实际截图反馈进一步调整 SDK 和宿主布局：移除宿主重复的行情子导航和长说明，将个股查询放到右上角；合并 SDK 标题与分段控件；默认字号下指数采用三列紧凑卡片，整卡可点击；统计采用双列摘要、环图和水平条形；板块卡片内置迷你趋势；排行使用带分隔线的紧凑行。模拟来源仍可见，完整来源和指数内容保留在可访问描述中。

宿主仅对 SDK 的紧凑图表载荷设置 `compact`，隐藏环图的全屏工具与重复图例；原有详细图表继续保留交互工具。迷你趋势使用涨跌色和淡色填充。

验证：主工程构建、共享层与 Android 单测、Lint 通过；SDK 单测和 API 检查通过。Pixel 8a API 35 模拟器安装、启动和滚动正常，资金流入排序后半导体位于首位。检查了 150% 系统字号下的首屏，指数降为两列、统计降为一列；验证后已恢复 100% 字号。没有据此宣称全设备或完整无障碍验收通过。

截图位于 `artifacts/ui-preview-2026-09-14/`：

- `market.png`：调整前实际页面。
- `market-aligned.png`：调整后首屏。
- `market-aligned-lower.png`：调整后板块与股票排行。
- `market-flow-selected.png`：资金流入排序。
- `market-font150.png`：150% 字体下的响应式布局。

## 上游提交与主分支合并

SDK 新分支：`codex/market-ui-prototype-alignment`。
优化提交：`b9e84f1`。
合并提交：`1a6dcd0bb948f37ad5cac3b0e82614da54d79e5d`，已推送至 `RogerIn1900/kuikly-market-ui` 的 `main`。

TalkToAI 子模块已切换到该远端提交，默认构建引用固定子模块。TalkToAI 自身的接入、宿主图表和既有页面修改仍保留在本地工作区；本次没有将这些宿主修改一并提交或推送。
