package com.example.talktoai.base.components

import com.example.talktoai.base.theme.ShapeTokens
import com.example.talktoai.base.theme.TextStyles
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * TabRow（TalkToAI/Kuikly 实现蓝图 §3.6 SettingsPager 的 TabRow 模板）。
 *
 * - 选中态：accent 下边框 + 加粗 + 主色文字。
 * - 选中背景：[ThemeColors.surfaceVariant]（亮色为浅紫，夜间对应对比色）。
 *
 * 用法：
 * ```kotlin
 * TabsRow(
 *     tabs = listOf("通用", "Chat", "账户", "数据"),
 *     selectedIndex = 0,
 *     onSelect = { settingsTab = it }
 * )
 * ```
 */
internal fun ViewContainer<*, *>.TabsRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val tabsRef = tabs
    tabsRef.forEachIndexed { i, label ->
        val isSelected = i == selectedIndex
        View {
            attr {
                flex(1f)
                padding(top = 12f, bottom = 12f)
                alignItemsCenter()
                backgroundColor(
                    if (isSelected) ThemeColors.surfaceVariant else ThemeColors.surface
                )
                borderBottom(
                    if (isSelected) Border(2f, BorderStyle.SOLID, ThemeColors.accent)
                    else Border(0f, BorderStyle.SOLID, ThemeColors.background)
                )
            }
            event { click { onSelect(i) } }
            Text {
                attr {
                    text(label)
                    fontSize(TextStyles.BODY_MEDIUM.size)
                    color(if (isSelected) ThemeColors.accent else ThemeColors.onSurface)
                }
            }
        }
    }
}

/**
 * 设置项 row（右箭头 + 副标题；§3.6 列表样式）。
 * 使用 ViewContainer 扩展方便在任意父里嵌入。
 */
internal fun ViewContainer<*, *>.SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    View {
        attr {
            padding(left = 16f, right = 16f, top = 14f, bottom = 14f)
            borderBottom(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
            flexDirectionRow()
            alignItemsCenter()
            backgroundColor(ThemeColors.surface)
        }
        event { click { onClick() } }
        View {
            attr { flex(1f) }
            Text {
                attr {
                    text(title)
                    fontSize(TextStyles.BODY_MEDIUM.size + 1f)
                    color(ThemeColors.onSurface)
                }
            }
            if (subtitle.isNotEmpty()) {
                Text {
                    attr {
                        text(subtitle)
                        fontSize(TextStyles.BODY_SMALL.size)
                        marginTop(2f)
                        color(ThemeColors.onSurfaceVariant)
                    }
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

/**
 * 通用分组 header（带上下间距 + 副标题）。
 */
internal fun ViewContainer<*, *>.SettingsGroupHeader(title: String) {
    View {
        attr {
            padding(left = 16f, right = 16f, top = 18f, bottom = 6f)
        }
        Text {
            attr {
                text(title)
                fontSize(TextStyles.LABEL_SMALL.size + 1f)
                color(ThemeColors.onSurfaceVariant)
            }
        }
    }
}

/**
 * 圆角卡片容器（§4.4 ShapeTokens.MD）。
 *
 * 注：Kuikly 的 `overflow(visible: Boolean)` API 用于显式控制子节点剪裁；
 * 圆角设置本身也会让子孩子被裁剪到本 View 范围（见 `IStyleAttr.kt` 注释）。
 * 此处叠加显式 `overflow(true)`，双保险防止最后一行底部的 divider 在某些平台上越出圆角。
 */
internal fun ViewContainer<*, *>.SettingsCard(content: ViewBuilder) {
    View {
        attr {
            margin(left = 12f, right = 12f)
            backgroundColor(ThemeColors.surface)
            borderRadius(ShapeTokens.MD)
            // 不上 shadow：避免 dark mode 下过重；如果要加，对应 §4.1 主题运行时切换
            border(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
            overflow(true)            // Kuikly API: overflow(visible: Boolean) — true 表示裁剪子节点
        }
        content()
    }
}
