package com.example.talktoai.base.dsh

import com.example.talktoai.base.BasePager
import com.example.talktoai.base.BridgeModule
import com.example.talktoai.base.components.DrawerContent
import com.example.talktoai.base.components.DrawerEntry
import com.example.talktoai.base.components.DrawerOverlay
import com.example.talktoai.base.setTimeout
import com.example.talktoai.base.theme.ShapeTokens
import com.example.talktoai.base.theme.TextStyles
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.*

/**
 * DSH 风格首页（TalkToAI/Kuikly 实现蓝图 §3.1 + 截图 v0.3 + mapping §3.1/§3.2）。
 *
 * 视觉结构（与参考截图一致）：
 *   ┌────────────────────────────────────────────┐ ← top safe area inset
 *   │  ☰        问候                📞 🔊        │  height 56
 *   │       AI 生成可能有误 注意核实              │  subtitle (12pt onSurfaceVariant)
 *   ├────────────────────────────────────────────┤
 *   │   ┌──┐                                       │
 *   │   │hi│  ← 蓝色 starter 胶囊                  │
 *   │   └──┘                                       │
 *   │                                              │
 *   │   🤖  Hi~ 😄 有什么我可以帮你的吗?            │  flex 1（可滚动）
 *   │       [📋] [🔊] [👍] [👎] [🔄]                │  action row
 *   │                                              │
 *   │                                            ↕│  bottom = chips + input + safeBottom
 *   ├────────────────────────────────────────────┤
 *   │ [⚡快速] [✨AI 创作] [📷拍题答疑] [🖼照片…]→  │  chips row（横滑，浮动贴近输入条上）
 *   ├────────────────────────────────────────────┤
 *   │ [📷]   发消息或按住说话        [+]          │  input bar（底栏浮动）
 *   ├────────────────────────────────────────────┤ ← bottom safe area inset
 *
 * 数据契约、路由、抽屉：
 * - 抽屉抽到 [DrawerContent]（§3.2）。
 * - 路由统一用 [openRoute] 封装的 [RouterModule.openPage]。
 * - 消息当前走客户端模拟；接 DSH SDK 后改为 `dsh.subscribe(...)` → 见 §8 数据契约。
 *
 * v0.3 (2026-09-02) 变更点：
 * - 顶栏标题改为 `问候`，并新增安全提示副标题；
 * - 新增 `hi` 蓝色 starter 胶囊（图片 UI 起点标记）；
 * - 助手气泡下方新增 action row（📋 复制 / 🔊 朗读 / 👍 / 👎 / 🔄 重生成）；
 * - chip 行改为「快速 / AI 创作 / 拍题答疑 / 照片… / 翻译 / 写作 / 代码 / 学习 / 创意 / 更多」，
 *   `快速` 为默认选中（accent 填充），其余描边；滑动露出更多（用 `Scroller`）；
 * - 输入框右侧统一为单一 `+`（弹出动作面板入口），与截图一致；
 * - 抽屉右侧图标顺序与文案（[DRAWER_ENTRIES]）按截图重新对齐（移除诊断入口到二级菜单外保留）。
 */
@Page("dsh_hub", supportInLocal = true)
internal class DshHubPager : BasePager() {

    // ── 状态 ────────────────────────────────────────────────────────────────

    private var sidebarOpen: Boolean by observable(false)
    private var selectedChip: String by observable("快速")

    /**
     * 当前会话消息。默认就有一条助手开场白，与截图一致。
     * 真实接入后由 DSH `subscribe` 流替换，这里只是 UI 占位。
     */
    private var chatMessages: List<ChatMessage> by observable(
        listOf(
            ChatMessage(
                id = 0,
                role = "assistant",
                content = "Hi~ 😄 有什么我可以帮你的吗?",
                time = "刚刚",
                avatar = "🤖"
            )
        )
    )
    private var nextMsgId: Int by observable(1)

    /** 输入文本（受控；用于"+ 面板"前的本地状态）。 */
    private var composer: String by observable("")

    private lateinit var inputRef: ViewRef<InputView>

