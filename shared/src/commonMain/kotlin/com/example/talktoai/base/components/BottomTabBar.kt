package com.example.talktoai.base.components

import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * 底部 Tab 栏（UI 架构 §5.2）。
 *
 * 规格：
 * - 总高度 56dp（不含顶部 0.5dp Divider）
 * - 子项 4 等分，每项宽 = 父宽 / 4
 * - 背景色 [ThemeColors.surface]，与页面内容区分
 *
 * 用法（参考 [com.example.talktoai.base.AppShell]）：
 * ```kotlin
 * BottomTabBar {
 *     attr {
 *         currentTab = state.currentTab
 *         tabs = listOf(
 *             BottomTabSpec(0, "对话", "💬"),
 *             BottomTabSpec(1, "发现", "🔍"),
 *             BottomTabSpec(2, "创作", "🎨"),
 *             BottomTabSpec(3, "我的", "👤"),
 *         )
 *     }
 *     event {
 *         tabClick = { index -> pageListRef.view?.setCurrentPage(index, true) }
 *     }
 * }
 * ```
 */
internal data class BottomTabSpec(
    val index: Int,
    val label: String,
    val emoji: String,
)

internal class BottomTabBarAttr : ComposeAttr() {
    var currentTab: Int by observable(0)
    var tabs: List<BottomTabSpec> by observable(emptyList())
}

internal class BottomTabBarEvent : ComposeEvent() {
    var tabClick: ((Int) -> Unit)? = null
}

internal class BottomTabBar : ComposeView<BottomTabBarAttr, BottomTabBarEvent>() {

    override fun createAttr(): BottomTabBarAttr = BottomTabBarAttr()
    override fun createEvent(): BottomTabBarEvent = BottomTabBarEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flexDirectionColumn()
                    backgroundColor(ThemeColors.surface)
                }

                // 顶部 0.5dp 分隔线
                View {
                    attr {
                        height(0.5f)
                        backgroundColor(ThemeColors.divider)
                    }
                }

                // Tab Row
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

/** DSL 入口：`BottomTabBar { attr { ... }; event { ... } }` */
internal fun ViewContainer<*, *>.BottomTabBar(init: BottomTabBar.() -> Unit) {
    addChild(BottomTabBar(), init)
}