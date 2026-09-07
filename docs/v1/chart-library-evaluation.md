# Android 图表库评估（V1）

评估日期：2026-09-07。范围是 Kuikly Android 客户端中的分时、K 线和成交量图，不包含交易能力。

| 方案 | 能力与分发 | Kuikly 接入成本 | 风险 | V1 结论 |
| --- | --- | --- | --- | --- |
| Kuikly `Canvas`（当前） | 与现有跨端 UI 同栈；坐标轴、图例、K 线和成交量需自行绘制 | 低 | 后续缩放、十字光标和大数据量优化需继续实现 | 采用。V1 保持依赖最小，并补齐坐标、图例和日期轴 |
| [Vico](https://github.com/patrykandpatrick/vico) | Apache-2.0；官方支持 Android View、Jetpack Compose、Compose Multiplatform，含 Cartesian、K 线等模型；Android `minSdk` 23，Maven Central 分发 | 中高：需要新增 Kuikly 原生 View 适配层，并处理生命周期、主题和事件桥接 | 增加一套原生渲染边界；当前项目没有该适配基础设施 | 后续首选验证对象。需要缩放、Marker、组合图时先做独立技术验证 |
| [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) | Apache-2.0；支持折线、柱、饼、K 线、缩放与平移；官方 README 使用 JitPack `v3.1.0` | 中高：同样需要 Kuikly 原生 View 适配层 | 官方最新发布仍为 3.1.0，发布链和工程配置较旧，并新增 JitPack 仓库 | 不作为新项目首选，仅在 Vico 无法满足时复核 |

## 决策

V1 不新增第三方图表依赖。当前需求只有只读展示，Kuikly `Canvas` 已能在不扩展基础设施的前提下完成 K 线、成交量、横纵坐标和颜色图例。直接引入 Android View 图表库会打破当前 Kuikly 组件边界，收益不足以覆盖适配与回归成本。

进入高级图表迭代时，对 Vico 做一个有边界的原型：验证 2,000 根 K 线、缩放/平移、十字光标、深浅主题、旋转恢复与 Kuikly 页面销毁后的资源释放。原型通过后再替换 `Canvas`，数据层继续复用 `MarketDataProvider`，不改变后端接口。

## 官方资料

- Vico 仓库与支持平台：https://github.com/patrykandpatrick/vico
- Vico Android 前置条件与依赖：https://github.com/patrykandpatrick/vico/blob/master/guide/getting-started.md
- Vico 发布记录：https://github.com/patrykandpatrick/vico/releases
- MPAndroidChart 仓库、能力和安装：https://github.com/PhilJay/MPAndroidChart
- MPAndroidChart 发布记录：https://github.com/PhilJay/MPAndroidChart/releases
