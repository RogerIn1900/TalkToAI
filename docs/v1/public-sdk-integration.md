# 公开 Kuikly SDK 与 TalkToAI 集成

日期：2026-09-09。

## 仓库与边界

- 公开仓库：https://github.com/RogerIn1900/kuikly-market-ui
- SDK 版本：0.1.0；MIT 许可证。独立 Git 历史，不包含 TalkToAI、CloudBase、私有凭证、用户对话或参考截图。
- SDK 源码位于 `market-ui/` 子模块；`settings.gradle.kts` 使用 `includeBuild`，shared 引用 `io.github.rogerin1900:market-ui:0.1.0`。
- 这是固定 Git 提交的源码导入，不是 Maven Central 发布，也不依赖 GitHub Packages 的访问令牌。
- SDK 仅负责数据契约、布局、主题注入、单选与排序、模拟兜底；图表原生桥仍由 TalkToAI 提供。

## 维护

SDK 独立维护 `sdkVersion`、CHANGELOG、兼容表、Tag、CI 和 Release。升级后更新当前工程的子模块 gitlink，不使用 `--remote` 浮动更新。初始化命令：`git submodule update --init --recursive`。

当前工程之前的未提交修改保持原状，本次不把它们一起推送到公开 SDK。发布前本地文件扫描未发现服务配置、JWT、访问令牌或本机绝对路径；这不等同于正式安全审计。

## 本地验证

- SDK `verifyReleaseVersion testDebugUnitTest assembleRelease lintRelease publishAllPublicationsToLocalReleaseRepository`：通过，7 项单元测试。
- SDK `./gradlew -p examples/consumer assembleDebug`：通过，编译示例仅依赖公开 API，不引用 TalkToAI。
- 无凭证从公开 URL 全新克隆后，SDK 测试与 AAR 构建通过；复用了本机 Gradle 依赖缓存，不代表空缓存下载验证。
- TalkToAI shared/Android 单测、Lint、APK 和测试 APK 构建通过。单测包含缓存命中，详见日志，不将其全部描述为重新执行。
- `dependencyInsight`：`io.github.rogerin1900:market-ui:0.1.0 -> project :market-ui`，使用 Android JVM 变体。
- 新 APK 与测试 APK 安装模拟器成功；设备测试 `OK (10 tests)`；冷启动与行情页图表已截图确认。

证据目录：`artifacts/sdk-publication-2026-09-09/`。

## 正式发布

- Release：https://github.com/RogerIn1900/kuikly-market-ui/releases/tag/v0.1.0
- Tag `v0.1.0` / 固定提交 `879b93161b5f044b917708030059a23d00d67e11`。
- 提交 CI 成功：https://github.com/RogerIn1900/kuikly-market-ui/actions/runs/34334632234 。包含远端 SDK 测试、Lint、AAR、本地 Maven publication 和独立消费者编译。
- 正式标签 CI 同样成功：https://github.com/RogerIn1900/kuikly-market-ui/actions/runs/34334975205 ，验证标签与版本号一致。
- Release 附件：`market-ui-0.1.0.aar`、`market-ui-maven-0.1.0.zip`、`SHA256SUMS.txt`，已公开发布。
- 附件重新下载后两项 SHA-256 校验均通过；解压 Maven 仓库，移除消费者的 `includeBuild`，改用该文件 Maven 仓库后独立编译成功（23 个任务全部执行），验证二进制包与元数据可消费。
- 故意传入错误 Tag `v9.9.9`，版本检查按预期失败：`Tag must match sdkVersion`。
- Lint 非零警告：SDK 1 条，宿主 50 条；构建不因其失败，不宣称零警告。
- 宿主 SDK gitlink 与 `.gitmodules` 已登记在索引，配置和其他原有修改留在工作区；未替用户提交或推送 TalkToAI 的其他改动。

## 剩余边界

没有真实汇总行情授权接入、没有物理真机或跨 Kuikly 版本兼容验证；消费者示例是可编译 UI 接线模块，不是包含原生图表引擎的完整演示 App。SDK 尚处于 0.x 阶段。
