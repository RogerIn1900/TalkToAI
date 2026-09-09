# Android SDK 本地配置修复

2026-09-09：用户在 Android Studio 构建时提示 `market-ui/local.properties` 缺少 SDK location。

原因：SDK 改为独立 Gradle 组合构建后，不会自动继承宿主 `local.properties`。此前命令行通过临时 `ANDROID_HOME` 验证，未覆盖 IDE 不继承该变量的情况。

修复：为 `market-ui/local.properties` 设置与宿主相同的有效 `sdk.dir`；文件由 SDK `.gitignore` 忽略，未提交公开仓库。宿主 README 补充首次配置说明。无生产代码或公开版本变更。

验证命令：

```sh
env -u ANDROID_HOME -u ANDROID_SDK_ROOT ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --offline --no-daemon
# 在 market-ui 目录执行
env -u ANDROID_HOME -u ANDROID_SDK_ROOT ./gradlew verifyReleaseVersion testDebugUnitTest assembleRelease --offline --no-daemon
```

两项构建分别成功（9s / 4s），单测主要复用缓存；这验证不依赖临时 SDK 环境变量，不代表重新执行全部 JVM 测试。模拟器通过明确指定 `emulator-5554` 安装应用和测试 APK，不操作已连接真机。

运行证据保存在 `artifacts/sdk-location-fix-2026-09-09/`；Android Studio 应重新 Gradle Sync，并选择 `Medium_Phone_API_36.1` 运行。未直接操作 IDE UI，不能将命令行验证表述为已在 IDE 点击运行。
