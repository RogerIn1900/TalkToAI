# TalkToAI Android V1 验证报告

日期：2026-09-07；环境：macOS、本机 CloudBase CLI 3.8.1、Android Studio AVD API 36.1、vivo V2507A（Android 15 / API 35）。

## 已执行验证

| 层级 | 命令/方法 | 结果 |
|---|---|---|
| 后端单元与接口 | `npm test` | 14/14 通过：SSE、引用、配额、附件上传、行情校验、夹具过期规则、周线聚合 |
| 后端类型构建 | `npm run build` | 通过 |
| Android 单元测试 | `./gradlew :androidApp:testDebugUnitTest` | 8/8 通过：SSE、HTTP 请求过滤、附件上传、会话与引用编解码、中断恢复 |
| Android 静态检查 | `./gradlew :androidApp:lintDebug` | 0 error、49 warning；warning 为依赖升级/国际化等非阻断项 |
| Android APK | `./gradlew :androidApp:assembleDebug` | 通过；6,909,351 bytes；SHA-256 `c14c41f821058393f5211e0ba3f3f178bf4a8d2ab5bf5b249ae566a1b63504ab` |
| CloudBase 测试部署 | `tcb fn deploy talktoai-api --force` | 成功 |
| 远端健康 | `GET /health` | 200；`aiReady=true`、`marketReady=true`、附件 `ready` |
| 远端行情 | 周线自定义区间 | 200；聚合 OHLCV；`STALE`；醒目标注非实时测试数据 |
| 远端 AI | `POST /v1/chat/completions` | 200；真实 `cloudbase / hy3` SSE：1 `meta`、40 `delta`、1 `done` |
| 远端附件 | TXT 原始字节上传 | 201；返回受控 `cloud://` 对象引用，不返回公开 URL |
| 模拟器安装与启动 | `adb -s emulator-5554 install --no-streaming -r -t ...` | 成功；Activity resumed；未发现 FATAL EXCEPTION/ANR |
| 模拟器离线状态 | 关闭蜂窝数据和 Wi-Fi，使默认网络进入 `SUSPENDED` | 正确显示“无网络：历史会话仍可查看”，恢复网络后回到在线状态 |
| Android 真实流式闭环 | 模拟器输入、SSE 增量、完成与停止 | 真实 AI 回答完成；生成中两个入口均切换为“停止”；停止后持久化状态为 `stopped` |
| 真机安装与启动 | `adb -s 10AG7H0CSP00C0D install --no-streaming -r -t ...` | 候选 APK 成功；包版本 1.0、targetSdk 34、进程存活；抽查日志未发现 FATAL EXCEPTION/ANR |

最终 Android 校验命令为：

```bash
./gradlew :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug --no-daemon
```

结果：`BUILD SUCCESSFUL`，94 个任务（29 executed、65 up-to-date）。最终 APK 已安装到模拟器。真机曾成功安装紧邻最终版的候选 APK；修复“停止”按钮后真机从 ADB 断开，因此最终哈希版本仍需重新安装。保留候选版安装、包信息、进程与日志证据，不把锁屏截图当作应用 UI 验收证据。

## 未完成与阻塞

1. 生产 A 股行情：给定三狐页面不支持 HTTPS，且没有可验证的官方 API 契约或数据授权。当前只能交付明确标为非实时、并会判定过期的固定测试数据。
2. 每日 500 次目前使用函数进程内存计数；单实例测试正确，但冷启动和横向扩容会重置。生产前必须接入 SQL/NoSQL 原子持久化计数并执行并发测试。
3. 附件已能安全上传到 CloudBase，但图片识别以及 CSV/TXT 内容抽取尚未接入 AI 上下文。
4. 无登录条件下只能本机保存与重启恢复，无法实现真实双端同步；该能力必须与后续账号/匿名云身份产品方案一起决策。
5. 插件中心首版只提供能力状态与稳定边界，尚未实现第三方动态加载或远程插件目录。
6. `@cloudbase/node-sdk 3.18.3` 的传递依赖审计仍报告 1 个 moderate、4 个 high；升级需等待官方依赖链修复或做隔离替换评估。
7. 最终哈希 APK 尚缺物理真机复装与 UI 截图；当前完成的是模拟器最终版和真机候选版验证。