    // ── 尺寸常量（dp） ──────────────────────────────────────────────────────
    private companion object {
        const val TOP_BAR_HEIGHT = 56f
        const val INPUT_BAR_HEIGHT = 56f
        const val CHIPS_BAR_HEIGHT = 44f
        const val FLOAT_PADDING = 12f
        const val ACTION_ROW_HEIGHT = 36f
        const val STARTER_BADGE_HEIGHT = 28f

        /** §3.2 Drawer 入口清单。 */
        val DRAWER_ENTRIES = listOf(
            DrawerEntry("💬", "Chat", "DSH 对话主界面（§3.3）", "chat"),
            DrawerEntry("📁", "Workspaces", "Workspace 列表（§3.5）", "workspace_list"),
            DrawerEntry("📋", "Sessions", "Session 列表（§3.5）", "session_list"),
            DrawerEntry("🐞", "Diagnostics", "反馈问题包导出", "diagnostics"),
            DrawerEntry("⚙️", "Settings", "主题 / 语言 / 账户 / 数据（§3.6）", "settings_main"),
            DrawerEntry("🏠", "Hub 首页", "返回首页", "dsh_hub"),
        )

        /** 快捷 chip 数据。顺序与截图自左向右一致，前若干项固定可见；其余靠横向滚动露出。 */
        val QUICK_CHIPS = listOf(
            QuickChip("⚡", "快速"),
            QuickChip("✨", "AI 创作"),
            QuickChip("📷", "拍题答疑"),
            QuickChip("🖼", "照片..."),
            QuickChip("🌐", "翻译"),
            QuickChip("📝", "写作助手"),
            QuickChip("💻", "代码助手"),
            QuickChip("📚", "学习辅导"),
            QuickChip("🎨", "创意灵感"),
            QuickChip("⋯", "更多"),
        )

        /** 助手气泡下的快捷动作（与截图一致：复制 / 朗读 / 赞 / 踩 / 重生成）。 */
        const val ACTION_COPY = "📋"
        const val ACTION_TTS = "🔊"
        const val ACTION_LIKE = "👍"
        const val ACTION_DISLIKE = "👎"
        const val ACTION_REGEN = "🔄"
    }

    // ── body ────────────────────────────────────────────────────────────────

