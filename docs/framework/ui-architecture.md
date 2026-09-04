# TalkToAI UI 架构 v1.0

> 参考：[豆包 App](https://github.com/Tencent-TDS/KuiklyUI) 的 PageList + 4-Tab 模式
> 适配框架：Tencent Kuikly（KMP DSL，非标准 Compose）
> 适用项目：`/Users/jerry/JustDoIt/OHHHHH/TalkToAI/`
> 日期：2026-09-03
> 状态：**设计稿 · 待 Jerry 确认 ADR-001 后执行**

---

## §0 阅读路径

| 章节 | 内容 |
|---|---|
| §1 | 与现有架构的差异（Drawer → Tab） |
| §2 | 整体架构树（AppShell + PageList + BottomTabBar + Drawer 二级） |
| §3 | Kuikly DSL 翻译表（豆包 Compose → Kuikly） |
| §4 | 4-Tab 内容映射与状态机 |
| §5 | 关键组件代码（AppShell / BottomTabBar / TabItem） |
| §6 | 迁移计划 + 风险 |
| §7 | 版本记录 |

---

## §1 与现有架构的差异

### 1.1 当前架构（Drawer 单页）

```
┌─────────────────────────────────────┐
│         DshHubPager (单 Pager)        │
│  ┌─────────────────────────────────┐│
│  │ ☰ 问候                    📞 🔊 ││  ← 顶部栏（汉堡入口）
│  ├─────────────────────────────────┤│
│  │  hi 蓝色 starter                ││
│  │  🤖 Hi~ 😄 ...                  ││  ← 对话区
│  │     [📋][🔊][👍][👎][🔄]         ││
│  ├─────────────────────────────────┤│
│  │ [⚡快速][✨AI 创作][📷拍题答疑]… ││  ← chips 行
│  ├─────────────────────────────────┤│
│  │ [📷]  发消息或按住说话      [＋] ││  ← 输入栏
│  └─────────────────────────────────┘│
│                                       │
│  ☰ 点击 → DrawerContent 浮层:        │
│  ┌──────────┐                         │
│  │ 💬 Chat │                         │
│  │ 📁 Workspaces                     │
│  │ 📋 Sessions                       │
│  │ 🐞 Diagnostics                    │
│  │ ⚙️ Settings                       │
│  └──────────┘                         │
└─────────────────────────────────────┘
```

**问题**：
1. 主导航藏在汉堡里，用户发现成本高（DSH 任务文档第 4 项"会话管理"明确提到）
2. 切换页面需打开 Drawer → 点击 → 全屏替换，路径长
3. 第三方页面（Workspaces / Plugins / Log）和主流程页面（Chat）混在同一级

### 1.2 目标架构（4-Tab + PageList + Drawer 二级）

```
┌─────────────────────────────────────────────┐
│              AppShell (Root Pager)            │
│  ┌─────────────────────────────────────────┐│
│  │        PageList (横向 4 页)               ││
│  │  ┌──────────┬──────────┬──────────┬─────┐││
│  │  │  Chat   │Discover │Creation │Profile││  ← 各 Tab 内自有 TopBar + 内容
│  │  │  Page   │  Page   │  Page   │ Page ││
│  │  │         │         │         │      ││
│  │  └──────────┴──────────┴──────────┴─────┘││
│  └─────────────────────────────────────────┘│
│                                               │
│  ┌─────────────────────────────────────────┐│
│  │  💬Chat │🔍发现 │🎨创作 │👤我的          ││  ← 底部 BottomTabBar (固定)
│  └─────────────────────────────────────────┘│
│                                               │
│  ☰ (在 Chat Tab TopBar) → Drawer:           │
│   ┌──────────────┐                            │
│   │ 📁 Workspaces│   ← 二级导航（隐藏的次要功能）│
│   │ 🐞 Diagnostics│                            │
│   │ 🔌 Plugins    │                            │
│   │ 📊 Log Center │                            │
│   │ ⚙️ Settings   │                            │
│   └──────────────┘                            │
└─────────────────────────────────────────────┘
```

**收益**：
1. 主流程 4 个 Tab 一指可达，符合豆包/微信/小红书的国内用户习惯
2. Drawer 退化为"设置类"二级导航，主流程路径不再被淹没
3. `PageList` 的 `offscreenPageLimit = 1` 缓存相邻 Tab，避免每次切页重建

---

## §2 整体架构树

### 2.1 模块结构

```
shared/src/commonMain/kotlin/com/example/talktoai/
├── base/                              # 基础设施
│   ├── AppShell.kt                  ← 【新】根容器（4-Tab 切换）
│   ├── BasePager.kt                  ← 已存在
│   ├── BridgeModule.kt               ← 已存在
│   └── components/
│       ├── BottomTabBar.kt          ← 【新】自定义底部 Tab 栏
│       ├── BottomTabItem.kt         ← 【新】单个 Tab 项
│       └── DrawerContent.kt          ← 已存在（保留为二级导航）
│
├── base/dsh/                          # DSH 业务 Pager（已有）
│   ├── DshHubPager.kt               ← 重构为 ChatPager（feature/message/ 候选）
│   ├── WorkspaceListPager.kt         ← 不变（保留为 Drawer 二级入口）
│   ├── SessionListPager.kt           ← 移动到 feature/session/
│   ├── DiagnosticsPager.kt           ← 保留为 Drawer 二级入口
│   ├── SettingsPager.kt              ← 保留为 Drawer 二级入口
│   ├── ChatPager.kt                   ← 重构自 DshHubPager
│   └── DshBridgeModule.kt            ← 已存在
│
└── feature/                           # 【新】feature-owned（详见 dsh-architecture.md §3）
    ├── theme/                         # Feature-1
    ├── message/                       # Feature-2（ChatPager + MessageBubble）
    ├── attachment/                    # Feature-3
    ├── session/                       # Feature-4
    ├── plugin/                        # Feature-5（保留为 Drawer 二级）
    └── log/                           # Feature-6（保留为 Drawer 二级）
```

### 2.2 路由表更新

```kotlin
// 注册在 KuiklyRenderActivity 启动入口（默认 pageName）
@Page("app_shell", supportInLocal = true)
class AppShell : BasePager() { ... }

// Tab 内子页面（保留 RouterModule.openPage 跳转）
"chat"          → ChatPager            // Tab 0 内容
"discover"      → DiscoverPager        // Tab 1 内容
"creation"      → CreationPager        // Tab 2 内容
"profile"       → ProfilePager         // Tab 3 内容

// Drawer 二级入口
"workspace_list"   → WorkspaceListPager
"session_list"     → SessionListPager
"plugin_list"      → PluginListPager
"log_center"       → LogCenterPager
"diagnostics"      → DiagnosticsPager
"settings_main"    → SettingsPager
```

> **决策点**：原 `dsh_hub` 路由名 → 重构为 `chat`，与 Tab 0 语义对齐。
> 旧路由做 1 个版本的兼容跳转（`RouterModule.openPage("dsh_hub")` → 跳 `chat`）。

---

## §3 Kuikly DSL 翻译表（豆包 Compose → Kuikly）

| 豆包 Compose | TalkToAI Kuikly DSL | 备注 |
|---|---|---|
| `Box(Modifier.fillMaxSize())` | `View { attr { flex(1f) } }` | 根容器 |
| `Column { … }` | `View { attr { flexDirectionColumn() } }` | 垂直布局 |
| `Row(...)` | `View { attr { flexDirectionRow() } }` | 水平布局 |
| `LazyColumn { items(...) }` | `Scroller { View { ... } View { ... } }` | Kuikly 2.x 没有公开 key API，直接 forEach（< 100 条可接受） |
| `LazyVerticalGrid(columns = 2)` | `Scroller { View { flexDirectionColumn() ; View { flexDirectionRow() ; View{...}; View{...}}; View{flexDirectionRow() ; ...} } }` | 手动两列：每 Row 内两个 `weight(1f)` 项 |
| `BasicTextField(value, onValueChange)` | `Input { ref{...}; attr { ... } event { textDidChange { ... } } }` | Kuikly `Input` 等价 |
| `Image(painterResource(...))` | `Image { attr { src(...) ; size(...) } }` | Kuikly 自带 `Image` |
| `Modifier.background(color)` | `attr { backgroundColor(color) }` | 属性而非 Modifier |
| `Modifier.padding(16.dp)` | `attr { padding(16f) }` 或 `padding(left=, right=, top=, bottom=)` | |
| `Modifier.clickable { ... }` | `event { click { ... } }` | 事件外置 |
| `Modifier.size(24.dp)` | `attr { size(24f, 24f) }` | |
| `Modifier.clip(CircleShape)` | `attr { borderRadius(9999f) }` 或 `ShapeTokens.PILL` | |
| `HorizontalPager` / `VerticalPager` | **`PageList(pageCount, pageDirection=true)`** | Kuikly 原生横滑组件 |
| `AnimatedVisibility(visible)` | `vif({ ... }) { ... }`（编译指令）或 `if (cond) { ... }` | 直接条件渲染即可，Kuikly 不强制动画 |
| `rememberPagerState` | `pagerData.pageIndex`（Kuikly 内置） | 不需要手动 remember |
| `LazyListState` | `ScrollerState`（Kuikly 内部管理） | 通过 `scrollTo()` API |

---

## §4 4-Tab 内容映射与状态机

### 4.1 Tab 内容矩阵

| Tab | 标题 | 图标 (Emoji 占位) | Pager | 说明 |
|---|---|---|---|---|
| 0 | **对话** | 💬 | `ChatPager`（改造自 `DshHubPager`） | 主对话流，含 starter 胶囊、消息气泡、chips、输入栏 |
| 1 | **发现** | 🔍 | `DiscoverPager`【新】 | 智能体 / 工作区入口（搜索 + 分类 chips + 2 列网格卡片） |
| 2 | **创作** | 🎨 | `CreationPager`【新】 | 工具列表（写作 / 绘画 / 音乐 / 视频 四象限） |
| 3 | **我的** | 👤 | `ProfilePager`【新】 | 用户信息卡 + 设置入口（迁移部分 Drawer 入口） |

### 4.2 Tab 状态机

```kotlin
sealed class AppShellState {
    /** 主流程 4-Tab 模式 */
    data class TabBrowsing(val currentTab: Int) : AppShellState()   // 0..3

    /** Chat Tab 内 Drawer 打开（叠层） */
    data class ChatWithDrawer(val drawerOpen: Boolean) : AppShellState()

    /** 全屏二级页面（Drawer 跳转后，隐藏 TabBar） */
    data class SubPage(val route: String) : AppShellState()
}

class AppShellState {
    var currentTab: Int by observable(0)
    var drawerOpen: Boolean by observable(false)
    var activeSubRoute: String? by observable(null)   // 非空时隐藏 TabBar

    fun switchTab(index: Int) {
        currentTab = index
        drawerOpen = false
    }

    fun openDrawer() { drawerOpen = true }
    fun closeDrawer() { drawerOpen = false }

    fun openSubPage(route: String) {
        activeSubRoute = route
        drawerOpen = false
    }
    fun closeSubPage() { activeSubRoute = null }
}
```

### 4.3 Tab 切换协议

```kotlin
// AppShell 内部
PageList(
    pageCount = 4,
    pageDirection = true,
    offscreenPageLimit = 1,
    scrollEnable = true,
    onPageChanged = { index -> state.switchTab(index) }
) { pageIndex ->
    when (pageIndex) {
        0 -> ChatPager(state)
        1 -> DiscoverPager()
        2 -> CreationPager()
        3 -> ProfilePager()
    }
}

// BottomTabBar 点击同步 PageList
BottomTabBar(
    currentTab = state.currentTab,
    onTabClick = { index ->
        // 1. 同步 PageList 的 currentIndex
        pageListRef.view?.setCurrentPage(index)
        // 2. 更新 state
        state.switchTab(index)
    }
)
```

> **Kuikly 适配**：Kuikly `PageList` 提供 `ViewRef<PageListView>`，
> 调用 `setCurrentPage(index, animated=true)` 触发平滑滚动。
> （标准 Compose 用 `pagerState.animateScrollToPage(index)`，Kuikly DSL 封装不同但语义一致。）

---

## §5 关键组件代码

### 5.1 AppShell 根容器

```kotlin
package com.example.talktoai.base

import com.example.talktoai.base.components.BottomTabBar
import com.example.talktoai.base.components.DrawerContent
import com.example.talktoai.base.components.DrawerOverlay
import com.example.talktoai.base.dsh.ChatPager
import com.example.talktoai.base.dsh.CreationPager
import com.example.talktoai.base.dsh.DiscoverPager
import com.example.talktoai.base.dsh.ProfilePager
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * TalkToAI 应用根容器（UI 架构 §2.1）。
 *
 * 布局：
 *   PageList（4 页内容）
 *     ↓ 叠加
 *   BottomTabBar（仅在主 Tab 浏览时显示）
 *
 * Drawer 由 ChatTab 内部触发，AppShell 只监听状态变化决定是否在最外层显示。
 */
@Page("app_shell", supportInLocal = true)
internal class AppShell : BasePager() {

    private val state = AppShellState()
    private lateinit var pageListRef: ViewRef<PageListView>

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                flex(1f)
                backgroundColor(ThemeColors.background)
            }

            // ── ① 页面内容 ──────────────────────────────────────────────
            PageList {
                ref { ctx.pageListRef = it }
                attr {
                    pageCount(4)
                    pageDirection(true)
                    offscreenPageLimit(1)
                    scrollEnable(true)
                    currentPage(ctx.state.currentTab)
                    flex(1f)
                }
                event {
                    pageChanged { index -> ctx.state.switchTab(index) }
                }

                // Tab 0: 对话
                ctx.renderTab(0) { ChatPager(ctx.state) }
                // Tab 1: 发现
                ctx.renderTab(1) { DiscoverPager() }
                // Tab 2: 创作
                ctx.renderTab(2) { CreationPager() }
                // Tab 3: 我的
                ctx.renderTab(3) { ProfilePager() }
            }

            // ── ② 底部 TabBar（sub-page 时隐藏） ─────────────────────────
            vif({ ctx.state.activeSubRoute == null }) {
                View {
                    attr {
                        positionAbsolute()
                        left(0f); right(0f); bottom(0f)
                    }
                    BottomTabBar {
                        attr {
                            currentTab = ctx.state.currentTab
                            tabs = BOTTOM_TABS
                        }
                        event {
                            tabClick = { index ->
                                ctx.pageListRef.view?.setCurrentPage(index, true)
                                ctx.state.switchTab(index)
                            }
                        }
                    }
                }
            }

            // ── ③ Drawer（二级导航，仅 Chat Tab 触发） ─────────────────
            vif({ ctx.state.drawerOpen }) {
                DrawerOverlay { ctx.state.closeDrawer() }
                DrawerContent {
                    attr {
                        brandTitle = "TalkToAI"
                        brandSubtitle = "Design-Driven Smart Host"
                        entries = DRAWER_ENTRIES
                    }
                    event {
                        entryClick = { entry ->
                            ctx.state.openSubPage(entry.route)
                        }
                        dismiss = { ctx.state.closeDrawer() }
                    }
                }
            }
        }
    }

    /** 渲染单个 Tab 的内容容器（统一占满 PageList 子项） */
    private fun renderTab(
        @Suppress("UNUSED_PARAMETER") index: Int,
        content: () -> ViewBuilder
    ) = content()

    companion object {
        val BOTTOM_TABS = listOf(
            BottomTabSpec(0, "对话", "💬"),
            BottomTabSpec(1, "发现", "🔍"),
            BottomTabSpec(2, "创作", "🎨"),
            BottomTabSpec(3, "我的", "👤"),
        )

        /** Drawer 二级导航入口（参考 DshHubPager 原 DRAWER_ENTRIES 调整） */
        val DRAWER_ENTRIES = listOf(
            DrawerEntry("📁", "Workspaces",  "Workspace 列表",       "workspace_list"),
            DrawerEntry("📋", "Sessions",    "Session 管理",         "session_list"),
            DrawerEntry("🔌", "Plugins",     "插件清单（Task-5）",    "plugin_list"),
            DrawerEntry("📊", "Log Center", "日志中心（Task-6）",    "log_center"),
            DrawerEntry("🐞", "Diagnostics", "反馈问题包导出",       "diagnostics"),
            DrawerEntry("⚙️", "Settings",    "主题 / 语言 / 账户",   "settings_main"),
        )
    }
}

data class BottomTabSpec(
    val index: Int,
    val label: String,
    val emoji: String,
)
```

### 5.2 BottomTabBar 自定义组件

```kotlin
package com.example.talktoai.base.components

import com.example.talktoai.base.theme.ShapeTokens
import com.example.talktoai.base.theme.TextStyles
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * 底部 Tab 栏（TalkToAI UI 架构 §5.2）。
 *
 * 设计要点：
 * - 严格按 §2.1 描述：横向 4 等分，固定高度 56dp，顶部 0.5dp Divider；
 * - 选中态：图标 emoji 不变色（避免 Emoji 颜色不一致），改为文字变 accent 色 + 字重 medium；
 * - 点击区域 64dp 宽，超过 emoji 视觉宽度，提升可达性。
 */
internal class BottomTabBarAttr : ComposeAttr() {
    var currentTab: Int by observable(0)
    var tabs: List<BottomTabSpec> by observable(emptyList())
}

internal class BottomTabBarEvent : ComposeEvent() {
    var tabClick: ((Int) -> Unit)? = null
}

internal class BottomTabBar : ComposeView<BottomTabBarAttr, BottomTabBarEvent>() {
    override fun createAttr() = BottomTabBarAttr()
    override fun createEvent() = BottomTabBarEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flexDirectionColumn()
                    backgroundColor(ThemeColors.surface)
                }

                // ── 顶部 0.5dp 分隔线 ───────────────────────────────────
                View {
                    attr {
                        height(0.5f)
                        backgroundColor(ThemeColors.divider)
                    }
                }

                // ── Tab Row ────────────────────────────────────────────
                View {
                    attr {
                        height(56f)
                        flexDirectionRow()
                    }
                    ctx.attr.tabs.forEach { tab ->
                        BottomTabItem {
                            attr {
                                label = tab.label
                                emoji = tab.emoji
                                selected = (ctx.attr.currentTab == tab.index)
                            }
                            event {
                                click {
                                    ctx.event.tabClick?.invoke(tab.index)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.BottomTabBar(init: BottomTabBar.() -> Unit) {
    addChild(BottomTabBar(), init)
}
```

### 5.3 BottomTabItem 单项组件

```kotlin
package com.example.talktoai.base.components

import com.example.talktoai.base.theme.TextStyles
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * 单个 Tab 项（TalkToAI UI 架构 §5.3）。
 *
 * 视觉：
 *   未选中：emoji（24sp）+ label（11sp onSurfaceVariant, normal）
 *   选中  ：emoji + label（11sp accent, medium）+ 顶部 2dp accent 短线指示器
 *
 * 选中态指示器是一个 24×2dp 的小条，放在图标上方 4dp 处，模拟 iOS TabBar 风格，
 * 比纯文字变色更明显。
 */
internal class BottomTabItemAttr : ComposeAttr() {
    var label: String by observable("")
    var emoji: String by observable("")
    var selected: Boolean by observable(false)
}

internal class BottomTabItemEvent : ComposeEvent() {
    var click: (() -> Unit)? = null
}

internal class BottomTabItem : ComposeView<BottomTabItemAttr, BottomTabItemEvent>() {
    override fun createAttr() = BottomTabItemAttr()
    override fun createEvent() = BottomTabItemEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    width(0f)               // 0 表示均分（依赖父 Row 的 flex）
                    flex(1f)
                    height(56f)
                    flexDirectionColumn()
                    alignItemsCenter()
                    justifyContentCenter()
                }
                event {
                    click { ctx.event.click?.invoke() }
                }

                // 顶部小指示器（仅选中时显示）
                View {
                    attr {
                        width(if (ctx.attr.selected) 24f else 0f)
                        height(2f)
                        margin(bottom = 4f)
                        borderRadius(1f)
                        backgroundColor(
                            if (ctx.attr.selected) ThemeColors.accent
                            else Color.TRANSPARENT
                        )
                    }
                }

                // Emoji 图标
                Text {
                    attr {
                        fontSize(22f)
                        text(ctx.attr.emoji)
                    }
                }

                // 文字
                Text {
                    attr {
                        fontSize(TextStyles.LABEL_SMALL.size)
                        marginTop(2f)
                        fontWeight(if (ctx.attr.selected) FontWeight.MEDIUM else FontWeight.NORMAL)
                        color(
                            if (ctx.attr.selected) ThemeColors.accent
                            else ThemeColors.onSurfaceVariant
                        )
                        text(ctx.attr.label)
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.BottomTabItem(init: BottomTabItem.() -> Unit) {
    addChild(BottomTabItem(), init)
}
```

### 5.4 ChatPager 改造要点（基于 DshHubPager）

> **决策点**：保留 `DshHubPager` 的全部 UI 资产，只做以下调整：
>
> | 改动 | 原 `DshHubPager` | 新 `ChatPager` |
> |---|---|---|
> | 类名 | `DshHubPager` | `ChatPager` |
> | 路由 | `@Page("dsh_hub")` | `@Page("chat")` |
> | 顶部汉堡 | 内嵌点击 → `sidebarOpen = true` | 改为回调 → `state.openDrawer()`（由 AppShell 监听） |
> | 输入栏右上 + 按钮 | 内嵌 `handleSend` | 改为回调 → 触发流式回复（接 DSH） |
> | 抽屉 | 自渲染 DrawerContent | **删除**（改由 AppShell 统一渲染） |

```kotlin
@Page("chat", supportInLocal = true)
internal class ChatPager(
    private val appShellState: AppShellState,
) : BasePager() {

    // 原 DshHubPager 的所有状态保留：
    private var chatMessages: List<ChatMessage> by observable(...)
    private var selectedChip: String by observable("快速")
    private var composer: String by observable("")

    override fun body(): ViewBuilder {
        val ctx = this
        val safeTop = pagerData.safeAreaInsets.top
        val safeBottom = pagerData.safeAreaInsets.bottom
        val tabBarHeight = 56f  // AppShell 底部 TabBar 占据
        // ... 原 DshHubPager 的 body() 全部保留，仅:
        // ① 顶部汉堡 event.click { ctx.appShellState.openDrawer() }
        // ② 删除 DrawerContent / DrawerOverlay 内嵌渲染
        // ③ 底部 bottom 改为 FLOAT_PADDING + safeBottom + tabBarHeight
    }
}
```

---

## §6 迁移计划与风险

### 6.1 分阶段执行

| 阶段 | 任务 | 风险 | 验证 |
|---|---|---|---|
| **Phase 1**（并行） | ① 新建 `BottomTabBar` / `BottomTabItem` 组件<br> ② 新建 `AppShell` Pager 框架<br> ③ 新建 3 个空 Pager（Discover / Creation / Profile） | 无（新增文件） | 编译通过，启动走 `app_shell` 看到空 4 Tab |
| **Phase 2**（依赖 1） | ① 把 `DshHubPager` 复制为 `ChatPager`，去掉 Drawer 部分<br> ② AppShell 接入 `ChatPager` + 三空 Pager | 路由名 `dsh_hub` → `chat` 切换；Drawer 入口点击链路重接 | AppShell 启动 → 切 4 Tab → 顶部汉堡触发 Drawer |
| **Phase 3**（依赖 2） | ① Discover / Creation / Profile 三大 Pager 完整实现<br> ② 顶部 Drawer 入口迁移完整 | Tab 内容深度（网格、列表） | 4 个 Tab 内容齐备，Drawer 跳转全通 |
| **Phase 4**（依赖 3） | ① 引入 Kuikly `PageList` 的 `setCurrentPage` 同步逻辑<br> ② BottomTabBar 选中态动画优化 | 滑动时与 TabBar 点击的状态一致性 | 手势滑动与 TabBar 点击同步 |

### 6.2 风险矩阵

| 风险 | 等级 | 应对 |
|---|---|---|
| Kuikly `PageList` 在 OHOS / iOS 渲染不一致 | 中 | Phase 1 用 4 个最简 Pager 跑全端冒烟 |
| Emoji 在不同平台字形差异 | 低 | TabBar 准备 SVG 图标 fallback（Kuikly `Image { src }`） |
| `DshHubPager` → `ChatPager` 迁移遗漏 Drawer 引用 | 中 | 全局搜索 `sidebarOpen` / `DrawerContent`，grep 后逐个迁移 |
| 旧路由 `dsh_hub` 失效 | 低 | AppShell `created()` 阶段把 `pageName=="dsh_hub"` 重写为 `chat` |
| TabBar 与底部系统手势冲突 | 中 | 保留 `safeAreaInsets.bottom` 作为 `tabBarHeight` 计算输入 |

### 6.3 不在本次范围

- **侧滑手势冲突**：保留底部 TabBar，不引入 iOS 风格的全屏侧滑返回（Kuikly 已有 `RouterModule.closePage()`，暂用按钮触发）
- **Tab 角标 / 红点**：保留为后续 task（v1.1）
- **横屏 / 平板分栏布局**：当前 iPad 等大屏只放大内容，不做 split view
- **主题切换动画**：由 Feature-1（Theme）负责，不在 UI 架构 v1.0 范围

---

## §7 版本记录

| 版本 | 日期 | 作者 | 变更 |
|---|---|---|---|
| v1.0 | 2026-09-03 | Agent | 初稿：Drawer → 4-Tab 升级方案；AppShell + BottomTabBar + TabItem 完整 Kuikly DSL 代码；6 阶段迁移计划 |