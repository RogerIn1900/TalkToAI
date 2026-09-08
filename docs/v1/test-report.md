# TalkToAI Android V1 验证报告

> 本文件主体是 2026-09-07 的历史记录。第二轮优化后的当前证据见 `test-report-2026-09-08.md`，两者不得混作同一次测试。

日期：2026-09-07；环境：macOS、本机 CloudBase CLI 3.8.1、Android Studio AVD API 36.1、vivo V2507A（Android 15 / API 35）。

## 已执行验证

### 最新优化回归（2026-09-07 22:00）

本节覆盖下方历史验证中同名项目；本轮没有部署后端或连接物理真机。

- `./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug`：通过。shared 10/10、Android 11/11；lint 0 error、50 warning。
- MPAndroidChart 已接入 AI 数值表格，默认折线，支持柱状/饼状切换；表格原始 Markdown 被转换。折线/柱状按钮及图形在 AVD 验证；最终饼图关闭切片文字以避免重叠，该微调未单独截图复验。证据：`artifacts/runtime-2026-09-07/ai-chart-line.png`、`ai-chart-bar.png`。
- 主题切换后留在外观页，气泡/头像按钮即时选中；证据：同目录 `theme-stays.png`、`bubble-immediate.png`、`avatar-immediate.png`。
- 日期确认立即刷新，日期标题即时更新；无数据时显示明确空态。区间 `2026-08-08` 至 `2026-09-08` 重复查询显示本地缓存，缓存文件 SHA-256 前后均为 `379dc3ed99d36564f503025cc588ab9619708696c31a787d5b185cccea9e2e47`；证据：`market-date-cache.png`。缓存命中在桥接层直接返回，保留服务端新鲜度字段。
- 飞行模式下发送 `verify4`，输入清空、用户消息出现，显示网络连接失败和重新加载；使用滚动区实际尺寸修复发送后停在旧位置的问题。证据：`chat-offline-latest.png`。此前离线消息重启恢复及失败态也已核实。测试结束恢复网络，ping 成功。
- 选区操作栏贴近选中文本，证据：`selection-near-text.png`。
- 最终 APK 安装成功，`am start -W` 返回 `Status: ok`；冷启动耗时 8189ms。此前调试构建出现一次 `failed to complete startup` ANR，模拟器同期负载高，但尚不能确定根因；启动性能仍须专项排查，不宣称无 ANR。
- APK 已更新到 `artifacts/TalkToAI-v1-debug.apk`，哈希见 `artifacts/SHA256SUMS.txt`。本轮可安装与上述功能通过，不代表以下产品阻塞全部解决。

### 前序验证记录