    override fun body(): ViewBuilder {
        val ctx = this
        val safeTop = pageData.safeAreaInsets.top
        val safeBottom = pageData.safeAreaInsets.bottom

        // 由下到上的累加（最下层最先算）
        val inputBottom = FLOAT_PADDING + safeBottom
        val chipsBottom = inputBottom + INPUT_BAR_HEIGHT + FLOAT_PADDING / 2f
        val chatBottomPadding = chipsBottom + CHIPS_BAR_HEIGHT + FLOAT_PADDING

        return {
            // ── 整体根容器 ─────────────────────────────────────────────
            View {
                attr {
                    flex(1f)
                    backgroundColor(ThemeColors.background)
                    positionRelative()
                }

                // ── ① 顶部状态栏（§3.3 ChatFloatingTopBar 雏形） ─────────────
                View {
                    attr {
                        positionAbsolute()
                        top(0f); left(0f); right(0f)
                        height(TOP_BAR_HEIGHT + safeTop)
                        padding(top = safeTop, left = 16f, right = 16f, bottom = 0f)
                        backgroundColor(ThemeColors.surface)
                        flexDirectionRow()
                        alignItemsCenter()
                        justifyContentSpaceBetween()
                        borderBottom(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
                        zIndex(5, false)
                    }
                    // 左：汉堡
                    Text {
                        attr {
                            fontSize(22f)
                            color(ThemeColors.onSurface)
                            text("☰")
                        }
                        event { click { ctx.sidebarOpen = true } }
                    }
                    // 中：标题 + 副标题
                    View {
                        attr { flex(1f); alignItemsCenter() }
                        Text {
                            attr {
                                fontSize(TextStyles.TITLE_MEDIUM.size + 1f)
                                color(ThemeColors.onSurface)
                                text("问候")
                                fontWeightSemiBold()
                            }
                        }
                        Text {
                            attr {
                                fontSize(TextStyles.BODY_SMALL.size)
                                marginTop(2f)
                                color(ThemeColors.onSurfaceVariant)
                                text("AI 生成可能有误 注意核实")
                            }
                        }
                    }
                    // 右：📞 + 🔊（截图图标顺序）
                    View {
                        attr { flexDirectionRow(); alignItemsCenter() }
                        Text {
                            attr {
                                fontSize(20f)
                                marginRight(14f)
                                color(ThemeColors.onSurface)
                                text("📞")
                            }
                            event { click { ctx.openRoute("diagnostics") } }
                        }
                        Text {
                            attr {
                                fontSize(20f)
                                color(ThemeColors.onSurface)
                                text("🔊")
                            }
                            event { click { ctx.openRoute("diagnostics") } }
                        }
                    }
                }

                // ── ② 对话区域（§3.3 MessageList） ────────────────────────
                Scroller {
                    attr {
                        positionAbsolute()
                        top(TOP_BAR_HEIGHT + safeTop)
                        left(0f); right(0f)
                        bottom(0f)
                        padding(
                            left = 16f, right = 16f,
                            top = 12f, bottom = chatBottomPadding
                        )
                    }

                    // hi 蓝色 starter 胶囊（截图标记）
                    ctx.renderStarterBadge()

                    // 消息流：< 100 条直接 forEach（Kuikly 2.7 无公开 key API）
                    // 见 mapping §5.2 / §1.3 关键映射表。
                    ctx.chatMessages.forEach { msg ->
                        ctx.renderBubble(msg)
                    }

                    View { attr { height(FLOAT_PADDING) } }
                }

                // ── ③ 快捷 chips 行（贴近输入框上方；横滑） ───────────────
                View {
                    attr {
                        positionAbsolute()
                        left(0f); right(0f)
                        bottom(chipsBottom)
                        height(CHIPS_BAR_HEIGHT)
                        backgroundColor(ThemeColors.background)
                        padding(left = 12f, right = 12f)
                        flexDirectionRow()
                        alignItemsCenter()
                        zIndex(8, false)
                    }
                    Scroller {
                        attr { flex(1f); flexDirectionRow(); alignItemsCenter() }
                        ctx.renderQuickChips()
                    }
                }

                // ── ④ 底部输入条（截图样式：📷 | 输入框 | ＋） ───────────
                View {
                    attr {
                        positionAbsolute()
                        left(FLOAT_PADDING); right(FLOAT_PADDING)
                        bottom(inputBottom)
                        height(INPUT_BAR_HEIGHT)
                        backgroundColor(ThemeColors.surface)
                        borderRadius(28f)
                        padding(left = 14f, right = 12f)
                        flexDirectionRow()
                        alignItemsCenter()
                        boxShadow(BoxShadow(0f, 2f, 12f, ThemeColors.shadowMedium), false)
                        zIndex(9, false)
                    }
                    // 相机
                    Text {
                        attr {
                            fontSize(20f)
                            color(ThemeColors.onSurfaceVariant)
                            marginRight(10f)
                            text("📷")
                        }
                        event { click { ctx.handleSend("[用户发送了图片]") } }
                    }
                    // 输入框
                    View {
                        attr {
                            flex(1f)
                            backgroundColor(ThemeColors.background)
                            borderRadius(20f)
                            padding(left = 12f, right = 12f)
                            height(38f)
                            alignItemsCenter()
                        }
                        Input {
                            ref { ctx.inputRef = it }
                            attr {
                                fontSize(TextStyles.BODY_MEDIUM.size)
                                color(ThemeColors.onSurface)
                                placeholder("发消息或按住说话")
                                placeholderColor(ThemeColors.onSurfaceVariant)
                                flex(1f)
                            }
                        }
                    }
                    // 右侧 + 按钮（截图样式：单一 +，弹出动作面板）
                    Text {
                        attr {
                            fontSize(22f)
                            color(ThemeColors.onAccent)
                            marginLeft(10f)
                            size(36f, 36f)
                            text("＋")
                        }
                        event { click { ctx.handleSend() } }
                    }
                }

                // ── ⑤ 侧边栏 + 遮罩（§3.1 / §3.2） ────────────────────────
                if (ctx.sidebarOpen) {
                    DrawerOverlay { ctx.sidebarOpen = false }
                    DrawerContent {
                        attr {
                            brandTitle = "问候 AI"
                            brandSubtitle = "Design-Driven Smart Host"
                            entries = DRAWER_ENTRIES
                        }
                        event {
                            entryClick = { entry ->
                                ctx.sidebarOpen = false
                                if (entry.route != "dsh_hub") {
                                    ctx.openRoute(entry.route)
                                }
                            }
                            dismiss = { ctx.sidebarOpen = false }
                        }
                    }
                }
            }
        }
    }

    // ── 路由便捷封装（§7.2） ─────────────────────────────────────────────
    private fun openRoute(route: String) {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME)
            .openPage(route, JSONObject())
    }

