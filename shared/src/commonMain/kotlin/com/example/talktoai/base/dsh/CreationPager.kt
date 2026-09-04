package com.example.talktoai.base.dsh

import com.example.talktoai.base.BasePager
import com.example.talktoai.base.theme.TextStyles
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * 创作页（UI 架构 §4.1 Tab 2）。
 *
 * 规划内容（参考豆包"创作/工具"）：
 * - 顶部标题区
 * - 工具分组（写作 / 绘画 / 音乐 / 视频），每组内 2×N 网格
 *
 * 当前为骨架版本（v0.1）：渲染标题 + 4 个占位组卡片。
 * 数据接入由 Feature-2/3/4/5 完成后逐个填充。
 */
@Page("creation", supportInLocal = true)
internal class CreationPager : BasePager() {

    private companion object {
        val TOOL_GROUPS = listOf(
            ToolGroup("✍️ 写作", listOf("📝", "📄", "📧", "📰")),
            ToolGroup("🎨 绘画", listOf("🖼", "🎭", "✏️", "🌈")),
            ToolGroup("🎵 音乐", listOf("🎼", "🎤", "🎧", "🎹")),
            ToolGroup("🎬 视频", listOf("🎞", "📽", "🎥", "📹")),
        )

        data class ToolGroup(val title: String, val icons: List<String>)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val safeTop = pagerData.safeAreaInsets.top
        val tabBarHeight = 56f

        return {
            attr {
                flex(1f)
                backgroundColor(ThemeColors.background)
            }

            // 顶部标题
            View {
                attr {
                    padding(top = safeTop + 16f, left = 16f, right = 16f, bottom = 12f)
                    backgroundColor(ThemeColors.background)
                }
                Text {
                    attr {
                        fontSize(TextStyles.TITLE_LARGE.size)
                        color(ThemeColors.onSurface)
                        text("AI 创作中心")
                        fontWeightBold()
                    }
                }
                Text {
                    attr {
                        fontSize(TextStyles.BODY_SMALL.size)
                        marginTop(4f)
                        color(ThemeColors.onSurfaceVariant)
                        text("选择工具，开始创作")
                    }
                }
            }

            // 工具组列表
            Scroller {
                attr {
                    flex(1f)
                    padding(left = 16f, right = 16f, bottom = tabBarHeight + 16f)
                }

                TOOL_GROUPS.forEachIndexed { idx, group ->
                    if (idx > 0) {
                        View { attr { height(20f) } }
                    }
                    this@CreationPager.ctxRenderToolGroup(group)
                }
            }
        }
    }

    private fun ctxRenderToolGroup(group: ToolGroup) {
        View {
            attr {
                flexDirectionColumn()
                margin(bottom = 16f)
            }
            Text {
                attr {
                    fontSize(TextStyles.TITLE_MEDIUM.size)
                    color(ThemeColors.onSurface)
                    text(group.title)
                    margin(bottom = 12f)
                }
            }

            // 2 行 × 2 列网格
            val rows = group.icons.chunked(2)
            rows.forEachIndexed { rowIdx, rowIcons ->
                if (rowIdx > 0) View { attr { height(12f) } }
                View {
                    attr {
                        flexDirectionRow()
                    }
                    rowIcons.forEachIndexed { colIdx, icon ->
                        this@CreationPager.ctxRenderToolCell(icon, if (colIdx < rowIcons.lastIndex) 6f else 0f)
                    }
                }
            }
        }
    }

    private fun ctxRenderToolCell(icon: String, rightMargin: Float) {
        View {
            attr {
                flex(1f)
                margin(right = rightMargin)
                height(96f)
                backgroundColor(ThemeColors.surface)
                borderRadius(12f)
                flexDirectionColumn()
                alignItemsCenter()
                justifyContentCenter()
            }
            Text {
                attr {
                    fontSize(36f)
                    text(icon)
                }
            }
            Text {
                attr {
                    fontSize(TextStyles.BODY_SMALL.size)
                    marginTop(6f)
                    color(ThemeColors.onSurfaceVariant)
                    text("工具")
                }
            }
        }
    }
}