package com.example.talktoai.base.dsh

import com.example.talktoai.base.BasePager
import com.example.talktoai.base.theme.TextStyles
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * 个人中心（UI 架构 §4.1 Tab 3）。
 *
 * 规划内容（参考豆包"我的"）：
 * - 顶部用户信息卡（头像 / 昵称 / ID）
 * - 功能菜单分组（收藏 / 历史 / 我的智能体 等）
 * - 设置入口（Theme / Help / About 等）
 *
 * 当前为骨架版本（v0.1）：渲染用户卡 + 2 个分组。
 * 完整数据接入在 Feature-1（Theme）与 Feature-4/5/6 完成后。
 */
@Page("profile", supportInLocal = true)
internal class ProfilePager : BasePager() {

    private companion object {
        val FIRST_GROUP = listOf(
            "我的收藏" to "⭐",
            "历史记录" to "📜",
            "我的智能体" to "🤖",
        )
        val SECOND_GROUP = listOf(
            "设置" to "⚙️",
            "帮助与反馈" to "❓",
            "关于" to "ℹ️",
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

            Scroller {
                attr {
                    flex(1f)
                    padding(top = safeTop + 16f, left = 16f, right = 16f, bottom = tabBarHeight + 16f)
                }

                // ── 用户信息卡 ─────────────────────────────────────────
                View {
                    attr {
                        backgroundColor(ThemeColors.surface)
                        borderRadius(16f)
                        padding(20f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    View {
                        attr {
                            size(56f, 56f)
                            borderRadius(28f)
                            backgroundColor(ThemeColors.accent)
                            alignItemsCenter()
                            justifyContentCenter()
                        }
                        Text {
                            attr {
                                fontSize(28f)
                                color(ThemeColors.onAccent)
                                text("👤")
                            }
                        }
                    }
                    View {
                        attr {
                            flex(1f)
                            marginLeft(16f)
                        }
                        Text {
                            attr {
                                fontSize(TextStyles.TITLE_MEDIUM.size)
                                color(ThemeColors.onSurface)
                                text("未登录用户")
                                fontWeightBold()
                            }
                        }
                        Text {
                            attr {
                                fontSize(TextStyles.BODY_SMALL.size)
                                marginTop(4f)
                                color(ThemeColors.onSurfaceVariant)
                                text("点击登录以使用完整功能")
                            }
                        }
                    }
                }

                View { attr { height(24f) } }

                // ── 第一组菜单 ─────────────────────────────────────────
                this@ProfilePager.ctxRenderMenuGroup(FIRST_GROUP)
                View { attr { height(16f) } }

                // ── 第二组菜单 ─────────────────────────────────────────
                this@ProfilePager.ctxRenderMenuGroup(SECOND_GROUP)
            }
        }
    }

    private fun ctxRenderMenuGroup(items: List<Pair<String, String>>) {
        View {
            attr {
                backgroundColor(ThemeColors.surface)
                borderRadius(12f)
                flexDirectionColumn()
            }
            items.forEachIndexed { idx, (label, emoji) ->
                View {
                    attr {
                        padding(left = 16f, right = 16f, top = 14f, bottom = 14f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    if (idx < items.lastIndex) {
                        attr {
                            borderBottom(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
                        }
                    }
                    Text {
                        attr {
                            fontSize(20f)
                            marginRight(12f)
                            text(emoji)
                        }
                    }
                    Text {
                        attr {
                            fontSize(TextStyles.BODY_MEDIUM.size + 1f)
                            color(ThemeColors.onSurface)
                            text(label)
                            flex(1f)
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
    }
}