    // ── starter 蓝色胶囊（截图标记） ────────────────────────────────────
    private fun renderStarterBadge() {
        View {
            attr {
                margin(top = 4f, bottom = 12f)
                height(STARTER_BADGE_HEIGHT)
                padding(left = 14f, right = 14f)
                backgroundColor(ThemeColors.accent)
                borderRadius(14f)
                alignItemsCenter()
                justifyContentCenter()
                alignSelfFlexStart()
            }
            Text {
                attr {
                    fontSize(TextStyles.BODY_MEDIUM.size)
                    color(ThemeColors.onAccent)
                    text("hi")
                    fontWeightSemiBold()
                }
            }
        }
    }

    // ── 快捷 chips 行（§3.3；横滑出更多） ─────────────────────────────
    private fun renderQuickChips() {
        val ctx = this
        QUICK_CHIPS.forEachIndexed { index, chip ->
            val isSelected = chip.label == ctx.selectedChip
            View {
                attr {
                    padding(left = 12f, right = 12f, top = 6f, bottom = 6f)
                    margin(left = 4f, right = 4f)
                    borderRadius(ShapeTokens.PILL)
                    border(
                        Border(
                            if (isSelected) 0f else 1f,
                            BorderStyle.SOLID,
                            ThemeColors.divider
                        )
                    )
                    backgroundColor(
                        if (isSelected) ThemeColors.accent else ThemeColors.surface
                    )
                    alignItemsCenter()
                    justifyContentCenter()
                }
                event {
                    click {
                        when (chip.label) {
                            "更多" -> ctx.openMoreChips()
                            else   -> ctx.selectedChip = chip.label
                        }
                    }
                }
                Text {
                    attr {
                        fontSize(TextStyles.BODY_SMALL.size + 1f)
                        color(
                            if (isSelected) ThemeColors.onAccent else ThemeColors.onSurface
                        )
                        text("${chip.emoji} ${chip.label}")
                    }
                }
            }
        }
    }

    // ── 消息气泡（§3.3 ChatScreen 的雏形；后续 MessageBubble.kt 接管） ───
    private fun renderBubble(msg: ChatMessage) {
        val ctx = this
        View {
            attr {
                flexDirectionRow()
                margin(top = 6f, bottom = 10f)
                alignItemsFlexStart()
            }
            // 头像
            View {
                attr {
                    size(36f, 36f)
                    borderRadius(18f)
                    backgroundColor(ThemeColors.surfaceVariant)
                    marginRight(10f)
                    alignItemsCenter()
                    justifyContentCenter()
                }
                Text {
                    attr {
                        fontSize(20f)
                        text(if (msg.role == "user") "👤" else msg.avatar)
                    }
                }
            }

            // 气泡 + 下方 action row
            View {
                attr { flex(1f) }

                // 气泡
                View {
                    attr {
                        maxWidth(260f)
                        backgroundColor(
                            if (msg.role == "user") ThemeColors.accent else ThemeColors.surface
                        )
                        borderRadius(ShapeTokens.MD)
                        padding(left = 14f, right = 14f, top = 10f, bottom = 10f)
                        border(
                            Border(
                                0.5f,
                                BorderStyle.SOLID,
                                ThemeColors.divider
                            )
                        )
                    }
                    Text {
                        attr {
                            fontSize(TextStyles.BODY_MEDIUM.size + 1f)
                            color(
                                if (msg.role == "user") ThemeColors.onAccent
                                else ThemeColors.onSurface
                            )
                            text(msg.content)
                        }
                    }
                }

                // 助手气泡下方的 action row（仅 assistant 渲染）
                if (msg.role == "assistant") {
                    ctx.renderBubbleActions(msg)
                }
            }
        }
    }

