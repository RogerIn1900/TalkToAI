# TalkToAI 架构重构规范 v1.0

> 参考：**WorkBuddyGuide 第三篇 · 第 24 章「如何进行多 Agent 系统设计」**
> 参考：AlephAITech/WorkBuddyGuide (https://github.com/AlephAITech/WorkBuddyGuide)
> 日期：2026-09-02
> 状态：**设计稿 · 待 Jerry 确认后执行**

---

## §0 背景与目标

### 现状问题

TalkToAI 当前代码结构存在以下问题：

| 问题 | 描述 |
|---|---|
| **Feature 扁平堆叠** | `shared/base/dsh/` 下所有 Pager（ChatPager / SessionListPager / DiagnosticsPager / WorkspaceListPager / SettingsPager）全部扁平堆叠，无 feature-owned 边界 |
| **dsh/ 根目录扁平** | `dsh/transport/`、`dsh/observability/`、`dsh/media/` 等没有进一步按 feature 组织 |
| **硬编码颜色分散** | `0xFFF5F7FA`、`0xFF3B82F6` 等颜色散落在各 Pager 中，Theme 系统不完整 |
| **6 个新 feature 缺文件** | `ExportPager`、`MessageBubble`、`PluginListPager`、`LogCenterPager`、`DshStreamLog` 在 MEMORY.md 里引用但未创建 |
| **BridgeModule 膨胀风险** | 所有 feature 都在 BridgeModule 上追加 method，无 feature 契约层 |

### 目标

1. 建立 **feature-owned 模块边界**（参考 WorkBuddy Multi-Agent 角色契约）
2. 引入 **角色契约模式**：每个 feature 的输入/输出/副作用明确，不越界调用
3. 为 6 个 task 预留清晰的文件路径，避免冲突
4. 保留 `dsh/` 作为**核心域**，各 feature Pager 通过 `DshClient` 接口访问 `dsh/`，不直接 import dsh 内部类

---

## §1 参考架构：WorkBuddy Multi-Agent 设计原则

WorkBuddyGuide 第三篇第 24 章「如何进行多 Agent 系统设计」提出的核心原则，直接映射到代码架构：

### 1.1 角色契约（输入 / 输出 / 禁止动作）

每个 feature 模块等价于一个"角色"，有三要素：

```
输入（Inputs）：这个角色接收什么数据/事件
输出（Outputs）：这个角色产出什么数据/调用什么副作用
禁止（Forbidden）：这个角色不得做什么
```

| 角色（Feature） | 输入 | 输出 | 禁止 |
|---|---|---|---|
| **Theme（主题）** | `AppTheme{LIGHT\|DARK\|SYSTEM}` | `ThemeColors` + Intent extra | 不得直接调用 RPC |
| **Message（消息）** | `ContentBlock[]` | `bridge.copyToPasteboard()` | 不得访问 Session List |
| **Attachment（附件）** | `ImageAttachmentPayload` | `dsh.promptWithImage()` | 不得修改 Session 元数据 |
| **Session（会话）** | `SessionId` | `sessionControl(Rename\|Archive)` | 不得渲染消息气泡 |
| **Plugin（插件）** | `PluginInventory` | `bridge.listPlugins()` | 不得发起 RPC 调用 |
| **Log（日志）** | `TraceContext[]` | `LogBundle` zip export | 不得记录正文内容 |

### 1.2 串行 vs 并行

- **可并行**：Theme 初始化 ↔ PluginInventory 加载 ↔ Log 缓冲建立（第一批 3 个 task 可并行）
- **必须串行**：`SessionListPager` 重命名必须等待 `sessionControl(Rename)` 返回后才更新本地状态

### 1.3 共享产物层

所有 feature 通过 `DshClient` 单一入口访问 `dsh/` 核心域，不直接 import `dsh/transport/`、`dsh/observability/` 等内部类。

### 1.4 降级交付

每个 feature 的错误处理策略：

| Feature | 降级行为 |
|---|---|
| Theme | 降级为 `SYSTEM` 跟随系统 |
| Attachment | 显示 overLimit 提示，消息保留在输入框 |
| Plugin | failed 状态展开显示 failureReason，不闪退 |
| Log | 环形缓冲满时丢弃最旧，不阻塞主线程 |

---

## §2 目标目录结构

### 2.1 整体结构

```
TalkToAI/
├── androidApp/                          # Android 平台适配（不变）
├── iosApp/                              # iOS 平台适配（不变）
├── ohosApp/                             # HarmonyOS 平台适配（不变）
├── shared/src/commonMain/kotlin/com/example/talktoai/
│   ├── base/                            # 基础设施（不变）
│   │   ├── BasePager.kt
│   │   ├── BridgeModule.kt
│   │   ├── Utils.kt
│   │   ├── IPagerIdKtx.kt
│   │   ├── components/
│   │   │   ├── DrawerContent.kt
│   │   │   └── SettingsComponents.kt
│   │   └── theme/                       # Theme 系统（不变，已存在）
│   │       ├── Palette.kt
│   │       ├── TextStyles.kt
│   │       └── ThemeColors.kt
│   ├── feature/                         # 【新】feature-owned 模块
│   │   ├── theme/                      # Feature-1：夜间模式
│   │   │   ├── ThemeFeature.kt         # Theme 管理器（持久化 + 观察者）
│   │   │   └── ThemeContract.kt        # 输入/输出/禁止
│   │   ├── message/                    # Feature-2：文本选择/复制/导出
│   │   │   ├── MessageBubble.kt        # 可选择消息气泡组件
│   │   │   └── ExportPager.kt          # 导出 Pager
│   │   ├── attachment/                  # Feature-3：图片/文件上传
│   │   │   └── AttachmentInputBar.kt   # InputBar + 缩略图列表
│   │   ├── session/                    # Feature-4：会话管理
│   │   │   └── SessionListFeature.kt   # Rename/Archive 状态机
│   │   ├── plugin/                      # Feature-5：插件菜单
│   │   │   └── PluginListPager.kt      # 插件列表 Pager
│   │   └── log/                        # Feature-6：日志中心
│   │       └── LogCenterPager.kt       # 日志查看 Pager
│   └── dsh/                            # DSH Pager 层（已在 shared/base/dsh/）
│       ├── ChatPager.kt                 # 移动到 feature/message/
│       ├── DshHubPager.kt               # 主 Hub（不变）
│       ├── SessionListPager.kt          # 移动到 feature/session/
│       ├── WorkspaceListPager.kt         # Workspace 操作（不变）
│       ├── DiagnosticsPager.kt          # 移动到 feature/log/
│       ├── SettingsPager.kt             # 移动到 feature/theme/
│       └── RouterPage.kt                # 不变
├── dsh/src/commonMain/kotlin/com/example/talktoai/dsh/
│   ├── client/                          # 不变
│   │   ├── DshClient.kt
│   │   ├── DshAppContext.kt
│   │   ├── InteractionHandler.kt
│   │   └── InMemoryFakeRemote.kt
│   ├── transport/                      # 不变
│   │   ├── DshRemote.kt
│   │   └── FollowStream.kt
│   ├── connection/                      # 不变
│   │   ├── Connection.kt
│   │   └── LocalProcessConnectionManager.kt
│   ├── contract/                        # 不变
│   │   ├── SessionModels.kt
│   │   ├── Attachments.kt
│   │   ├── Prompt.kt
│   │   └── Errors.kt
│   ├── observability/                   # 不变（feature/log/ 会依赖这个）
│   │   ├── SanitizedLogger.kt
│   │   ├── TraceContext.kt
│   │   └── DshStreamLog.kt             # 【新】环形缓冲（从 feature/log/ 移动来）
│   ├── media/                           # 不变
│   │   └── ImageBudget.kt
│   ├── prompt/                         # 不变
│   │   ├── Prompt.kt
│   │   └── ReconciliationService.kt
│   └── session/                         # 不变
│       └── SessionWindow.kt
└── docs/framework/
    ├── README.md                        # 框架索引（不变）
    └── dsh-architecture.md              # 【新】本规范存档
```

### 2.2 Gradle 模块（不变）

```
settings.gradle.kts:
  include(":androidApp")
  include(":shared")
  include(":dsh")
```

> **架构决策**：不新增 Gradle subproject，保持 `shared` 和 `dsh` 两个模块。
> feature 模块以 Kotlin 包（`shared/feature/<name>/`）方式组织，
> 避免 Gradle 模块粒度过细带来的构建复杂度。

---

## §3 Feature 模块详细规范

### 3.1 Feature-1 · Theme（夜间模式）

**入口文件**：`shared/feature/theme/ThemeFeature.kt`

```
输入（Inputs）：
  - AppTheme { LIGHT, DARK, SYSTEM }
  - onConfigurationChanged 回调（系统主题变更）

输出（Outputs）：
  - ThemeColors（后台线程更新，主线程通知 Pager 重绘）
  - Intent extra（透传给 KuiklyRenderActivity）

禁止（Forbidden）：
  - 不得直接调用 bridge RPC
  - 不得访问 Session 数据
```

**实现要点**：
- `ThemeFeature` 实现为 `object` 单例（Kotlin），持有 `SharedPreferences` 持久化
- 所有 Pager 通过 `ThemeColors.current()` 读取，不持有 `ThemeFeature` 实例
- `DshBridgeModule` 新增 `dsh.getTheme / dsh.setTheme` RPC（第 1 个接入 BridgeModule）

**文件变更**：

| 操作 | 文件 |
|---|---|
| 新建 | `shared/feature/theme/ThemeFeature.kt` |
| 新建 | `shared/feature/theme/ThemeContract.kt` |
| 修改 | `shared/base/BasePager.kt`（注入 ThemeFeature 观察者） |
| 修改 | `androidApp/.../KuiklyRenderActivity.kt`（监听配置变更透传） |
| 修改 | `shared/base/BridgeModule.kt`（新增 theme RPC） |

---

### 3.2 Feature-2 · Message（文本选择/复制/导出）

**入口文件**：`shared/feature/message/MessageBubble.kt`、`ExportPager.kt`

```
输入（Inputs）：
  - ContentBlock[]（来自 SessionWindow）

输出（Outputs）：
  - bridge.copyToPasteboard(text)
  - bridge.share(markdown)
  - ExportPager → 导出 zip / 复制 Markdown

禁止（Forbidden）：
  - 不得调用 sessionControl（Rename/Archive）
  - 不得访问 PluginInventory
```

**实现要点**：
- `MessageBubble` 用 Kuikly `SelectionContainer` 包裹文本节点
- `ExportPager` 遍历 `SessionWindow`，拼接 Markdown，调用 `bridge.share()`
- 代码块加 📋 复制按钮（`copyToPasteboard`）

**文件变更**：

| 操作 | 文件 |
|---|---|
| 新建 | `shared/feature/message/MessageBubble.kt` |
| 新建 | `shared/feature/message/ExportPager.kt` |
| 修改 | `shared/base/dsh/ChatPager.kt`（使用 MessageBubble 替代内联气泡） |
| 修改 | `shared/base/BridgeModule.kt`（新增 export 相关 RPC） |

---

### 3.3 Feature-3 · Attachment（图片/文件上传）

**入口文件**：`shared/feature/attachment/AttachmentInputBar.kt`

```
输入（Inputs）：
  - Native Intent 结果（相册/相机返回的 URI）
  - ImageAttachmentPayload（base64 编码）

输出（Outputs）：
  - dsh.promptWithImage(sessionId, text, attachments)
  - ImageBudget 预算守卫

禁止（Forbidden）：
  - 不得修改 Session 元数据（rename/archive）
  - 超出预算时不得发送
```

**实现要点**：
- `ChatPager` InputBar 重构为 `AttachmentInputBar`
- 五态 UI：`idle → selecting → pending → uploading → done / failed / overLimit`
- 缩略图列表（可移除）
- `DshRemote.promptWithImage()` 新增（Task 3 完成后）

**文件变更**：

| 操作 | 文件 |
|---|---|
| 新建 | `shared/feature/attachment/AttachmentInputBar.kt` |
| 修改 | `shared/base/dsh/ChatPager.kt`（替换 InputBar） |
| 修改 | `dsh/transport/DshRemote.kt`（新增 promptWithImage） |
| 修改 | `dsh/client/DshClient.kt`（新增 sendPromptWithImage） |
| 修改 | `androidApp/.../DshClientHolder.kt`（Native Intent 实现） |
| 修改 | `dsh/media/ImageBudget.kt`（MIME 预检） |

---

### 3.4 Feature-4 · Session（会话管理）

**入口文件**：`shared/feature/session/SessionListFeature.kt`、`shared/feature/session/SessionListPager.kt`

```
输入（Inputs）：
  - DshRemote.sessionList(workspaceId)
  - DshRemote.sessionControl(sessionId, Rename(title))

输出（Outputs）：
  - 主列表（左滑归档）/ 归档列表（Tab 切换）
  - 长按菜单（重命名 / 归档）

禁止（Forbidden）：
  - 不得渲染消息内容（MessageBubble）
  - 不得发起 Attachment RPC
```

**实现要点**：
- `SessionListFeature` 管理 Rename/Archive 的状态机（`idle → pending → success / failed`）
- `sessionControl(Command.Rename(title))` 和 `sessionControl(Command.Archive)` 走现有 `DshRemote.sessionControl()`
- 归档前二次确认弹窗

**文件变更**：

| 操作 | 文件 |
|---|---|
| 新建 | `shared/feature/session/SessionListFeature.kt` |
| 新建 | `shared/feature/session/SessionListPager.kt` |
| 修改 | `shared/base/BridgeModule.kt`（新增 dsh.renameSession / dsh.archiveSession） |
| 修改 | `dsh/transport/DshRemote.kt`（CommandKind 扩展 Rename/Archive） |

---

### 3.5 Feature-5 · Plugin（插件菜单）

**入口文件**：`shared/feature/plugin/PluginListPager.kt`

```
输入（Inputs）：
  - DshRemote.pluginInventory()（已存在于 DshRemote）

输出（Outputs）：
  - bridge.listPlugins() → PluginListPager 显示
  - 状态 Badge（pending / loading / active / failed / unloading）

禁止（Forbidden）：
  - 不得发起 Prompt RPC
  - 不得显示 ToolCard 执行结果
```

**实现要点**：
- `PluginListPager` 只读展示 `PluginEntry` 列表
- 顶栏搜索 + 状态 Tab 筛选
- failed 状态展开显示 failureReason

**文件变更**：

| 操作 | 文件 |
|---|---|
| 新建 | `shared/feature/plugin/PluginListPager.kt` |
| 修改 | `androidApp/.../DshClientHolder.kt`（实现 listPlugins） |
| 修改 | `shared/base/BridgeModule.kt`（新增 dsh.listPlugins） |

---

### 3.6 Feature-6 · Log（日志中心）

**入口文件**：`dsh/observability/DshStreamLog.kt`、`shared/feature/log/LogCenterPager.kt`

```
输入（Inputs）：
  - DshClientHolder.trace/info/warn/error 回调
  - TraceContext（sessionId + 时间索引）

输出（Outputs）：
  - LogCenterPager（筛选 + 详情 + 导出 zip）
  - 环形缓冲 500 条

禁止（Forbidden）：
  - 不得记录正文内容（bridge RPC 参数）
  - 不得记录 Base64 载荷
  - 不得记录 Authorization / API Key
```

**实现要点**：
- `DshStreamLog` 在 `dsh/observability/`（核心域），`LogCenterPager` 在 `shared/feature/log/`（UI 层）
- 脱敏规则见 `SanitizedLogger.kt`
- 导出 zip 包含：脱敏日志 + App 版本 + 设备信息

**文件变更**：

| 操作 | 文件 |
|---|---|
| 新建 | `dsh/observability/DshStreamLog.kt` |
| 新建 | `shared/feature/log/LogCenterPager.kt` |
| 修改 | `androidApp/.../DshClientHolder.kt`（trace 回调接入） |
| 修改 | `shared/base/BridgeModule.kt`（新增 dsh.listLogs / dsh.clearLogs / dsh.exportLogBundle） |
| 修改 | `shared/base/dsh/DiagnosticsPager.kt`（重构接入 DshStreamLog） |

---

## §4 迁移计划（文件操作顺序）

### 第一批（可并行，各 feature 独立）

| 顺序 | Feature | 新建文件 | 修改文件 |
|---|---|---|---|
| A | Feature-6 先做（被其他所有 feature 依赖 observability） | `DshStreamLog.kt` | `DshClientHolder.kt` |
| B | Feature-1 Theme | `ThemeFeature.kt`, `ThemeContract.kt` | `BasePager.kt`, `KuiklyRenderActivity.kt`, `BridgeModule.kt` |
| C | Feature-5 Plugin | `PluginListPager.kt` | `DshClientHolder.kt`, `BridgeModule.kt`, `DshHubPager.kt` |

### 第二批（依赖第一批 RPC 接口）

| 顺序 | Feature | 新建文件 | 修改文件 |
|---|---|---|---|
| D | Feature-2 Message | `MessageBubble.kt`, `ExportPager.kt` | `ChatPager.kt`, `BridgeModule.kt` |
| E | Feature-3 Attachment | `AttachmentInputBar.kt` | `ChatPager.kt`, `DshRemote.kt`, `DshClient.kt`, `DshClientHolder.kt`, `ImageBudget.kt` |
| F | Feature-4 Session | `SessionListFeature.kt`, `SessionListPager.kt` | `BridgeModule.kt`, `DshRemote.kt` |

### 目录重组（与第一批同时进行）

| 操作 | 说明 |
|---|---|
| 创建 `shared/feature/theme/`, `shared/feature/message/`, `shared/feature/attachment/`, `shared/feature/session/`, `shared/feature/plugin/`, `shared/feature/log/` 目录 | 确保所有新文件落在 feature-owned 目录 |
| `shared/base/dsh/ChatPager.kt` 移动到 `shared/feature/message/` | 建立 feature-owned 边界，feature/message/ 拥有自己的 Pager |
| `shared/base/dsh/SessionListPager.kt` 移动到 `shared/feature/session/` | 同上 |
| `shared/base/dsh/DiagnosticsPager.kt` 移动到 `shared/feature/log/` | 同上 |
| `shared/base/dsh/SettingsPager.kt` 移动到 `shared/feature/theme/` | 同上 |

---

## §5 文件冲突预判（与 MEMORY.md §4 对齐）

| 文件 | 被哪些 feature 修改 | 串行要求 |
|---|---|---|
| `shared/base/BridgeModule.kt` | Feature-1, 2, 3, 4, 5, 6 | **Feature-1 先做基础 RPC**，后续只追加 |
| `androidApp/.../DshClientHolder.kt` | Feature-3, 5, 6 | **Feature-6 先做 trace 回调接入**，后续只追加 |
| `dsh/transport/DshRemote.kt` | Feature-3, 4 | **Feature-5（pluginInventory 已有）**，后续追加 promptWithImage 和 sessionControl 扩展 |
| `shared/base/dsh/ChatPager.kt` | Feature-2, 3 | **Feature-2 先做 MessageBubble 抽象**，Feature-3 只改 InputBar |
| `shared/base/dsh/DshHubPager.kt` | Feature-1, 5 | Feature-1 先加 Settings 入口，Feature-5 再追加 Plugins 入口卡片 |
| `shared/base/dsh/DiagnosticsPager.kt` | Feature-6 | Feature-6 独占重构 |

---

## §6 版本记录

| 版本 | 日期 | 作者 | 变更 |
|---|---|---|---|
| v1.0 | 2026-09-02 | Agent | 初始架构规范：feature-owned 模块 + WorkBuddy Multi-Agent 角色契约映射 + 6 个 feature 详细规范 + 迁移计划 |

---

## §7 附录：WorkBuddy Multi-Agent 设计原则引用

> 来源：[WorkBuddyGuide 第三篇 · 第 24 章](https://workbuddy.homes/bluebook/第三篇%20进阶篇：把案例变成自己的工作系统/第%2024%20章%20如何进行多%20Agent%20系统设计/)

核心原则摘要：

1. **角色契约**：每个角色有明确的「输入 / 输出 / 禁止动作」三要素
2. **共享产物层**：单一产物路径，下游只读取上游已确认产物
3. **串行 vs 并行**：可并行则并行（标明汇合点），必须串行则等前置完成
4. **主理人职责**：工作流控制器（分解任务、检查产物、决定等待/重试）
5. **降级交付**：失败时交付降级产物 + 说明缺失，不伪装完整成果