| 层级 | 命令/方法 | 结果 |
|---|---|---|
| 后端单元与接口 | `npm test` | 17/17 通过：SSE、引用、行情事件先于 AI、行情上下文注入、内存/SQL 配额映射、附件上传、行情校验、夹具过期规则、周线聚合 |
| 后端类型构建 | `npm run build` | 通过 |
| Android 单元测试 | `./gradlew :androidApp:testDebugUnitTest` | 8/8 通过：SSE、HTTP 请求过滤、附件上传、会话与引用编解码、中断恢复 |
| 共享 UI 规则单元测试 | `./gradlew :shared:test` | 8/8 通过：原有 UI 规则，以及输入选择/组合区边界、闰年日期校验和行情轴标签 |
| Android 静态检查 | `./gradlew :androidApp:lintDebug` | 0 error、49 warning；warning 为依赖升级/国际化等非阻断项 |
| Android APK | `./gradlew :androidApp:assembleDebug` | 通过；制品大小与 SHA-256 见 `artifacts/SHA256SUMS.txt` |
| CloudBase 测试部署 | `tcb fn deploy talktoai-api --force` | 成功 |
| 远端健康 | `GET /health` | 200；`aiReady=true`、`marketReady=true`、附件 `ready` |
| 远端行情 | 周线自定义区间 | 200；聚合 OHLCV；`STALE`；醒目标注非实时测试数据 |
| 远端 AI + 行情工具 | 最终部署后 `POST /v1/chat/completions`，消息 `atodaymarket` | 200；真实 `cloudbase / hy3` SSE 共 146 个事件；开头为 `meta`、`market`、`delta`，末尾为 `done`；`market_before_delta=true`、4 根 OHLCV、`STALE` |
| 远端附件 | TXT 原始字节上传 | 201；返回受控 `cloud://` 对象引用，不返回公开 URL |
| 模拟器安装与启动 | `adb -s emulator-5554 install --no-streaming -r -t ...` | 成功；Activity resumed；未发现 FATAL EXCEPTION/ANR |
| 模拟器离线状态 | 关闭蜂窝数据和 Wi-Fi，使默认网络进入 `SUSPENDED` | 正确显示“无网络：历史会话仍可查看”，恢复网络后回到在线状态 |
| Android 真实流式闭环 | 模拟器输入、SSE 增量、完成与停止 | 真实 AI 回答完成；生成中两个入口均切换为“停止”；停止后持久化状态为 `stopped` |
| Tab 与行情图 | 模拟器点击“对话 / 行情” | 页面互斥切换；行情页展示 K 线、同步成交量、来源、数据时间和 `STALE`，见 `evidence/ui/tab-market.png` |
| 侧边栏 | 输入框聚焦时单击导航 | 键盘收起且一次打开侧栏；重复入口合并为会话、AI 与工具、行情、插件和诊断、外观 5 项，见 `evidence/ui/sidebar-optimized.png` |
| 消息交互 | 模拟器恢复真实 AI 会话并点击/长按操作 | 用户消息右侧、AI 回答左侧；复制/点赞/重新生成位于气泡外下方；长按显示选区手柄与“复制选中/全选/取消”，点击后显示复制 Toast，见 `evidence/ui/chat-actions.png`、`evidence/ui/text-selection-copy.png` |
| 外观与安全区 | 模拟器进入外观设置并切换气泡 | 主题、气泡、头像为显式选项并可生效；顶部仅保留系统状态栏安全距离，见 `evidence/ui/sidebar-optimized.png` 与 `evidence/ui/chat-actions.png` |
| 输入与流式性能 | 源码检查 + 普通触控复验 | 草稿写入由每字符同步 `commit()` 改为 250ms 防抖 `apply()`；流式消息按 50ms 合并 UI/异步持久化并在 `done` 强制提交。批量 ADB 按键灌入曾造成应用及 Gboard ANR，不作为真实输入性能通过证据；重启输入法后普通触控未新增应用 ANR |
| 键盘避让 | 模拟器打开系统输入法；`dumpsys input_method` | `mInputShown=true`、`mIsInputViewShown=true`，输入框位于键盘上方，见 `evidence/ui/keyboard-adjust-resize.png` |
| 深色主题 | 侧边栏切换主题 | 页面、气泡、侧边栏及系统栏同步切换，见 `evidence/ui/dark-theme.png`；验证后恢复“跟随系统” |
| 真机安装与启动 | `adb -s 10AG7H0CSP00C0D install --no-streaming -r -t ...` | 候选 APK 成功；包版本 1.0、targetSdk 34、进程存活；抽查日志未发现 FATAL EXCEPTION/ANR |
| 外观状态与页面恢复 | 模拟器依次选择描边气泡、圆形头像和深色主题 | 按钮立即以勾选和高亮反馈；主题触发 Activity 重建后仍停留外观页；`SharedPreferences` 复核为 `dark/outline/round` |
| 行情坐标与日期选择 | 模拟器行情页和 Kuikly `DatePicker` | 显示价格纵轴、日期/分时横轴、涨跌及成交量图例；起止日期改为日历弹窗，默认结束日为当天、起始日为前 30 天 |
| 输入中断回归 | 输入 36 字符、连续删除 20 次、切到桌面、恢复应用、继续删除 8 次、发送，再分别等待 2 秒观察 | 文本稳定为 `abcdefgh`，发送后输入框持续为空；未出现反复删除、旧值回填、FATAL EXCEPTION 或应用 ANR |
| 选择操作栏 | 长按 AI 文本 | 选区下方直接显示高对比度“已选择 / 复制选中 / 全选 / 取消”，普通消息操作在选择期间隐藏 |

最终 Android 校验命令为：

```bash
./gradlew :shared:test :androidApp:testDebugUnitTest :androidApp:assembleDebug
./gradlew :androidApp:lintDebug
```

本轮结果：`BUILD SUCCESSFUL`；Android 8/8、shared 8/8，共 16/16 个唯一单元测试通过；后端另有 17/17 个测试通过；`lintDebug` 通过。当前 APK 已安装到 `emulator-5554`。真机曾成功安装紧邻最终版的候选 APK；本轮按用户要求只复验 Android Studio 虚拟机，因此未更新物理真机证据。

## 未完成与阻塞

1. 生产 A 股行情：给定三狐页面不支持 HTTPS，且没有可验证的官方 API 契约或数据授权。当前只能交付明确标为非实时、并会判定过期的固定测试数据。
2. 每日 500 次目前使用函数进程内存计数；单实例测试正确，但冷启动和横向扩容会重置。测试库已创建 `talktoai_daily_quota` 与原子 UPSERT 函数，直接 SQL 的 1/2/拒绝边界通过；HTTP 云函数通过 Node SDK `$runSQL` 时仍因运行时 SQL 鉴权返回 500，已恢复内存配置。生产前必须补齐服务端 SQL 身份并执行并发测试。
3. 附件已能安全上传到 CloudBase，但图片识别以及 CSV/TXT 内容抽取尚未接入 AI 上下文。
4. 无登录条件下只能本机保存与重启恢复，无法实现真实双端同步；该能力必须与后续账号/匿名云身份产品方案一起决策。
5. 插件中心首版只提供能力状态与稳定边界，尚未实现第三方动态加载或远程插件目录。
6. `@cloudbase/node-sdk 3.18.3` 的传递依赖审计仍报告 1 个 moderate、4 个 high；升级需等待官方依赖链修复或做隔离替换评估。
7. 最终哈希 APK 尚缺物理真机复装与 UI 截图；当前完成的是模拟器最终版和真机候选版验证。
8. AI 数值表格已支持折线/柱状/饼状切换；看板风格定制仍留后续。复杂非数值表格、混合单位、多系列柱状布局和负值饼图仍需完善；不能视为所有 Markdown 数据均已可靠图形化。第三方库结论见 `chart-library-evaluation.md`。
9. 本轮以 Kuikly 原子编辑态同步消除了复现链路中的反复删除和旧值回填，并已通过 AVD 生命周期回归；仍缺 Macrobenchmark 的帧耗时数据，不能把功能回归等同于量化性能基准。
