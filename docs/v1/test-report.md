# TalkToAI Android V1 验证报告

日期：2026-09-07；环境：macOS、本机 CloudBase CLI 3.8.1、Android Studio AVD API 36.1、vivo V2507A（Android 15 / API 35）。

## 已执行验证

| 层级 | 命令/方法 | 结果 |
|---|---|---|
| 后端单元与接口 | `npm test` | 14/14 通过：SSE、引用、配额、附件上传、行情校验、夹具过期规则、周线聚合 |
| 后端类型构建 | `npm run build` | 通过 |
| Android 单元测试 | `./gradlew :androidApp:testDebugUnitTest` | 8/8 通过：SSE、HTTP 请求过滤、附件上传、会话与引用编解码、中断恢复 |
| Android 静态检查 | `./gradlew :androidApp:lintDebug` | 0 error、49 warning；warning 为依赖升级/国际化等非阻断项 |
| Android APK | `./gradlew :androidApp:assembleDebug` | 通过；6,909,351 bytes；SHA-256 `ef95ec764fa1d2dd205ef7b41cc0f7d40c4f456068a3dc9444174db2956baf41` |
| CloudBase 测试部署 | `tcb fn deploy talktoai-api --force` | 成功 |
| 远端健康 | `GET /health` | 200；准确报告 AI/附件未配置 |
| 远端行情 | 周线自定义区间 | 200；聚合 OHLCV；`STALE`；醒目标注非实时测试数据 |
| 远端 AI | 无凭证聊天请求 | 503 `AI_NOT_CONFIGURED`，且在配额扣减前失败 |
| 模拟器安装与启动 | `adb -s emulator-5554 install --no-streaming -r -t ...` | 成功；Activity resumed；未发现 FATAL EXCEPTION/ANR |
| 模拟器离线状态 | 关闭蜂窝数据和 Wi-Fi，使默认网络进入 `SUSPENDED` | 正确显示“无网络：历史会话仍可查看”，恢复网络后回到在线状态 |
| 真机安装与启动 | `adb -s 10AG7H0CSP00C0D install --no-streaming -r -t ...` | 成功；包版本 1.0、targetSdk 34、进程存活；抽查日志未发现 FATAL EXCEPTION/ANR |

最终 Android 校验命令为：

```bash
./gradlew :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug --no-daemon
```

结果：`BUILD SUCCESSFUL`，94 个任务（16 executed、78 up-to-date）。最终 APK 同时安装到模拟器和真机。真机安装后设备进入系统锁屏，因此保留安装、包信息、进程与日志证据，不把锁屏截图当作应用 UI 验收证据。

## 未完成与阻塞

1. 腾讯云 AI 流式真响应与 CloudBase 附件真实存储/内容解析：测试函数没有服务端 `CLOUDBASE_APIKEY`。代码与失败契约已完成，不能伪造成功证据；停止生成目前由客户端断开流和接口测试覆盖，尚缺真实上游运行证据。
2. 生产 A 股行情：给定三狐页面不支持 HTTPS，且没有可验证的官方 API 契约或数据授权。当前只能交付明确标为非实时、并会判定过期的固定测试数据。
3. 无登录条件下只能本机保存与重启恢复，无法实现真实双端同步；该能力必须与后续账号/匿名云身份产品方案一起决策。
4. 插件中心首版只提供能力状态与稳定边界，尚未实现第三方动态加载或远程插件目录。
5. `@cloudbase/node-sdk 3.18.3` 的传递依赖审计仍报告 1 个 moderate、4 个 high；升级需等待官方依赖链修复或做隔离替换评估。