    // ── 气泡 action row（📋 🔊 👍 👎 🔄）──────────────────────────────
    private fun renderBubbleActions(msg: ChatMessage) {
        val ctx = this
        View {
            attr {
                marginTop(6f)
                flexDirectionRow()
                alignItemsCenter()
                height(ACTION_ROW_HEIGHT)
            }
            listOf(
                ACTION_COPY to "复制",
                ACTION_TTS to "朗读",
                ACTION_LIKE to "赞",
                ACTION_DISLIKE to "踩",
                ACTION_REGEN to "重生成"
            ).forEach { (icon, _) ->
                View {
                    attr {
                        size(32f, 32f)
                        marginRight(8f)
                        alignItemsCenter()
                        justifyContentCenter()
                    }
                    Text {
                        attr {
                            fontSize(16f)
                            color(ThemeColors.onSurfaceVariant)
                            text(icon)
                        }
                    }
                    event {
                        click {
                            ctx.handleBubbleAction(msg, icon)
                        }
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  业务逻辑
    // ════════════════════════════════════════════════════════════════════════

    private fun handleSend(sample: String? = null) {
        // 截图 demo 阶段：从 constant content 推一条用户消息；接 DSH 后改为读 Input。
        val text = sample ?: "[测试消息]"
        appendMessage("user", text)
        inputRef.view?.setText("")
        simulateReply()
    }

    private fun handleBubbleAction(msg: ChatMessage, icon: String) {
        when (icon) {
            ACTION_REGEN  -> simulateReply(force = true)
            ACTION_COPY   -> ctx_acquireBridgeLog("copy: ${msg.content.take(20)}")
            ACTION_TTS    -> ctx_acquireBridgeLog("tts: ${msg.id}")
            ACTION_LIKE   -> ctx_acquireBridgeLog("like: ${msg.id}")
            ACTION_DISLIKE-> ctx_acquireBridgeLog("dislike: ${msg.id}")
            else          -> { /* no-op */ }
        }
    }

    private fun ctx_acquireBridgeLog(content: String) {
        // 演示阶段：写到 Bridge 日志，避免引入跨端副作用。接 DSH 后改为 dsh.record。
        try {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).log(content)
        } catch (_: Throwable) {
            // 当前若不在原生 runtime（纯 kmp 编译环境）安全降级；不抛错让 Pager 仍能渲染。
        }
    }

    private fun appendMessage(role: String, content: String) {
        val newMsg = ChatMessage(
            id = nextMsgId,
            role = role,
            content = content,
            time = "刚刚",
            avatar = if (role == "user") "👤" else "🤖"
        )
        chatMessages = chatMessages + newMsg
        nextMsgId = nextMsgId + 1
    }

    private fun simulateReply(force: Boolean = false) {
        val ctx = this
        setTimeout(if (force) 200 else 800) {
            ctx.appendMessage(
                "assistant",
                if (force) "好的，我换一种方式回答：" +
                        "「${ctx.selectedChip}」模式下，我可以这样帮你——" +
                        "请告诉我具体场景或问题，我再继续。"
                else "好的，我已收到你的消息。\n" +
                        "当前模式：${ctx.selectedChip}\n" +
                        "有什么我可以帮你的吗？"
            )
        }
    }

    private fun openMoreChips() {
        val ctx = this
        setTimeout(80) {
            ctx.appendMessage(
                "assistant",
                "（更多模式占位）\n" +
                        "• 翻译 / 写作助手 / 代码助手 / 学习辅导 / 创意灵感\n" +
                        "• 拍题答疑 / 照片问答 / 语音通话"
            )
        }
    }

    // ── 数据模型 ────────────────────────────────────────────────────────

    private data class ChatMessage(
        val id: Int,
        val role: String,
        val content: String,
        val time: String,
        val avatar: String
    )

    private data class QuickChip(
        val emoji: String,
        val label: String
    )
}
