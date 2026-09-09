# 个股查询滚动布局

2026-09-09：将 TalkToAiPager.marketDetailContent 的固定 View 改为 Kuikly List（代码别名 LazyColumn，并非 Compose LazyColumn）。基于本机 Kuikly 2.27.0 源码，List 在可见区域管理原生视图；本次不是行情数据分页，也未测量内存收益。

K 线和成交量区域使用 420dp 独立高度，真实/模拟两种状态一致；删除剩余空间 flex 分配，查询、图例、图表、指标和来源说明进入同一纵向列表。保留既有图表全屏与归位，不改变数据源和 SDK 版本。

验证：

```sh
env -u ANDROID_HOME -u ANDROID_SDK_ROOT ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --offline --no-daemon
adb -s emulator-5554 shell am instrument -w com.example.talktoai.test/androidx.test.runner.AndroidJUnitRunner
```

构建通过；设备测试 11 项通过，包括 candleAndVolumeDataSurviveListStyleDetachAndReattach。模拟器冷启动成功，人工从图表内部上滑，确认底部成交量、指标与来源说明可达。截图和日志：artifacts/market-detail-scroll-2026-09-09/。未验证物理真机、小屏/大字体组合和行情数据分页；本次未执行 Lint。
