package com.example.talktoai.base.dsh

import com.example.talktoai.base.BasePager
import com.example.talktoai.base.theme.TextStyles
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * 发现页（UI 架构 §4.1 Tab 1）。
 *
 * 规划内容（参考豆包"发现/智能体"）：
 * - 顶部搜索栏（按智能体名 / 标签过滤）
 * - 分类 chips（全部 / 学习 / 工作 / 生活 / 娱乐 / 角色；横滑）
 * - 2 列智能体卡片网格（头像 + 名称 + 描述 + 使用量）
 *
 * 当前为骨架版本（v0.1）：渲染搜索栏 + 占位卡片 4 个，
 * 数据接入 [DshRemote.pluginInventory] 由 Feature-5 完成后接入。
 */
@Page("discover", supportInLocal = true)
internal class DiscoverPager : BasePager() {

    private var query: String by observable("")
    private var selectedCategory: Int by observable(0)

    private companion object {
        val CATEGORIES = listOf(
            "全部", "学习", "工作", "生活", "娱乐", "角色", "效率", "创意"
        )
        val PLACEHOLDER_AGENTS = listOf(
            "🤖", "📚", "💼", "🎨"
        )
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

            // ── 顶部搜索栏 + 分类 chips ────────────────────────────────
            View {
                attr {
                    padding(top = safeTop + 8f, left = 12f, right = 12f, bottom = 8f)
                    backgroundColor(ThemeColors.surface)
                }

                // 搜索框
                View {
                    attr {
                        height(40f)
                        backgroundColor(ThemeColors.background)
                        borderRadius(20f)
                        padding(left = 14f, right = 14f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    Text {
                        attr {
                            fontSize(15f)
                            marginRight(8f)
                            color(ThemeColors.onSurfaceVariant)
                            text("🔍")
                        }
                    }
                    Text {
                        attr {
                            fontSize(TextStyles.BODY_MEDIUM.size)
                            color(ThemeColors.onSurfaceVariant)
                            text("搜索智能体、工具、角色…")
                            flex(1f)
                        }
                    }
                }

                // 分类 chips（横滑）
                Scroller {
                    attr {
                        marginTop(12f)
                        flexDirectionRow()
                    }
                    CATEGORIES.forEachIndexed { idx, label ->
                        View {
                            attr {
                                padding(left = 14f, right = 14f, top = 6f, bottom = 6f)
                                margin(right = 8f)
                                borderRadius(16f)
                                backgroundColor(
                                    if (ctx.selectedCategory == idx) ThemeColors.accent
                                    else ThemeColors.background
                                )
                            }
                            event {
                                click { ctx.selectedCategory = idx }
                            }
                            Text {
                                attr {
                                    fontSize(TextStyles.BODY_SMALL.size + 1f)
                                    color(
                                        if (ctx.selectedCategory == idx) ThemeColors.onAccent
                                        else ThemeColors.onSurface
                                    )
                                    text(label)
                                }
                            }
                        }
                    }
                }
            }

            // ── 智能体网格（2 列） ─────────────────────────────────────
            Scroller {
                attr {
                    flex(1f)
                    padding(left = 12f, right = 12f, top = 12f, bottom = tabBarHeight + 12f)
                    flexDirectionColumn()
                }

                // 第 1 行
                View {
                    attr {
                        flexDirectionRow()
                        margin(bottom = 12f)
                    }
                    PLACEHOLDER_AGENTS.take(2).forEach { emoji ->
                        this@DiscoverPager.ctxRenderAgentCard(emoji, "占位智能体", "由 Feature-5 接入")
                    }
                }
                // 第 2 行
                View {
                    attr {
                        flexDirectionRow()
                    }
                    PLACEHOLDER_AGENTS.drop(2).take(2).forEach { emoji ->
                        this@DiscoverPager.ctxRenderAgentCard(emoji, "占位智能体", "由 Feature-5 接入")
                    }
                }
            }
        }
    }

    private fun ctxRenderAgentCard(emoji: String, name: String, desc: String) {
        View {
            attr {
                flex(1f)
                margin(right = 6f)
                padding(12f)
                backgroundColor(ThemeColors.surface)
                borderRadius(12f)
                flexDirectionColumn()
            }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                View {
                    attr {
                        size(40f, 40f)
                        borderRadius(20f)
                        backgroundColor(ThemeColors.surfaceVariant)
                        alignItemsCenter()
                        justifyContentCenter()
                    }
                    Text {
                        attr {
                            fontSize(20f)
                            text(emoji)
                        }
                    }
                }
                View {
                    attr {
                        flex(1f)
                        marginLeft(8f)
                    }
                    Text {
                        attr {
                            fontSize(TextStyles.BODY_MEDIUM.size)
                            color(ThemeColors.onSurface)
                            text(name)
                        }
                    }
                    Text {
                        attr {
                            fontSize(TextStyles.BODY_SMALL.size)
                            marginTop(2f)
                            color(ThemeColors.onSurfaceVariant)
                            text(desc)
                        }
                    }
                }
            }
        }
    }
}