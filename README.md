# TalkToAI Android V1

TalkToAI 是一个仅提供 A 股信息参考的 Android 测试版应用。客户端使用 Kotlin、Kuikly 和 MVVM；后端使用 CloudBase HTTP 云函数与 TypeScript。应用不提供登录、下单、支付或收益承诺。

## 快速验证

行情 UI 使用公开 SDK [kuikly-market-ui](https://github.com/RogerIn1900/kuikly-market-ui)，以 `market-ui/` Git 子模块锁定提交，通过 Gradle 组合构建导入。首次克隆使用 `git clone --recurse-submodules`；已有工程先执行：

```bash
git submodule update --init --recursive
```

当前使用可组合行情组件 `0.2.0`，Gradle 默认接入 `market-ui` 子模块，固定到上游 `main` 的 `1a6dcd0` 合并提交，包含最新原型布局优化。开发时可用 `-PmarketUiDir=../kuikly-market-ui` 显式接入同级源码；默认构建不会自动引用本机其他工作目录。

Android SDK 本机配置：组合构建中的 `market-ui` 是独立 Gradle 工程，不自动继承宿主的 `local.properties`。如果 Android Studio 未继承 `ANDROID_HOME`，请在宿主根目录和 `market-ui/local.properties` 中分别配置同一个有效的 `sdk.dir`。这两个文件均被 Git 忽略，不应提交本机路径。独立编译 SDK 消费者示例时，也需为其配置 SDK 路径或设置 `ANDROID_HOME`。

```bash
cd backend/functions/talktoai-api
npm test
npm run build

cd ../../..
./gradlew :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug --no-daemon
adb -s emulator-5554 install --no-streaming -r -t androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

## Android Studio 运行

1. 完成 Gradle Sync，选择项目共享的 `androidApp` 运行配置和目标模拟器。
2. 该配置使用合并 Manifest 中的 Default Activity，并在启动前构建、部署 `:androidApp`。
3. 如果 Android Studio 提示 `KuiklyRenderActivity does not exist`，说明当前设备上的 APK 未成功更新或仍在使用旧的个人运行配置。重新选择共享的 `androidApp` 配置后运行；也可用下面两条命令验证部署与入口：

```bash
adb -s emulator-5554 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s emulator-5554 shell am start -W -n com.example.talktoai/.KuiklyRenderActivity
```

普通 Android 构建不会执行 Kuikly JS 分包任务，`packEntryJSBundle... is NOT in execution graph` 表示主动跳过，不是构建失败。

测试环境接口：`https://stockai-test-d6gd0ho1z97f0bbde.service.tcloudbase.com/talktoai`。CloudBase Node SDK 在函数内调用 `cloudbase / hy3`；Android 不保存服务端密钥。测试环境已经完成真实 SSE 与云存储上传验证。

详细资料：

- [需求基线](docs/v1/requirements.md)
- [架构与安全边界](docs/v1/architecture.md)
- [HTTP 接口](docs/v1/api.md)
- [测试环境部署](docs/v1/deployment.md)
- [验证报告与剩余风险](docs/v1/test-report.md)

## 行情看板（UI SDK 0.2.0）

行情页通过 `market-ui` Git 子模块和 Gradle 组合构建使用独立 UI SDK。

```sh
git submodule update --init --recursive
```

为宿主和 `market-ui` 分别配置被 Git 忽略的 `local.properties`，或设置 `ANDROID_HOME`。

行情总览使用 SDK 的 `MarketDashboardView`，个股详情使用独立的 `MarketMetricPanel`。宿主提供 AI 行情数据、来源、请求状态、导航和原生图表适配；缺失的总览区块使用明确标识的模拟示例。

这次替换移除了旧版可编辑看板的宿主适配（编辑、CSV 导入和布局快照入口），未删除设备上的历史数据文件。对话页现有视觉优化和聊天功能保留。

接入和验证说明见 [最新组件接入记录](docs/v1/market-ui-latest-integration.md)。
