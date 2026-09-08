# Android 图表库评估（V1）

评估日期：2026-09-07。范围是 Kuikly Android 客户端中的分时、K 线和成交量图，不包含交易能力。

| 方案 | 能力与分发 | Kuikly 接入成本 | 风险 | V1 结论 |
| --- | --- | --- | --- | --- |
| Kuikly `Canvas` | 与现有跨端 UI 同栈；坐标轴、图例、K 线和成交量需自行绘制 | 低 | 后续缩放、十字光标和大数据量优化需继续实现 | 已从行情主图移除，避免继续维护手绘 K 线 |
| [Vico](https://github.com/patrykandpatrick/vico) | Apache-2.0；官方支持 Android View、Jetpack Compose、Compose Multiplatform，含 Cartesian、K 线等模型；Android `minSdk` 23，Maven Central 分发 | 中高：需要新增 Kuikly 原生 View 适配层，并处理生命周期、主题和事件桥接 | 增加一套原生渲染边界；当前项目没有该适配基础设施 | 后续首选验证对象。需要缩放、Marker、组合图时先做独立技术验证 |
| [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) | Apache-2.0；支持折线、柱、饼、K 线、缩放与平移；官方 README 使用 JitPack `v3.1.0` | 中：Kuikly 原生 View 适配层覆盖通用图和行情图 | 官方版本较旧，并新增 JitPack 仓库 | V1 采用：AI 折/柱/饼，以及行情 K 线+成交量 |

## 决策

V1 统一采用可替换的 MPAndroidChart 原生扩展边界。`TalkDataChart` 处理 AI 数值内容并复用三个图表实例；`TalkMarketChart` 使用 K 线价格窗和成交量窗，显示日期、价格、成交量与涨跌颜色含义。上下窗暂时禁用独立平移缩放，以保证日期窗口一致。默认显示折线图，并提供折线、柱状和受数据约束的饼图 Tab；该变化没有改变 `MarketDataProvider` 或后端接口。

原生适配层只接收图表类型、结构化 JSON 和深浅主题三个属性；第三方库不进入 ViewModel 和领域层。后续替换 Vico 或其他库时，只替换 Android 渲染器。高级图表迭代仍需验证 2,000 点性能、缩放/平移、十字光标、旋转恢复和页面销毁后的资源释放。

## 官方资料

- Vico 仓库与支持平台：https://github.com/patrykandpatrick/vico
- Vico Android 前置条件与依赖：https://github.com/patrykandpatrick/vico/blob/master/guide/getting-started.md
- Vico 发布记录：https://github.com/patrykandpatrick/vico/releases
- MPAndroidChart 仓库、能力和安装：https://github.com/PhilJay/MPAndroidChart
- MPAndroidChart 发布记录：https://github.com/PhilJay/MPAndroidChart/releases
