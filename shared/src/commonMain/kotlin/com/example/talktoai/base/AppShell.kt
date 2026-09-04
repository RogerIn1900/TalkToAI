package com.example.talktoai.base

import com.example.talktoai.base.components.BottomTabBar
import com.example.talktoai.base.components.BottomTabSpec
import com.example.talktoai.base.components.DrawerContent
import com.example.talktoai.base.components.DrawerEntry
import com.example.talktoai.base.components.DrawerOverlay
import com.example.talktoai.base.theme.TextStyles
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.*

/**
 * TalkToAI 应用根容器（UI 架构 §2.1 + §5.1）。
 *
 * 布局层级：
 *   1. PageList  ← 4 个 Tab 子页面（横向滑动）
 *   2. BottomTabBar（叠加在底部，仅主 Tab 浏览态可见）
 *   3. DrawerOverlay + DrawerContent（叠层，仅 Chat Tab 内触发）
 *
 * 入口路由：`app_shell`。启动时由 KuiklyRenderActivity 通过
 * `pageName = "app_shell"` 加载本 Pager 作为根。
 *
 * ## Kuikly PageList 关键约束
 *
 * 1. **类型参数**：`ViewRef<PageListView<*, *>>` 必须给 2 个类型参数（Kuikly 2.7）。
 * 2. **API 名称**：
 *    - 没有 `pageCount` / `currentPage` / `setCurrentPage` / `scrollEnable` 属性
 *    - 用 `defaultPageIndex(Int)` 设初始 Tab·
 *    - 用 `pageDirection(Boolean)` 切横/纵
 *    - 用 `offscreenPageLimit(Int)` 设缓存
 *    - 编程式滚动用 `scrollToPageIndex(Int, Boolean)`
 *    - 监听切换用 `event { pageIndexDidChanged { data -> ... } }`，data 是 JSONObject
 * 3. **子项类型**：PageList 的子项必须是普通 `View`，**不能塞 Pager 实例**。
 *    因此 4 个 Tab 的内容直接内联在 body() lambda 里，
 *    不能像 Compose 那样用 `PageList { pageCount = 4 ; ... } { index -> ... }` 形式。
 * 4. **隐式 this 陷阱**：Pager 是 ViewContainer，所以成员函数里的
 *    `View { ... }` 默认加到 Pager 根，**不会**加到当前嵌套的 View/Scroller/PageList 里。
 *    解决：把 tab 内容直接内联在 PageList 的 init 块里。
 */
@Page("app_shell", supportInLocal = true)
internal class AppShell : BasePager() {

    private val state = AppShellState()

    /** Kuikly 2.7 要求两个类型参数。声明用 `<*, *>` 是因为 PageList DSL 的 ref{} 回调返回 ViewRef<PageListView<*, *>>（通配符投影），无法改成具体类型参数（Kotlin 泛型不协变）。IDE 偶发缓存报错 "2 type arguments expected" 指向注释行 40:47 为误报，实际编译通过。 */
    private lateinit var pageListRef: ViewRef<PageListView<*, *>>

