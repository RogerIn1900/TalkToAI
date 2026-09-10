# TalkToAI Android V1

TalkToAI 是一个仅提供 A 股信息参考的 Android 测试版应用。客户端使用 Kotlin、Kuikly 和 MVVM；后端使用 CloudBase HTTP 云函数与 TypeScript。应用不提供登录、下单、支付或收益承诺。

## 快速验证

行情 UI 使用公开 SDK [kuikly-market-ui](https://github.com/RogerIn1900/kuikly-market-ui)，以 `market-ui/` Git 子模块锁定提交，通过 Gradle 组合构建导入。首次克隆使用 `git clone --recurse-submodules`；已有工程先执行：

```bash
git submodule update --init --recursive
```

当前依赖版本为 `0.1.0`。SDK 的快速接入、功能与实现说明见其 README；不要用浮动主分支替换已锁定版本。

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

## 可编辑看板（UI SDK 0.2.1）

行情页通过 `market-ui` Git 子模块和 Gradle 组合构建使用独立 UI SDK。

```sh
git submodule update --init --recursive
```

为宿主和 `market-ui` 分别配置被 Git 忽略的 `local.properties`，或设置 `ANDROID_HOME`。

新看板支持长按编辑、三点按钮移动与配置、四边缩放、五类展示、CSV 导入、报表快照和本地保存。内置数据明确标为模拟数据；导入和快照仅保存在本机，不发送给 AI。

CSV 表头为 `label,value`，或 `label,value,timeMs`；时间为毫秒时间戳，限制 256 KB / 1000 行。配置中保存数据引用，原始数据由宿主单独持久化。

设计见 [可编辑看板方案](docs/v1/editable-dashboard-sdk-design.md)，SDK 接入说明与 Skill 位于子模块的 `docs/editable-dashboard.md` 和 `skills/kuikly-dashboard/SKILL.md`。
