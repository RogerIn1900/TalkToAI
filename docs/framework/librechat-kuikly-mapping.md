# UI 框架文档 · Librechat-Mobile → Kuikly 实现蓝图

> **范围**：以 [`garfiec/Librechat-Mobile`](https://github.com/garfiec/Librechat-Mobile) 的 UI 结构为参照，将其架构形态逐项映射到当前 `TalkToAI` 项目的 **Kuikly** DSL 上，输出一份「能照着写」的框架蓝图。
> **目标读者**：在 `TalkToAI/` 内做 Pager 改造与新页面开发的设计师/工程师。
> **关键差异**：参考项目用 **Jetpack Compose / Compose Multiplatform**（声明式、`@Composable`、`MaterialTheme`、Nav 3），当前项目用 **Kuikly**（`ViewBuilder { ... }` + `attr { ... }` 嵌套 DSL，`@Page` + `RouterModule`，自绘主题）。文档中所有代号转换都给出代码样例，可直接复刻。

---

## 0 · 阅读顺序建议

1. 先看 §1 摸清 Librechat 的整体 UI 骨架（模块 / 主题 / 状态 / 导航）
2. 再读 §2 了解 Compose → Kuikly 的「通用翻译表」（所有 Pager 都共用）
3. 然后按 §3 的分屏蓝图照着写 Pager，§4 的列表/抽屉/聊天三大件是最高频
4. §5 的细节规则（性能 / 无障碍 / 主题）是评分维度

---

## 1 · 整体架构对照

### 1.1 参考项目（Librechat-Mobile）的模块结构

```
Librechat-Mobile/
├── app/                          # androidApp 仅壳；纯 entry，路由交给 shared
├── shared/                       # KMP umbrella，导出 iOS framework
│   ├── commonMain/
│   │   ├── LibreChatSDK.kt       # 业务门面
│   │   ├── di/                   # sharedKoinModules
│   │   ├── navigation/           # Nav3 路由 + SidebarScaffold + PhoneLayout
│   │   └── app/                  # 根 Composable
│   ├── iosMain/                  # iOS 平台入口 + Koin Helper
│   └── androidMain/              # 占位（实际壳在 androidApp）
├── core/
│   ├── common   # 通用工具、ForegroundSignal、AccountState
│   ├── model    # 纯 Kotlin 数据类
│   ├── network  # Ktor 客户端 + SSE 引擎（OkHttp / Darwin）
│   ├── data     # Room（缓存）+ DataStore（偏好）+ EncryptedSharedPreferences（密钥）
│   ├── logging  # Kermit + Diag 面包屑
│   └── ui/      # ★ UI 单一源
│       ├── theme/         # Material 3 调色、Typography、Shape
│       ├── components/    # 跨 feature 通用 Composable
│       ├── message/       # MessageBubble / ToolCallCard
│       └── media/         # 图片加载与缓存
├── feature/
│   ├── auth         # ServerURL + AddAccountServerUrl + 登录
│   ├── chat         # ★ 主战场：聊天、附件、流式、Voice
│   ├── conversations# 列表、归档、Project、Tag、搜索
│   ├── files        # 文件浏览器、Picker
│   ├── agents       # Agent 市场 + MCP
│   ├── skills       # Skills
│   └── settings     # 主题 / 语言 / 数据 / 账户 / Chat
└── buildSrc/        # librechat.mobile.library / .compose 等约定插件
```

### 1.2 当前项目（TalkToAI）的对应位置

```
TalkToAI/
├── androidApp/           # android 入口壳（KuiklyRenderActivity + DshClientHolder）
├── iosApp/               # ios 入口壳
├── ohosApp/              # 鸿蒙入口壳（OpenHarmony / Kuikly 主战场之一）
├── shared/src/commonMain/kotlin/com/example/talktoai/
│   ├── base/             # ★ UI 单源（Kuikly 版本）
│   │   ├── BasePager.kt            # 基类、主题钩子、生命周期
│   │   ├── BridgeModule.kt         # JS / Native 桥
│   │   ├── Utils.kt / IPagerIdKtx.kt
│   │   └── dsh/
│   │       ├── DshHubPager.kt       # 豆包式首页（侧边栏 + 输入框）
│   │       ├── ChatPager.kt         # 对话主页面
│   │       ├── SessionListPager.kt  # 会话列表
│   │       ├── WorkspaceListPager.kt
│   │       └── DiagnosticsPager.kt  # 日志/诊断
│   └── bridge/                       # Native 端扩展点（实现在 androidApp）
└── dsh/                  # 业务 SDK（Model / Client / Remote）
```

### 1.3 关键映射表

| 维度 | Librechat (Compose) | TalkToAI (Kuikly) | 当前文件 |
|---|---|---|---|
| UI 单源 | `:core:ui` | `shared/.../base/` | — |
| 主题 | `LibreChatTheme` (Material 3) | `BasePager.themeDidChanged()` + `Color` token | `BasePager.kt` |
| 路由 / 页面栈 | Nav 3 `NavBackStack` + `entryProvider` | `@Page("route")` + `RouterModule.openPage(route, params)` | `RouterModule` |
| 跨页导航 | `Navigator.navigate(route)` | `acquireModule<RouterModule>().openPage(...)` | `DshHubPager.kt:121` |
| 侧边栏 / Drawer | `ModalNavigationDrawer` + `SidebarScaffold` | 绝对定位遮罩 + 条件渲染侧栏 | `DshHubPager.kt:331-415` |
| ViewModel | `koinViewModel()` + `StateFlow<UiState>` | `pagerId { DshBridgeModule(...) }` + `observable` | `BasePager.kt`, `ChatPager.kt` |
| 异步事件 | `LaunchedEffect(key) { ... }` | `setTimeout(ms) { ... }` | `Utils.kt` |
| 跨端 RPC | Koin 单例 + `suspend fun` | `BridgeModule.callJsonRpc(method, args, callback)` | `BridgeModule.kt` |
| 性能规则 | `@Immutable`、stable List/Map、`key`/`contentType` | `observable` 字段、用 `List<>` 引用相等 | §5 |

---

## 2 · 通用翻译表（Compose → Kuikly DSL）

> 以下片段都是可复用的「最小替换对」。新写 Pager 时按行复制即可。

### 2.1 容器 / 布局

| Compose | Kuikly 等价 | 备注 |
|---|---|---|
| `Box { ... }` | `View { attr { ... } ... }` | 根容器 |
| `Column(modifier = M.fillMaxSize()) { ... }` | `View { attr { flex(1f); flexDirectionColumn() } ... }` | `flex(1f)` 即 `fillMaxSize` |
| `Row(...) { ... }` | `View { attr { flexDirectionRow(); alignItemsCenter() } ... }` | Row 必须显式 `alignItems*` |
| `Box(modifier = M.weight(1f))` | `View { attr { flex(1f) } ... }` | `flex()` 替代 `weight` |
| `M.padding(8.dp)` | `attr { padding(8f) }` 或 `padding(horizontal=8f, vertical=4f)` | Kuikly 默认是 4 边 |
| `M.padding(start = 8.dp, top = 4.dp)` | `attr { padding(left = 8f, top = 4f) }` | 显式 4 边更稳 |
| `M.fillMaxWidth().padding(...)` | `attr { padding(left = 8f, right = 8f, top = 4f, bottom = 4f) }` | Kuikly 没有 fillMaxWidth，padding 自动 |
| `M.size(width, height)` | `attr { size(width, height) }` 或拆 `width(...) + height(...)` | |
| `M.offset(x, y)` | `attr { margin(left = x, top = y) }` 或 `positionAbsolute() + left/top` | |
| `LazyColumn { items(list, key) { ... } }` | `Scroller { attr { ... }; (1..list.size).forEach { ... } }` | 一定要给稳定 key；见 §5 |
| `VerticalScroll(rememberScrollState())` | `Scroller { attr { ... } }` | 默认纵向 |
| `HorizontalScroll` | `Scroller { attr { flexDirectionRow() } }` | |
| `Spacer(modifier = M.height(8.dp))` | `View { attr { height(8f) } }` | |

#### 复合示例：商品卡片（Compose vs Kuikly）

```kotlin
// —— Compose ——
@Composable
fun ProductCard(name: String, price: String) {
    Card(shape = RoundedCornerShape(12.dp), elevation = 2.dp) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(price, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```

```kotlin
// —— Kuikly ——
private fun productCard(name: String, price: String): ViewBuilder {
    return {
        View {
            attr {
                padding(16f)
                borderRadius(12f)
                boxShadow(BoxShadow(0f, 2f, 8f, Color(0x1A000000)), false)
                backgroundColor(Color.WHITE)
                flexDirectionColumn()
            }
            Text {
                attr {
                    fontSize(17f)
                    fontWeightSemiBold()
                    text(name)
                }
            }
            View { attr { height(4f) } }            // Spacer
            Text {
                attr {
                    fontSize(12f)
                    color(Color(0xFF999999))
                    text(price)
                }
            }
        }
    }
}
```

### 2.2 文本 / 排版

| Compose | Kuikly | 备注 |
|---|---|---|
| `Text("foo", color = Red, fontSize = 14.sp)` | `Text { attr { text("foo"); color(Color.RED); fontSize(14f) } }` | 顺序不敏感但建议 color→fontSize→text |
| `Text("foo", style = MaterialTheme.typography.titleMedium)` | `Text { attr { text("foo"); fontSize(17f); fontWeightSemiBold() } }` | 没有 Typography token，自己挑字号 |
| `Text("foo", textAlign = TextAlign.Center)` | `Text { attr { text("foo"); textAlign(TextAlign.CENTER) } }` | |
| `Text("foo", maxLines = 2, overflow = TextOverflow.Ellipsis)` | `Text { attr { text("foo"); maxLines(2); ellipsis() } }` | |
| `AnnotatedString { withStyle(...) { append(...) } }` | `Text { attr { text(...) } }` 内嵌多个 `Span` 块（富文本） | Kuikly 支持富文本构造，见 [Kuikly 文档：Rich Text](https://kuikly.tds.qq.com/) |

### 2.3 按钮 / 点击

| Compose | Kuikly |
|---|---|
| `Button(onClick = { ... }) { Text("OK") }` | `Button { attr { ... }; event { click { ... } } }` |
| `IconButton(onClick) { Icon(...) }` | `Text { attr { text("⚙"); fontSize(20f) }; event { click { ... } } }` |
| `FloatingActionButton(onClick)` | `View { attr { positionAbsolute(); right(...); bottom(...); borderRadius(28f); backgroundColor(accent); size(56f); alignItemsCenter(); justifyContentCenter() }; event { click { ... } } }` |
| `Card(onClick = { ... })` | 卡片 `View` 上挂 `event { click { ... } }` |

#### 按钮示例

```kotlin
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) {
        Text(text, color = Color.White)
    }
}
```

```kotlin
private fun primaryButton(text: String, onClick: () -> Unit): ViewBuilder {
    return {
        Button {
            attr {
                titleAttr {
                    text(text)
                    color(Color.WHITE)
                    fontSize(15f)
                }
                backgroundColor(Color(0xFF3B82F6))
                borderRadius(12f)
            }
            event { click { onClick() } }
        }
    }
}
```

### 2.4 列表 / 滚动

**核心原则**：Kuikly 的 `Scroller` 不像 `LazyColumn` 自动回收 —— 实际生产中我们用 `Scroller` 内 forEach，对所有 `item {}` 显式给稳定 key。

| Compose | Kuikly |
|---|---|
| `LazyColumn { items(20) { i -> Text("$i") } }` | `Scroller { (0 until 20).forEach { i -> Text { attr { text("$i") } } } }` |
| `items(list, key = { it.id }) { item -> ... }` | `Scroller { list.forEach { item -> View { attr { /* 业务样式 */ } } } }`（Kuikly 2.x 无公开 keyTag；走 native 层 viewId differential） |
| `items(list, contentType = { "header" / "row" })` | 用 sealed class 区分，分支渲染 |

> ⚠️ **当前 TalkToAI 实测**：vlist 完全可以直接 nested forEach（消息列表往往 < 100 条），暂不需要虚拟化。如遇长列表性能塌方，先试 `Scroller { lazy = true }`（Kuikly 1.0+ 提供）或拆 `Scroller` + `View`。

### 2.5 弹窗 / Sheet

| Compose | Kuikly |
|---|---|
| `AlertDialog(...)` | `AlertDialog { attr { ... } }`（Kuikly 原生弹窗） |
| `ModalBottomSheet { content() }` | `View { attr { positionAbsolute(); left(0f); right(0f); bottom(0f); height(400f); backgroundColor(W); borderRadius(topLeft=20f, topRight=20f); zIndex(99, false) } ... }` + 遮罩 |
| `DropdownMenu` | `View { attr { positionAbsolute(); ... }; /* 子菜单项 */ }` |
| `Snackbar` | 顶部 / 底部浮 `View { attr { ...; positionAbsolute(); top(bannerTop) } }` |

#### BottomSheet 完整骨架（与 Librechat 的 `BottomSheetScaffold` 等价）

```kotlin
private fun showModelSelectorSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: ViewBuilder,
) {
    if (visible) {
        // 半透明遮罩
        View {
            attr {
                positionAbsolute()
                top(0f); left(0f); right(0f); bottom(0f)
                backgroundColor(Color(0x66000000))
                zIndex(98, false)
            }
            event { click { onDismiss() } }
        }
        // Sheet 容器
        View {
            attr {
                positionAbsolute()
                left(0f); right(0f); bottom(0f)
                height(520f)
                backgroundColor(Color.WHITE)
                borderRadius(topLeft = 20f, topRight = 20f, bottomLeft = 0f, bottomRight = 0f)
                zIndex(99, false)
                flexDirectionColumn()
            }
            // 顶部 drag handle
            View {
                attr {
                    height(4f); width(40f)
                    margin(top = 8f, bottom = 8f)
                    backgroundColor(Color(0xFFE0E0E0))
                    alignSelfCenter()
                }
            }
            content()
        }
    }
}
```

### 2.6 状态 / 副作用

| Compose | Kuikly |
|---|---|
| `var x by remember { mutableStateOf("") }` | `private var x: String by observable("")`（Pager 内字段） |
| `val state = produceState(initial) { ... }` | `setTimeout(ms) { ... }` |
| `LaunchedEffect(key) { ... }` | `setTimeout(0) { ... }` 或在 `created()` 内 `LaunchedEffect`（Kuikly 提供） |
| `rememberCoroutineScope()` | `setTimeout` / `launchInPager`（见 `BasePager` 工具） |
| `viewModel.state.collectAsState()` | `pagerId { ... }` + 直接读 observable |
| `BackHandler { ... }` | `event { back { ... } }` |

> **`observable` 关键规则**：Kuikly 增量更新靠 setter；**别在 set 的回调里再次 mutate 同字段**，否则死循环。要派生就开第二个字段（详见 §5）。

### 2.7 主题色 / 令牌

| Compose | Kuikly 等价（短期方案） |
|---|---|
| `MaterialTheme.colorScheme.primary` | `BasePager.themeAccent()`（在 `BasePager` 里提供 `by observable` getter） |
| `MaterialTheme.colorScheme.surface` | `BasePager.themeSurface()` |
| `MaterialTheme.colorScheme.onSurfaceVariant` | `BasePager.themeOnSurfaceVariant()` |
| `MaterialTheme.colorScheme.background` | `BasePager.themeBackground()` |
| `MaterialTheme.typography.titleMedium` | 当前版本用硬编码字号（`fontSize(15f); fontWeightSemiBold()`） |

> 中期目标：实现 `LibreChatTheme` 同款 —— **基于 accent 色生成 13 色板**，映射到 Kuikly 的 `Color` token（参见 §4.1）。

### 2.8 图片加载

| Compose | Kuikly |
|---|---|
| `AsyncImage(model = url, contentDescription = ...)` | `Image { attr { src(painterResource(...)); size(...); placeholder(...) } }`（用 `KRImageAdapter` 桥接 Coil） |

> 当前 `TalkToAI/androidApp` 已有 `KRImageAdapter.kt`；新写 Pager 时直接用 `bridge.imageAdapter.load(url, ref)`。

---

## 3 · 屏幕蓝图（Screen Blueprint）

> 每个屏幕都给出「视觉骨架 → 数据模型 → 关键交互 → 实现优先级」。完全照搬 Librechat 的页面名，方便对照。

### 3.1 根布局：`PhoneLayout` / 平板双栏

Librechat 的 `PhoneLayout` 把整个 app 包成 `ModalNavigationDrawer(DrawerContent + MainNavDisplay)`。TalkToAI 当前的 `DshHubPager` 已经在干这事，但只是首页。需要抽象成公用组件。

#### 蓝图

```text
┌─ App 根（@Page("app_root") 或每个 Pager 自维护） ──────────────┐
│  if (loggedIn) {                                                │
│   ┌───────────────────────────┬────────────────────────────┐   │
│   │ Modal Drawer (左侧滑出)    │ Main NavDisplay            │   │
│   │ ┌─────────────────────┐   │  ┌──────────────────────┐  │   │
│   │ │ 账号 Chip / 切换     │   │  │ 当前 Pager (Chat)    │  │   │
│   │ ├─────────────────────┤   │  │                      │  │   │
│   │ │ New Chat            │   │  │  ╔════════════════╗  │  │   │
│   │ │ ─── 对话历史 ───     │   │  │  ║ Banner 可选   ║  │  │   │
│   │ │  • 会话项           │   │  │  ╚════════════════╝  │  │   │
│   │ │    滑动归档/删除     │   │  │  ┌────────────────┐  │  │   │
│   │ │  • Date Header      │   │  │  │ 消息流          │  │  │   │
│   │ ├─────────────────────┤   │  │  │                │  │  │   │
│   │ │ Workspaces          │   │  │  │                │  │  │   │
│   │ │ Sessions            │   │  │  └────────────────┘  │  │   │
│   │ │ Plugins             │   │  │  ┌────────────────┐  │  │   │
│   │ │ Skills              │   │  │  │ 输入条          │  │  │   │
│   │ │ Files               │   │  │  └────────────────┘  │  │   │
│   │ │ Settings            │   │  └──────────────────────┘  │   │
│   │ └─────────────────────┘   │                            │   │
│   └───────────────────────────┴────────────────────────────┘   │
│  } else { AuthPager }                                           │
└─────────────────────────────────────────────────────────────────┘
```

#### 抽出公用 scaffold（新文件建议）

`shared/.../base/AppShell.kt`：

```kotlin
@Page("app_root", supportInLocal = true)
internal class AppShell : BasePager() {

    private var drawerOpen by observable(false)
    private var currentRoute by observable("dsh_hub")

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    backgroundColor(BasePager.themeBackground())
                    flexDirectionRow()       // 平板双栏预备
                }

                // 平板：常驻侧栏；手机：条件渲染
                if (pageData.isTablet) {
                    ctx.renderSidebar(isDrawer = false)
                    View { attr { flex(1f); flexDirectionColumn() } }
                }

                // 主内容（NavDisplay）
                View {
                    attr { flex(1f); flexDirectionColumn() }
                    // ... 渲染 currentRoute 对应 Pager
                }
            }

            // 手机抽屉：遮罩 + 侧栏
            if (!pageData.isTablet && ctx.drawerOpen) {
                View {
                    attr {
                        positionAbsolute()
                        top(0f); left(0f); right(0f); bottom(0f)
                        backgroundColor(Color(0x66000000))
                        zIndex(50, false)
                    }
                    event { click { ctx.drawerOpen = false } }
                }
                ctx.renderSidebar(isDrawer = true)
            }
        }
    }

    private fun renderSidebar(isDrawer: Boolean): ViewBuilder { /* 见 §3.2 */ }
}
```

### 3.2 DrawerContent（侧栏 / 对话历史 + 入口）

完全对应 Librechat 的 `drawer/DrawerContent.kt`。

#### 视觉结构

```text
侧栏根容器（positionAbsolute|flex，width 280f，背景白）
├─ Header（顶部品牌区，背景色 = theme.accent，paddingTop = safeTop+16）
│   ├─ 大字 Logo
│   └─ 小字 subtitle
├─ 账号 Chip / 切换区（点开 sheet 列账号）
├─ "New Chat" 入口（按钮样式）
├─ ── 对话历史分组 ──
│   ├─ "Today" sticky header
│   │   └─ ConversationItem×N（左滑显示"归档"按钮）
│   ├─ "Yesterday"
│   ├─ "Previous 7 Days"
│   └─ "Previous 30 Days"
├─ ── 入口区 ──
│   ├─ Workspaces（路由：workspace_list）
│   ├─ Sessions / Plugins / Skills / Files
│   └─ Settings（路由：diagnostics/扩展为 settings_main）
└─ Footer（关闭/版本号）
```

#### 数据模型（对应 `:core:model` 的 `Conversation`）

```kotlin
data class DrawerConversation(
    val id: String,           // sessionId
    val title: String,
    val snippet: String?,     // 最近一条消息摘要
    val dateBucket: String,   // "Today" / "Yesterday" / "Previous 7 Days" / ...
    val timestampMs: Long,
    val isActive: Boolean,    // 高亮当前会话
    val isArchived: Boolean,
    val tags: List<String>,   // 标签过滤
)

sealed class DrawerListItem {
    data class DateHeader(val label: String) : DrawerListItem()
    data class ConvoItem(val data: DrawerConversation) : DrawerListItem()
}
```

#### 关键交互

| 行为 | 触发 | 实现 |
|---|---|---|
| 点击会话项 | `click { ... }` | `navigateToChat(conversationId)` |
| 滑动归档 | 左右手势 | 见 §5 「滑动操作」 — 当前 Kuikly 用 `if (swipeX > 80) showArchive` 简单方案；后续替换为 GestureDetector |
| 切换账号 | Header chip click | `bottomSheet { accountList }` |
| New Chat | "新建对话" 按钮 | `router.openPage("chat", JSONObject().put("reset", true))` |

#### Pager 字段

```kotlin
private var conversations: List<DrawerConversation> by observable(emptyList())
private var searchText: String by observable("")
private var selectedTags: Set<String> by observable(emptySet())
private var drawerOpen: Boolean by observable(false)
```

> 与 `DshBridgeModule` 的契约（建议下一步扩）：`dsh.listSessions(filter)` → `List<DrawerConversation>`；`dsh.archiveSession(id)`；`dsh.renameSession(id, title)`。

### 3.3 ChatScreen（对话主页面）

Librechat 中 `ChatScreen` 是 `feature/chat` 的 90%。当前 `ChatPager.kt` 仅 208 行，需要大改造。

#### 视觉结构

```text
ChatScreen 根（Column, bg = theme.background）
├─ ChatFloatingTopBar（绝对定位，顶部）
│   ├─ 菜单按钮（left）
│   ├─ ModelSelectorButton（中间，显示当前 model，点开 Sheet）
│   └─ More 菜单（right：分享、设置入口）
├─ Banner（条件：服务方推送）
├─ MessageList（flex 1，可滚动，LazyColumn 等价）
│   ├─ ActivityGroup（合并 tool call）
│   ├─ MessageBubble（用户 / 助手）
│   │   ├─ Avatar（左侧 28dp）
│   │   ├─ 气泡（最大宽度 80% 屏宽，圆角 12）
│   │   │   ├─ Markdown（含代码块、语言徽标、复制按钮）
│   │   │   ├─ CodeBlock（独立卡）+ 语言徽标
│   │   │   ├─ LaTeX（块/行）
│   │   │   ├─ ToolCallCard（折叠）
│   │   │   ├─ ImageCard
│   │   │   └─ Action Row（复制 / 重生成 / 编辑）
│   │   └─ SiblingNavigator（仅助手：1/N 切换）
│   ├─ StreamingMessageBubble（流式尾部）
│   │   ├─ StreamingCursor（cursor 内嵌 inline 文本）
│   │   └─ 增量 Markdown（100ms 防抖）
│   ├─ "Scroll to bottom" FAB
│   └─ QueuedMessagesSection（队列中的待发送）
├─ PendingQuoteChipsSection（草稿引用回复）
├─ PendingSteerChipsSection（引导指令）
├─ AttachmentChipsRow（已附文件）
└─ ChatInput（绝对定位，bottom）
    ├─ + 按钮（打开附件菜单）
    ├─ TextField（autoGrow，placeholder=发消息…）
    ├─ 🎤 语音按钮（长按录音）
    └─ 发送按钮（圆形）
```

#### 状态字段（参照 `ChatUiState` 17 个 slice）

```kotlin
private var composer: String by observable("")                 // 输入框当前文字
private var attachments: List<AttachmentRef> by observable(emptyList())
private var isStreaming: Boolean by observable(false)
private var streamingText: String by observable("")            // 当前流式累积
private var messages: List<UiMessage> by observable(emptyList())
private var branchIndex: Map<String, Int> by observable(emptyMap())
private var quotedMessageId: String? by observable(null)
private var showModelSheet: Boolean by observable(false)
private var showOptionSheet: Boolean by observable(false)
private var pendingAction: PendingAction? by observable(null)   // AskUserQuestion
private var searchQuery: String by observable("")
private var currentModel: ModelRef by observable(ModelRef("openai", "gpt-4o"))
```

#### 性能与流畅性（与 Librechat ChatScreen 一致）

1. **流式渲染防抖**：监听 `streamingText`，记上一帧文本长度；每 ≥ 60 字符或 ≥ 100ms 才 push 到 Markdown Renderer（对应 `CachedMarkdown` 节流）。
2. **Markdown 解析缓存**：`messageId+rev` → `ParsedMarkdown`。Librechat 用 LRU（`LruSnapshotCache.kt`），Kuikly 端用 `mutableMapOf` + 手动 trim 到 50。
3. **MessageList key**：Kuikly 2.7 无公开 `keyTag` API（`viewId` 由 native 内部分配，外部不可写），列表复用靠底层 differential 跟踪。实际生产中：消息列表 < 100 条直接 `forEach { msg -> View { attr { /* ... */ } } }` 没问题；如遇复用错乱，把 `forEach` 替换为 `forEachIndexed` 并给 `View { attr { /* id-only identity hint */ } }` 留显式注释，或迁移到 `Scroller { lazy = true }`（Kuikly 1.0+ 提供，2.x 已 GA）。
4. **In-place 收尾**（Completion Render）：流式 → finalize 时**同一个** `View` 节点更新文本，不要 remove + add，保持滚动锚点。

#### 关键交互

| 行为 | 实现 |
|---|---|
| 发送文本 | `composer.trim().isNotEmpty()` → `appendOptimistic + startStream` |
| 停止生成 | `abortChat()`，保留 partial，不重载 |
| 重生成 | 在 `MessageBubble` action row 点 `↻`，调用 `regenerateMessage(parentId)` |
| 编辑 user msg | 内联 `InlineEditInput` 替换原气泡，发送后**新分支** |
| 切换分支 | 助手气泡底部 `1/3` chevron → `switchBranch(parentId, index)` |
| 选模型 | FloatingTopBar Chip → `showModelSheet = true` |
| 文本选中 | `Text { attr { selectable(true) } }`（Kuikly 内建） |
| 复制 / 分享 / 导出 | 气泡长按 → 弹 MenuSheet；调用 `BridgeModule.share/copyText` |
| 附件 | 输入条 `+` → 相册/文件 picker；选完走 `KRImageAdapter` |

### 3.4 ModelSelectorSheet（选模型 BottomSheet）

Librechat 的 `ModelSelectorSheet.kt` 是个 **「搜索 + endpoint 分组」** 的 BottomSheet。

```text
ModalSheet（height 80%）
├─ Header：Title "选择模型" + 关闭 X
├─ SearchBar（input，纯本地过滤）
├─ ComparationDualPane（可选：双栏对照）
└─ LazyColumn
    ├─ "OpenAI" group header
    │   ├─ Row：icon + 名字 + 上下文长度徽标 + 简介 + 选中 ✓
    │   └─ ...
    ├─ "Anthropic" group header
    └─ "Custom" group header
```

```kotlin
private fun ModelSelectorSheet(
    endpointGroups: List<EndpointGroup>,
    selected: ModelRef,
    searchQuery: String,
    onSelect: (ModelRef) -> Unit,
): ViewBuilder {
    return {
        View { /* Sheet container, 见 §2.5 */ }
        Text { attr { text("选择模型"); fontSize(18f); fontWeightBold() } }
        Input {
            attr { placeholder("搜索模型…"); flex(1f) }
        }
        Scroller {
            attr { flex(1f) }
            endpointGroups
                .filter { searchQuery.isBlank() || it.models.any { m -> m.label.contains(searchQuery, true) } }
                .forEach { group ->
                    Text {
                        attr { text(group.label); fontSize(13f); color(themeOnSurfaceVariant); padding(top=12f, bottom=6f) }
                    }
                    group.models.forEach { model ->
                        ModelRow(
                            model = model,
                            isSelected = model.ref == selected,
                            onClick = { onSelect(model.ref); close() }
                        )
                    }
                }
        }
    }
}
```

### 3.5 对话历史（ConversationListScreen → 已合并到 Drawer）

Librechat 把它独立成 `feature/conversations/`，drawer 只是消费它。本项目可以选：
- **方案 A（简单）**：Drawer 直接持 list，最快出活
- **方案 B（解耦）**：抽 `ConversationListPager` 共享组件，被 Drawer 和独立的「会话列表 Pager」共用

短期走 A，中期迁 B。

### 3.6 Settings（含子页）

Librechat 的 `SettingsTabbed` 用 `TabRow` 把 4 个分类（General / Chat / Account / Data）放在顶部。当前 `DiagnosticsPager` 99 行承载了部分诊断能力。

```text
SettingsPager（@Page("settings_main")）
├─ TopBar：← 返回 + "设置"
├─ TabRow：General | Chat | Account | Data
└─ Body（按 tab 切换）
    ├─ General：主题、语言、字体大小、通知
    ├─ Chat：默认模型、流式开关、Markdown 开关、Artifact 开关
    ├─ Account：当前账号、切换、登出、删除账号
    └─ Data：清缓存、导出诊断包（已存在）
```

#### TabRow 模板

```kotlin
private fun TabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
): ViewBuilder {
    val ctx = this
    return {
        View {
            attr {
                flexDirectionRow()
                backgroundColor(Color.WHITE)
                borderBottom(Border(0.5f, BorderStyle.SOLID, Color(0xFFE0E0E0)))
            }
            tabs.forEachIndexed { i, label ->
                View {
                    attr {
                        flex(1f)
                        padding(top = 12f, bottom = 12f)
                        alignItemsCenter()
                        backgroundColor(
                            if (i == selectedIndex) Color(0xFFFFF7E6) else Color.WHITE
                        )
                        borderBottom(
                            if (i == selectedIndex) Border(2f, BorderStyle.SOLID, themeAccent())
                            else Border(0f, BorderStyle.SOLID, Color.TRANSPARENT)
                        )
                    }
                    event { click { ctx.onSelect(i) } }
                    Text {
                        attr {
                            text(label)
                            fontSize(14f)
                            fontWeight(if (i == selectedIndex) FontWeight.BOLD else FontWeight.NORMAL)
                            color(if (i == selectedIndex) themeAccent() else Color(0xFF333333))
                        }
                    }
                }
            }
        }
    }
}
```

### 3.7 Voice（语音）

Librechat 的 `feature/chat/audio/` + VoiceComponent：
- **Live 语音（听写）**：长按 🎤 录音，onResult 回写 `composer`
- **TTS**：长按助手气泡的 `🔊`
- **Voice Sheet**：动效 + 频谱条

新写 Pager 嵌入三个按钮 + 一个 `VoiceSheet`（条件渲染的浮层）。

### 3.8 Media Viewer（全屏图片浏览）

Librechat 称之为 "Google-Photos-style"：
- 全屏 100%
- 缩放/拖动
- 左右滑切换（同分支/grid）

Kuikly 直接用根 `View` + 绝对定位 + `Image` 组件；缩放外加 `gesture { pinch { ... } }`。整体可参考 `LocalChatMediaViewer.kt`。

### 3.9 Onboarding（首次接入）

Librechat 分为 `ServerUrl`、`AddAccountServerUrl`、`Login`、`OAuth` 等条目，由 `feature/auth/navigation/authEntries()` 注册。我们当前的 Onboarding 还没起来，对照下表新增：

| Step | 内容 | 路由名 |
|---|---|---|
| 1 | 输入服务器地址 | `onb_server` |
| 2 | 登录（账号密码） | `onb_login` |
| 3 | 加载模型列表 | `onb_load_models` |
| 4 | 选主模型 | `onb_pick_model` |
| 5 | 完成进首页 | `dsh_hub` |

---

## 4 · 主题系统（Theme system）

### 4.1 当前现状

- `BasePager.themeDidChanged()` 只暴露一个「主题已变」钩子。
- Pager 里硬编码 `0xFFF5F7FA`、`0xFF3B82F6`、`0xFF1A1A1A` 等色。

### 4.2 目标

复刻 Librechat 的 Material 3 + MaterialKolor 动态调色能力，但要降到 Kuikly 的 `Color` token。

#### 抽象接口（在 `BasePager`）

```kotlin
abstract class BasePager {
    companion object {
        // 颜色 token getter（由 App 在启动时注册实际值）
        var themeBackground: () -> Color   = { Color(0xFFF5F7FA) }
        var themeSurface: () -> Color      = { Color.WHITE }
        var themeAccent: () -> Color       = { Color(0xFF3B82F6) }
        var themeOnSurfaceVariant: () -> Color = { Color(0xFF666666) }
        var themeOnSurface: () -> Color    = { Color(0xFF1A1A1A) }
        // 深色对应集
        var themeBackgroundDark: () -> Color = { Color(0xFF121212) }
        var themeSurfaceDark: () -> Color = { Color(0xFF1E1E1E) }
        var themeAccentDark: () -> Color  = { Color(0xFF60A5FA) }
        var themeOnSurfaceDark: () -> Color = { Color(0xFFE5E5E5) }
        var themeOnSurfaceVariantDark: () -> Color = { Color(0xFFB0B0B0) }

        var isNightMode: () -> Boolean = { false }
        var currentAccentSeed: () -> Color = { Color(0xFF3B82F6) }
    }
}
```

#### 应用层注册（在 `KuiklyRenderActivity` / `AppStartup`）

```kotlin
fun installTheme() {
    val accent = DataStore.get("theme.accent")?.let(::parseColor) ?: Color(0xFF3B82F6)
    val useDynamic = DataStore.get("theme.useDynamic", false)
    val night = DataStore.get("theme.nightMode", "system")
    val resolvedNight = when (night) {
        "light" -> false
        "dark"  -> true
        else    -> AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
    }

    BasePager.themeAccent = {
        if (resolvedNight) Color(0xFF60A5FA) else MaterialKolor.generateAccent(accent, resolvedNight)
    }
    BasePager.themeBackground = {
        if (resolvedNight) Color(0xFF121212) else Color(0xFFF5F7FA)
    }
    // … 其他 token
    BasePager.themeDidChanged()              // 通知所有 Pager 重绘
}
```

#### MaterialKolor 等价（精简版）

Kuikly 侧只需：
- 输入 accent hex
- 输出 13 色 plate（primary / onPrimary / primaryContainer / onPrimaryContainer / secondary / … / surfaceVariant / outline）
- 按 Material 3 色域规则取主色 Container / onContainer / 等等

可用 Jetpack `androidx.compose.material3.ColorScheme.fromSeed(...)` 的 Kotlin 实现移植：在 `androidMain` 跑一遍，得到 13 个 ARGB，再喂给上面的 token setter。

> 当前 `Subagent-1`（`Task 1`）已经在做这件事；本框架文档定义接口，而颜色生成算法是它的任务。

### 4.3 Typography（字体阶梯）

| Token | fontSize | fontWeight | 用途 |
|---|---:|---|---|
| `displayLarge` | 30 | bold | 空状态插画副标题（少用） |
| `titleLarge` | 22 | bold | 顶部标题 |
| `titleMedium` | 17 | semibold | 卡片标题、消息头 |
| `bodyLarge` | 16 | normal | 普通正文 |
| `bodyMedium` | 14 | normal | 列表项主文字 |
| `bodySmall` | 12 | normal | 副文、日期 header |
| `labelSmall` | 11 | normal | 状态徽标 |

封装成 `TextStyles.kt`，`Pager` 内直接 `Text { attr { textStyle(TextStyles.TITLE_MEDIUM); ...} }`。

### 4.4 Shape（圆角）

| Token | value |
|---|---|
| `XS` | 4 |
| `SM` | 8 |
| `MD` | 12 |
| `LG` | 16 |
| `XL` | 24 |
| `PILL` | 999 |

---

## 5 · 性能 / 稳定性规则（必看）

### 5.1 状态层级

**Librechat 规则**：
1. 一个 Screen 一个 `StateFlow<XyzUiState>`，不要 5+ 个分散 StateFlow
2. UiState 上 `@Immutable`，字段 `val`
3. 数据变换放进 ViewModel，Pager 只 `collect`
4. 在最窄范围 `collect`（DrawerContent 读 drawer state，别在 PhoneLayout 读）

**Kuikly 对应**：
1. 一个 Pager 内 `observable` 字段尽量集中 5 个以内（state slices）；超 8 个就抽 group。
2. Pager 内字段就是 state，**别让子组件内再有 `observable`**（触发更难追踪）。
3. 业务计算放 `data class`，如 `UiMessage.summary()`；Pager 只用 O(n) 映射。
4. 在子节点内 `observable` 收集 —— 对应 `setTimeout` 触发 setState。

### 5.2 列表（替代 Compose 的 LazyColumn）

Librechat 用 `key`、`contentType`、一个 `item {}` 一个 composable、避免嵌套同方向滚动。Kuikly 端：
1. **item key**：Kuikly 2.7 没有用户可写的 `keyTag`（mapping 文档初版误把它列为可选 API，实测在 `com.tencent.kuikly-open:core:2.7.0-2.1.21` 中无此成员）；Kuikly 在 native 层用 viewId + struct equality 做 differential 更新，常规 `forEach { item -> View { attr { /* */ } } }` 即可。如需显式稳定 key，等到 Kuikly 后续公开 `itemKey`/`setItemIdentity` 之后再切。
2. 同类型连续时，**抽子 Composable**（ViewBuilder 函数）才能让 Kuikly 增量更新。
3. **别在 `Scroller` 里再套 `Scroller` 同方向**；纵向列表足够。

### 5.3 避免每帧 allocate

| 反模式 | 正解 |
|---|---|
| `Modifier.padding(8.dp).fillMaxWidth()` 拼 5 个临时 | Pager 顶部 `companion object` 抽常量（`private val PADDING_8 = 8f`） |
| `RoundedCornerShape(8.dp)` 反复 new | 同上，提到顶 |
| `Color.Gray.copy(alpha = 0.5f)` 每帧 | 预算一组 `Color(0x80808080)`、`Color(0x40808080)` |
| `DateTimeFormatter.ofPattern("HH:mm")` | 提到顶，`ThreadLocal` 或单例 |
| 用 `color.copy(alpha = ...)` → GPU 合成 | 用预计算 opaque / 半透明 |

### 5.4 Modifier 顺序

Compose 中顺序敏感；Kuikly 没这问题，但**绘制顺序敏感**。把 `attr {}` 块内一次性写完属性，不要在子 View 改父属性。

### 5.5 流式渲染防抖

参考 `feature/chat/components/CachedMarkdown.kt`：
- 每 60 字符或者 100ms 才触发 Markdown 解析
- 解析结果缓存在 `mutableMapOf<String, ParsedMarkdown>`
- LRU 上限 50
- 撤销 / 重生成时主动清缓存对应 key

```kotlin
private val markdownCache = mutableMapOf<String, MarkdownView>()
private var lastParseLen = 0
private var lastParseTs = 0L

private fun pushStreaming(text: String) {
    streamingText = text
    val now = System.currentTimeMillis()
    val lenDelta = text.length - lastParseLen
    if (lenDelta >= 60 || now - lastParseTs >= 100) {
        val cached = markdownCache.getOrPut(text) { parseMarkdown(text) }
        streamingMarkdownView = cached
        lastParseLen = text.length
        lastParseTs = now
    }
}

private fun renderStreamEnd() {
    val final = streamingText
    streamingText = ""                                       // 在同一帧 finalize
    val cached = markdownCache.getOrPut(final) { parseMarkdown(final) }
    streamingMarkdownView = cached                            // 不要 remove + add
}
```

### 5.6 In-place update（completion render）

**核心**：流式结束（`final` SSE 帧）时**不**重建消息列表项。把当前 streaming 的 View 节点就地更新为 final 文本（ID、state 都不变），列表滚动锚点不丢失。

```kotlin
fun onFinalMessage(finalMsg: UiMessage) {
    // 1. 更新 messages list（保留末尾 streaming 节点的位置）
    val updated = messages.map {
        if (it.localId == streamingLocalId) finalMsg else it
    }
    messages = updated
    // 2. 清 stream 字段 — 必须在同一 observable mutation 内
    streamingText = ""
    streamingMarkdownView = null
    isStreaming = false
}
```

### 5.7 滑动操作（DrawerItem 左滑）

Kuikly 用 `gesture { drag { ... } }`（Kuikly 1.0.7+）。给 `ConversationItem` 设初始 `marginLeft = 0f`，拖动时累加，达到 -80f 时露出「归档」按钮。

```kotlin
@Page("swipe_demo")
internal class SwipeRow : BasePager() {
    private var offset by observable(0f)
    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    width(280f); height(64f)
                    flexDirectionRow()
                    marginLeft(ctx.offset)
                    backgroundColor(Color.WHITE)
                }
                gesture {
                    drag(direction = DragDirection.Horizontal) { delta ->
                        ctx.offset = (ctx.offset + delta).coerceIn(-120f, 0f)
                    }
                    endDrag {
                        if (ctx.offset < -60f) {
                            ctx.onRevealArchive()
                            ctx.offset = -80f
                        } else {
                            ctx.offset = 0f
                        }
                    }
                }
                Text { attr { text("会话标题"); flex(1f); margin(16f) } }
            }
            // 归档按钮（绝对定位在右侧）
            if (offset < -40f) View {
                attr {
                    positionAbsolute()
                    right(0f); top(0f); bottom(0f)
                    width(80f)
                    backgroundColor(Color.RED)
                    alignItemsCenter(); justifyContentCenter()
                }
                Text { attr { text("归档"); color(Color.WHITE) } }
                event { click { /* 归档 */ } }
            }
        }
    }
}
```

---

## 6 · 无障碍（Accessibility）

Librechat 强调：
- 48dp 触控目标
- 语义 heading
- Live region（流式状态）
- contentDescription（图标按钮）

| 规则 | Kuikly 实现 |
|---|---|
| 触控 ≥ 48dp | 点击元素 `size(48f, 48f)` 或容器 `padding(12f)` 包 24dp 图标 |
| 语义 heading | 给 `Text` 设 `accessibilityRole = Heading`（Kuikly ARIA 支持） |
| 描述 | `attr { accessibilityLabel("新建对话") }` |
| Live region | 流式 cursor 设 `accessibilityLiveRegion = POLITE` |
| 焦点 | `attr { focusable(true) }` |

---

## 7 · 路由 / 导航 Mapping

### 7.1 路由清单（初版）

| 路由名 | 文件 | 触发场景 |
|---|---|---|
| `dsh_hub` | `DshHubPager` | 默认首页 |
| `chat` | `ChatPager` | 对话主页面 |
| `session_list` | `SessionListPager` | Drawer 历史（可与 Drawer 合并） |
| `workspace_list` | `WorkspaceListPager` | Workspace 列表 |
| `settings_main` | 新增 `SettingsPager` | 设置 |
| `settings_theme` | 新增 | 主题设置 |
| `plugin_list` | 已有（Subagent-5） | 插件 |
| `diagnostics` | `DiagnosticsPager` | 诊断 / 日志 |
| `media_viewer` | 新增 | 全屏图片浏览 |
| `voice_sheet` | 嵌入式，非独立路由 | 语音 |
| `model_selector_sheet` | 嵌入式 BottomSheet | 选模型 |

### 7.2 路由传递

```kotlin
// 发起跳转
val json = JSONObject().apply {
    put("conversationId", "abc-123")
    put("reset", false)
}
acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("chat", json)

// 接收侧（Pager 内）
private fun conversationId(): String =
    pageData.params.optString("conversationId").ifEmpty { "default" }
```

### 7.3 返回栈

Kuikly 自带 RouterModule 维护栈；Pager 用 `event { back { ... } }` 拦截返回键。

### 7.4 跳转动画

Kuikly `RouterModule` 提供 `openPage` 时附 transition 参数；默认是平台 push。如要仿 Librechat 的「左侧滑入」自定义过渡，需在 RouterModule 包装层完成。

---

## 8 · 数据契约（与 `DshBridgeModule` 对齐）

> 当前只列出新增/补全；`Subagent-*` 已规划的不再赘述。

| 方法 | 入参 | 出参 | 用途 |
|---|---|---|---|
| `dsh.listSessions` | `{ tags?: List<String>, archived?: Boolean, search?: String }` | `{ ok, items: [DrawerConversation], nextCursor? }` | Drawer 历史 |
| `dsh.loadConversation` | `{ id }` | `{ ok, messages: [UiMessage], branchIndex }` | 加载历史 |
| `dsh.archiveSession` | `{ id }` | `{ ok }` | 滑入归档 |
| `dsh.renameSession` | `{ id, title }` | `{ ok }` | 重命名 |
| `dsh.deleteSession` | `{ id }` | `{ ok }` | 永久删除（需扩展） |
| `dsh.sendPrompt` | 已实现 | 已实现 | 发送消息 |
| `dsh.attachImage` | `{ id, mime, base64 }` | `{ ok, attachmentId, thumbnailUrl }` | 图片附件 |
| `dsh.listModels` | — | `{ ok, groups: [EndpointGroup] }` | 模型选择 |
| `dsh.setTheme` | `{ accent, nightMode }` | `{ ok }` | 主题变更 |
| `dsh.getTheme` | — | `{ accent, nightMode }` | 读主题 |
| `dsh.toggleArtifactType` | `{ type, enabled }` | `{ ok }` | 渲染开关 |
| `dsh.forkBranch` | `{ messageId }` | `{ ok, newId }` | 消息分叉 |
| `dsh.regenerate` | `{ messageId }` | `{ ok }` | 重生成 |
| `dsh.abortChat` | `{ streamId }` | `{ ok, aborted }` | 停止 |
| `dsh.tts` | `{ messageId }` | `{ ok, audioUrl }` | 朗读 |
| `dsh.startDictation` | `{ lang }` | 流式回调 | 语音听写 |

---

## 9 · 实现路线图（Roadmap）

> 排序准则：**先把骨架立起来，再补功能**，跟 Librechat 的提交节奏一致。

### Phase 1 · 骨架与基础（1 周）
- [ ] `BasePager` 主题 getter（§4.1）
- [ ] `AppShell` 抽出 + Drawer 抽出（§3.1, §3.2）
- [ ] 路由清单（§7.1）注册到 RouterModule
- [ ] `Color` token 浅/深双套 + `MaterialKolor` 等价（在 `androidMain` 算一次再喂给 token）
- [ ] `TextStyles` 字体阶梯

### Phase 2 · 列表与 Drawer（1 周）
- [ ] Drawer 接入 `dsh.listSessions`
- [ ] Drawer 时间分组 + 搜索框 + tag filter
- [ ] 滑动归档（§5.7）
- [ ] Settings Pager 雏形（4 个 tab）

### Phase 3 · ChatScreen（2 周）
- [ ] MessageList 框架 + MessageBubble 基础
- [ ] 流式 cursor 内嵌 + Markdown 缓存
- [ ] ChatInput autoGrow + 发送
- [ ] ModelSelectorSheet
- [ ] In-place finalize（§5.6）
- [ ] ChatFloatingTopBar
- [ ] Branch / sibling navigation
- [ ] ToolCallCard / CodeBlock

### Phase 4 · 内容渲染（2 周）
- [ ] Markdown + 代码高亮（复用 `multiplatform-markdown-renderer`）
- [ ] LaTeX（用 webView 占位 → 替换为 AndroidMath 等价）
- [ ] 图片附件 + Media Viewer
- [ ] 文件附件 chip + 文件预览
- [ ] Artifact 渲染（Mermaid / SVG / HTML）

### Phase 5 · 跨平台打磨（1 周）
- [ ] iOS / OHOS 适配（字体、安全区、状态栏）
- [ ] 平板双栏（`isTablet` 走 AppShell 平板分支）
- [ ] Theme 切换 + 跟随系统
- [ ] Live region / accessibility 落地
- [ ] 动效（drawer 滑入 / sheet 上推 / fab 缩放）

### Phase 6 · Polish（持续）
- [ ] 性能审计：每帧分配 / 列表复用
- [ ] i18n 文案抽离
- [ ] 主题色板预览（HSV picker）

---

## 10 · 风险与备选

| 风险 | 备选 |
|---|---|
| Kuikly `Modifier` 概念缺失导致 Compose 风格代码无法 1:1 翻译 | 用本框架文档 §2 的翻译表约束，统一改写 |
| `MaterialKolor` 仅在 Android 端实现 | iOS / OHOS 端调 Android 端 `actual` → 桥回 13 色 |
| `RouterModule` 没有 predictive back | Android Activity 提供原生支持；iOS / OHOS 走 `event { back }` 拦截栈 |
| 列表 > 1000 条性能 | 拆窗口 + 增量加载（仿 Librechat 的 cursor 分页） |
| Kuikly 富文本/Markdown 没有官方库 | 退路：WebView 包 Vue/React 渲染组件，仿 Librechat 的 artifact 渲染 |
| OpenHarmony 上 `setTimeout` 抖动 | 用 `setTimeout(0)` + `LaunchedEffect` 双路径；性能基准优选 |
| Kuikly `gesture { drag }` 跨平台一致性问题 | 短期用 `Slidable` 类型 row（Kuikly 1.0.7+） |
| 主题切换全量重绘导致掉帧 | token getter 缓存 200ms，期间不重绘 |

---

## 11 · 附录 · 关键文件路径速查

```
TalkToAI/
└── shared/src/commonMain/kotlin/com/example/talktoai/
    ├── base/
    │   ├── BasePager.kt                ← 主题 getter 注册点、生命周期
    │   ├── BridgeModule.kt             ← 跨端 RPC 桥
    │   ├── Utils.kt                    ← setTimeout 工具
    │   ├── IPagerIdKtx.kt              ← pagerId { ... } 扩展
    │   └── dsh/
    │       ├── DshHubPager.kt          ← 豆包首页（侧栏/InputBar 当前实现）
    │       ├── ChatPager.kt            ← 对话主页面（当前 208 行，需重构）
    │       ├── SessionListPager.kt     ← 会话列表
    │       ├── WorkspaceListPager.kt
    │       └── DiagnosticsPager.kt     ← 日志/诊断
│   └── (新增)
    ├── AppShell.kt                     ← §3.1（手机/平板根布局）
    ├── DrawerContent.kt                ← §3.2（侧栏）
    ├── ModelSelectorSheet.kt           ← §3.4
    ├── SettingsPager.kt                ← §3.6
    ├── theme/
    │   ├── ColorTokens.kt              ← §4.1 token getter
    │   ├── TextStyles.kt               ← §4.3
    │   └── ShapeTokens.kt              ← §4.4
    └── components/
        ├── MessageBubble.kt            ← §3.3
        ├── ChatInput.kt
        ├── ChatFloatingTopBar.kt
        ├── MessageList.kt
        ├── MarkdownView.kt + CachedMarkdown.kt
        ├── ToolCallCard.kt
        ├── CodeBlock.kt
        └── TabsRow.kt
```

---

## 12 · 版本记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v0.1 | 2026-09-01 | 初版框架文档，从 Librechat-Mobile 全量对照翻译到 Kuikly；§1–§11 完整覆盖 |
| v0.2 | 2026-09-01 | Phase 1 骨架落地：新增 `theme/Palette.kt`、`theme/ThemeColors.kt`、`theme/TextStyles.kt`（含 `ShapeTokens`）；`BasePager.themeDidChanged()` 推送 `ThemeColors` 实现亮/暗双套 token 自动切换；抽出 `components/DrawerContent.kt`（含 `DrawerOverlay`）让 `DshHubPager` 与未来 `AppShell` 共用；新增 `dsh/SettingsPager.kt`（§3.6 4-tab 骨架）+ `components/SettingsComponents.kt`（`TabsRow` / `SettingsRow` / `SettingsCard`）；路由表新增 `settings_main`；**勘误**：移除 `keyTag = ...` 三处引用（Kuikly 2.7 无此公开 API，mapping §5.2 / §1.3 同步修正）；用 `overflow(clipChild: Boolean)` 替代误写的 `overflowHidden()` |
| v0.2.1 | 2026-09-01 | 编译错误修复：`overflowHidden` → `overflow(true)`；删除 `keyTag` 残留（Kuikly 2.7 不暴露该 setter） |
| v0.3 | 2026-09-02 | `DshHubPager` 按参考截图重做：①顶栏标题改 `问候` 并新增 `AI 生成可能有误 注意核实` 副标题；②新增蓝色 `hi` starter 胶囊（截图 UI 起点标记）；③助手气泡下方新增 action row（📋 复制 / 🔊 朗读 / 👍 / 👎 / 🔄 重生成，点 🔄 走 `simulateReply(force=true)` 走 in-place 重生成）；④quick chip 行改为「快速 / AI 创作 / 拍题答疑 / 照片… / 翻译 / 写作 / 代码 / 学习 / 创意 / 更多」，`快速` 默认选中（accent 填充 + 0px border，其余 1px divider + surface），横向 `Scroller` 露出更多；⑤输入框右侧统一为单一 `+`（弹出动作面板入口）。**编译验证**：`:shared:compileDebugKotlinAndroid` + `compileKotlinIosArm64` + `compileKotlinJs` 全部 `BUILD SUCCESSFUL`，未引入 unresolved 引用；未改 `BasePager` 主题钩子或 `ThemeColors` 公共接口。**遗留**：AGP 7.4.2 ↔ R8 4.0.52 ↔ Kotlin 2.1.21 工具链不兼容导致 `:androidApp:assembleDebug` 在 D8 阶段失败，与本次 Pager 重构无关；属于 buildSrc 升级目标。 |

> **下次更新触发**：当 `AppShell`（§3.1）抽出、`MaterialKolor` 接入自动调色板、`ChatScreen`（§3.3）大规模重构、或 Kuikly 跨端行为（如 RouterModule / gesture API）有版本变化时，本框架文档必须同步更新。
