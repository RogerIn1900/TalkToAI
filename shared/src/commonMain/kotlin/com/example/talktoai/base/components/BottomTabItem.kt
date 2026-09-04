package com.example.talktoai.base.components

import com.example.talktoai.base.theme.TextStyles
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.base.Color as KColor
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * 单个底部 Tab 项（UI 架构 §5.3）。
 *
 * 视觉规则：
 * - 未选中：emoji（22sp）+ label（11sp onSurfaceVariant, normal）
 * - 选 中：顶部 24×2dp accent 短杆指示器 + label（accent, medium）
 *
 * 选中态指示器模仿 iOS TabBar 顶部小条，比纯文字变色更明显，
 * 同时 emoji 字形在各平台一致性好（颜色不变）。
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

    override fun createAttr(): BottomTabItemAttr = BottomTabItemAttr()
    override fun createEvent(): BottomTabItemEvent = BottomTabItemEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    height(56f)
                    flexDirectionColumn()
                    alignItemsCenter()
                    justifyContentCenter()
                }
                event {
                    click { ctx.event.click?.invoke() }
                }

                // 顶部小指示器（仅选中态可见）
                View {
                    attr {
                        width(if (ctx.attr.selected) 24f else 0f)
                        height(2f)
                        margin(bottom = 4f)
                        borderRadius(1f)
                        backgroundColor(
                            if (ctx.attr.selected) ThemeColors.accent
                            else KColor.TRANSPARENT
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
                        if (ctx.attr.selected) fontWeightMedium() else fontWeightNormal()
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

/** DSL 入口：`BottomTabItem { attr { ... }; event { ... } }` */
internal fun ViewContainer<*, *>.BottomTabItem(init: BottomTabItem.() -> Unit) {
    addChild(BottomTabItem(), init)
}