    override fun body(): ViewBuilder {
        val ctx = this
        val safeTop = pagerData.safeAreaInsets.top
        val safeBottom = pagerData.safeAreaInsets.bottom

        return {
            attr {
                flex(1f)
                backgroundColor(ThemeColors.background)
            }

            // ════════════════════════════════════════════════════════════
            //  ① PageList：4 Tab 横向切换
            // ════════════════════════════════════════════════════════════
            PageList {
                ref { ctx.pageListRef = it }
                attr {
                    flex(1f)                        // 撑满剩余空间
                    pageDirection(true)             // 横向滑动
                    offscreenPageLimit(1)            // 缓存相邻 1 个
                    defaultPageIndex(ctx.state.currentTab)  // 初始 Tab
                }
                event {
                    pageIndexDidChanged { data ->
                        // data 是 JSONObject，含 "index" 字段
                        val index = (data as? JSONObject)?.optInt("index") ?: 0
                        ctx.state.switchTab(index)
                    }
                }

                // ───── Tab 0：对话 ─────
                // PageList 子项：普通 View，内联全部内容
                View {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        backgroundColor(ThemeColors.background)
                    }
                    // 顶部栏
                    View {
                        attr {
                            height(56f)
                            padding(top = safeTop, left = 16f, right = 16f, bottom = 0f)
                            backgroundColor(ThemeColors.surface)
                            flexDirectionRow()
                            alignItemsCenter()
                            justifyContentSpaceBetween()
                            borderBottom(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
                        }
                        Text {
                            attr {
                                fontSize(22f)
                                color(ThemeColors.onSurface)
                                text("☰")
                            }
                            event { click { ctx.state.openDrawer() } }
                        }
                        Text {
                            attr {
                                fontSize(TextStyles.TITLE_MEDIUM.size + 1f)
                                color(ThemeColors.onSurface)
                                text("问候")
                                fontWeightSemiBold()
                            }
                        }
                        View { attr { width(24f) } }   // 占位，让标题居中
                    }
                    // 消息占位（实际内容由 ChatPager/DshHubPager 接管）
                    View {
                        attr {
                            flex(1f)
                            alignItemsCenter()
                            justifyContentCenter()
                            padding(24f)
                        }
                        Text {
                            attr {
                                fontSize(14f)
                                color(ThemeColors.onSurfaceVariant)
                                text("对话 Tab（v0.1 占位）\n" +
                                     "后续将 DshHubPager 整体迁入")
                            }
                        }
                    }
                }

                // ───── Tab 1：发现 ─────
                View {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        backgroundColor(ThemeColors.background)
                    }
                    View {
                        attr {
                            padding(top = safeTop + 12f, left = 16f, right = 16f, bottom = 12f)
                            backgroundColor(ThemeColors.surface)
                        }
                        Text {
                            attr {
                                fontSize(TextStyles.TITLE_LARGE.size)
                                color(ThemeColors.onSurface)
                                text("发现")
                                fontWeightSemiBold()
                            }
                        }
                    }
                    Scroller {
                        attr { flex(1f); padding(16f) }
                        Text {
                            attr {
                                fontSize(14f)
                                color(ThemeColors.onSurfaceVariant)
                                text("智能体 / 工具 / 角色 入口\n" +
                                     "（Feature-5 接入 pluginInventory 后填充）")
                            }
                        }
                    }
                }

                // ───── Tab 2：创作 ─────
                View {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        backgroundColor(ThemeColors.background)
                    }
                    View {
                        attr {
                            padding(top = safeTop + 12f, left = 16f, right = 16f, bottom = 12f)
                            backgroundColor(ThemeColors.surface)
                        }
                        Text {
                            attr {
                                fontSize(TextStyles.TITLE_LARGE.size)
                                color(ThemeColors.onSurface)
                                text("创作")
                                fontWeightSemiBold()
                            }
                        }
                    }
                    Scroller {
                        attr { flex(1f); padding(16f) }
                        Text {
                            attr {
                                fontSize(14f)
                                color(ThemeColors.onSurfaceVariant)
                                text("写作 / 绘画 / 音乐 / 视频 工具\n" +
                                     "（Feature-3 接入 promptWithImage 后填充）")
                            }
                        }
                    }
                }

                // ───── Tab 3：我的 ─────
                View {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        backgroundColor(ThemeColors.background)
                    }
                    View {
                        attr {
                            padding(top = safeTop + 12f, left = 16f, right = 16f, bottom = 12f)
                            backgroundColor(ThemeColors.surface)
                        }
                        Text {
                            attr {
                                fontSize(TextStyles.TITLE_LARGE.size)
                                color(ThemeColors.onSurface)
                                text("我的")
                                fontWeightSemiBold()
                            }
                        }
                    }
                    Scroller {
                        attr { flex(1f); padding(16f) }
                        Text {
                            attr {
                                fontSize(14f)
                                color(ThemeColors.onSurfaceVariant)
                                text("用户信息 / 设置 / 帮助\n" +
                                     "（Feature-1 主题 + 各 feature 完成后填充）")
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════
            //  ② 底部 TabBar（绝对定位，二级页面时隐藏）
            // ════════════════════════════════════════════════════════════
            vif({ ctx.state.activeSubRoute == null }) {
                View {
                    attr {
                        positionAbsolute()
                        left(0f); right(0f); bottom(safeBottom)
                    }
                    BottomTabBar {
                        attr {
                            currentTab = ctx.state.currentTab
                            tabs = BOTTOM_TABS
                        }
                        event {
                            tabClick = { index ->
                                // 正确 API：scrollToPageIndex(index, animated)
                                ctx.pageListRef.view?.scrollToPageIndex(index, true)
                                ctx.state.switchTab(index)
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════
            //  ③ Drawer（二级导航遮罩 + 抽屉）
            // ════════════════════════════════════════════════════════════
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

    /** 旧路由 `dsh_hub` 兼容：直接进 Chat Tab。 */
    override fun created() {
        super.created()
        if (pageData.params.optString("pageName") == "dsh_hub") {
            state.switchTab(0)
        }
    }

    companion object {
        val BOTTOM_TABS = listOf(
            BottomTabSpec(0, "对话", "💬"),
            BottomTabSpec(1, "发现", "🔍"),
            BottomTabSpec(2, "创作", "🎨"),
            BottomTabSpec(3, "我的", "👤"),
        )

        val DRAWER_ENTRIES = listOf(
            DrawerEntry("📁", "Workspaces",  "Workspace 列表",         "workspace_list"),
            DrawerEntry("📋", "Sessions",    "Session 管理 / 归档",    "session_list"),
            DrawerEntry("🔌", "Plugins",     "插件清单（Task-5）",      "plugin_list"),
            DrawerEntry("📊", "Log Center", "日志中心（Task-6）",      "log_center"),
            DrawerEntry("🐞", "Diagnostics", "反馈问题包导出",         "diagnostics"),
            DrawerEntry("⚙️",  "Settings",    "主题 / 语言 / 账户",     "settings_main"),
        )
    }
}