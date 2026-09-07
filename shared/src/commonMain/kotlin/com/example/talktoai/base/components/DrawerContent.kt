package com.example.talktoai.base.components

import com.example.talktoai.base.theme.TextStyles
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * 侧边栏 Drawer（TalkToAI/Kuikly 实现蓝图 §3.2）。
 *
 * 设计约束：
 * - 提供可复用的会话抽屉渲染，
 *   host 只需要把 [DrawerEntry] 列表 / brandTitle 注入即可。
 * - 配色完全走 [ThemeColors]（§4.1）；亮 / 暗模式自动适配。
 * - 自身只负责渲染与回调分发；显示 / 隐藏 + 遮罩由外层持有者负责（手机端默认浮层，
 *   平板端可走常驻分支，参考后续 [AppShell]）。
 *
 * 用法：
 * ```kotlin
 * DrawerContent(
 *     brandTitle = "豆包 AI",
 *     brandSubtitle = "Design-Driven Smart Host",
 *     entries = listOf(DrawerEntry("💬", "Chat", "...", "chat"), ...),
 *     onEntryClick = { entry -> router.openPage(entry.route, JSONObject()) },
 *     onDismiss = { drawerOpen = false }
 * )
 * ```
 *
 * ComposeView 模板遵循 `RouterPage.kt` 内的 `RouterNavigationBar` 模式：
 * - `attr { … }` 暴露可配置字段；
 * - `event` 由外部通过回调赋值（与 RouterNavBar 一致无自定义 Event 子类）。
 */

// ── 数据模型 ─────────────────────────────────────────────────────────

internal data class DrawerEntry(
    val emoji: String,
    val title: String,
    val subtitle: String,
    val route: String,
    val dot: Boolean = false,    // 可选：右上小红点（未读 / 新功能）
)

internal class DrawerContentAttr : ComposeAttr() {
    var brandTitle: String by observable("")
    var brandSubtitle: String by observable("")
    var entries: List<DrawerEntry> by observable(emptyList())
    var width: Float by observable(280f)
}

internal class DrawerContentEvent : ComposeEvent() {
    var entryClick: ((DrawerEntry) -> Unit)? = null
    var dismiss: (() -> Unit)? = null
}

internal class DrawerContent : ComposeView<DrawerContentAttr, DrawerContentEvent>() {

    override fun createAttr(): DrawerContentAttr = DrawerContentAttr()
    override fun createEvent(): DrawerContentEvent = DrawerContentEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        // 安全区需要在 body() 阶段读取（pagerData 在 View 生命周期内有效）
        val safeTop = ctx.pagerData.safeAreaInsets.top
        val safeBottom = ctx.pagerData.safeAreaInsets.bottom

        return {
            View {
                attr {
                    positionAbsolute()
                    top(0f); left(0f); bottom(0f)
                    width(ctx.attr.width)
                    backgroundColor(ThemeColors.surface)
                    flexDirectionColumn()
                    zIndex(30, false)
                    // 取消动画先不做；Phase 5 补 drawer 滑入
                }

                // ── ① 头部品牌区（避开顶部安全区） ────────────────────────
                View {
                    attr {
                        padding(top = safeTop + 16f, left = 16f, right = 16f, bottom = 16f)
                        backgroundColor(ThemeColors.accent)
                        justifyContentFlexEnd()
                        flexDirectionColumn()
                    }
                    Text {
                        attr {
                            fontSize(TextStyles.TITLE_LARGE.size)
                            color(ThemeColors.onAccent)
                            text(ctx.attr.brandTitle)
                        }
                    }
                    Text {
                        attr {
                            fontSize(TextStyles.BODY_SMALL.size)
                            marginTop(4f)
                            color(ThemeColors.onAccent)
                            text(ctx.attr.brandSubtitle)
                        }
                    }
                }

                // ── ② 入口列表 ────────────────────────────────────────────
                Scroller {
                    attr { flex(1f) }
                    ctx.attr.entries.forEach { entry ->
                        View {
                            attr {
                                padding(left = 16f, right = 16f, top = 14f, bottom = 14f)
                                borderBottom(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
                                flexDirectionRow()
                                alignItemsCenter()
                            }
                            event {
                                click {
                                    ctx.event.entryClick?.invoke(entry)
                                }
                            }
                            Text {
                                attr {
                                    fontSize(24f)
                                    marginRight(12f)
                                    text(entry.emoji)
                                }
                            }
                            View {
                                attr { flex(1f) }
                                Text {
                                    attr {
                                        fontSize(TextStyles.TITLE_MEDIUM.size)
                                        color(ThemeColors.onSurface)
                                        text(entry.title)
                                    }
                                }
                                Text {
                                    attr {
                                        fontSize(TextStyles.LABEL_SMALL.size)
                                        marginTop(2f)
                                        color(ThemeColors.onSurfaceVariant)
                                        text(entry.subtitle)
                                    }
                                }
                            }
                            if (entry.dot) {
                                // 未读 / 通知小红点
                                View {
                                    attr {
                                        size(8f, 8f)
                                        borderRadius(4f)
                                        backgroundColor(ThemeColors.error)
                                        marginRight(4f)
                                    }
                                }
                            }
                            Text {
                                attr {
                                    fontSize(18f)
                                    color(ThemeColors.divider)
                                    text("›")
                                }
                            }
                        }
                    }
                }

                // ── ③ 底部 / 关闭按钮 ─────────────────────────────────────
                View {
                    attr {
                        padding(top = 16f, bottom = 16f + safeBottom, left = 16f, right = 16f)
                        borderTop(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
                    }
                    Text {
                        attr {
                            fontSize(TextStyles.BODY_MEDIUM.size)
                            color(ThemeColors.onSurfaceVariant)
                            text("关闭菜单")
                        }
                        event {
                            click { ctx.event.dismiss?.invoke() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * DSL 入口：让 host 用 `DrawerContent { attr { ... }; event { ... } }` 直接嵌入。
 */
internal fun ViewContainer<*, *>.DrawerContent(init: DrawerContent.() -> Unit) {
    addChild(DrawerContent(), init)
}

/**
 * 抽屉遮罩（TalkToAI/Kuikly 实现蓝图 §3.1）。
 * - 手机端：drawer 上方/下方铺一层半透明区域，点击关闭；
 * - 平板端不需要；
 * 当前实现默认手机，调用方按平台决定是否渲染。
 */
internal fun ViewContainer<*, *>.DrawerOverlay(onDismiss: () -> Unit) {
    View {
        attr {
            positionAbsolute()
            top(0f); left(0f); right(0f); bottom(0f)
            backgroundColor(ThemeColors.overlay)
            zIndex(20, false)
        }
        event {
            click { onDismiss() }
        }
    }
}
