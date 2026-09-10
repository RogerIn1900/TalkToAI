# DeepSeek 模型接入验证记录

日期：2026-09-10。范围：Android 测试版与 CloudBase 测试环境；只读咨询，不包含交易能力。

## 实现结果

- Android 模型选择器新增 `DeepSeek V4 Flash`，选择结果保存在应用私有偏好中，重启后恢复；请求只传服务端白名单模型 ID。
- CloudBase 函数新增 DeepSeek OpenAI 兼容流式适配器，并复用现有 `meta / delta / citation / done` SSE 输出协议。
- DeepSeek API Key 只存在于 CloudBase 测试函数环境变量。凭证未写入 Android、Git、文档、健康接口或错误正文；凭证记录 ID 不作为聊天参数。
- 当前接入的是文本模型：CSV/TXT 可继续作为文本上下文，图片附件在扣配额前返回 `MODEL_ATTACHMENT_UNSUPPORTED`。
- `/health` 不访问模型上游，只返回可用模型 ID；测试环境已确认 `hy3` 与 `deepseek-v4-flash` 均已配置。

官方契约参考：[DeepSeek API 模型与价格](https://api-docs.deepseek.com/quick_start/pricing)、[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion)、[CloudBase 第三方模型](https://docs.cloudbase.net/en/ai/quickstart/third-party-model)。

## 验证结果

| 层级 | 命令或证据 | 结果 |
|---|---|---|
| 后端契约 | `cd backend/functions/talktoai-api && npm test && npm run build` | 38 项测试通过，TypeScript 构建通过 |
| Android JVM | `./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest` | shared 23 项、androidApp 30 项通过 |
| Android 构建 | `./gradlew :androidApp:lintDebug :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --rerun-tasks` | Lint、debug APK、测试 APK 均通过 |
| 模拟器设备 | `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest` | ResumeTargetApi36 / Android 16，21 项通过，失败 0 |
| 测试环境 | `GET https://stockai-test-d6gd0ho1z97f0bbde.service.tcloudbase.com/talktoai/health` | `aiReady=true`，模型目录含 `hy3` 与 `deepseek-v4-flash` |
| UI 与恢复 | `artifacts/runtime-2026-09-10/deepseek-model-picker.png`、`deepseek-model-selected.png`、`deepseek-model-restored.png` | 双模型单选可见；切换后顶部与输入框下方同步更新；强制停止并冷启动后仍为 DeepSeek |

debug APK：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`，SHA-256：`776c88f1ea976437e832096cf5947cb0a3fb9dd4b46a3bdf17baff87eb8d3058`。

## 未验证边界

- 没有执行真实 DeepSeek 生成。该操作可能消耗模型账户额度，与“不得产生付费资源”的边界冲突；因此目前已验证配置可见性、请求契约、流式解析、错误脱敏和完整客户端选择链路，但未验证所给凭证对上游的实际鉴权及余额状态。
- 模拟器证据不是物理真机证据。物理设备虽处于 ADB 列表中，本轮未安装、启动或运行测试。
- DeepSeek 文本模型不支持图片理解；若后续需要图片，应接入官方可确认的视觉模型并重新完成附件契约、安全和费用验收。
