package com.example.talktoai.base.dsh

import com.example.talktoai.base.BasePager
import com.example.talktoai.base.components.SettingsCard
import com.example.talktoai.base.components.SettingsGroupHeader
import com.example.talktoai.base.components.SettingsRow
import com.example.talktoai.base.components.TabsRow
import com.example.talktoai.base.theme.TextStyles
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * 设置主页（TalkToAI/Kuikly 实现蓝图 §3.6 + 路由表 §7.1）。
 *
 * 4 个 tab：
 *   - General：主题 / 语言 / 字体大小 / 通知（占位）
 *   - Chat   ：默认模型 / 流式 / Markdown / Artifact 开关（占位）
 *   - Account：当前账号 / 切换 / 登出 / 删除账号（占位）
 *   - Data   ：清缓存 / 导出诊断包（接入 [DiagnosticsPager]）
 *
 * 当前为骨架版本；具体子页与持久化交给后续任务跟进。
 */
@Page("settings_main")
internal class SettingsPager : BasePager() {

    private var selectedTab: Int by observable(0)
    private var fontSizeLevel: String by observable("标准")
    private var streamEnabled: Boolean by observable(true)
    private var markdownEnabled: Boolean by observable(true)
    private var artifactEnabled: Boolean by observable(true)
    private var darkModeLabel: String by observable("跟随系统")

    override fun body(): ViewBuilder {
        val ctx = this
        val safeTop = pageData.safeAreaInsets.top

        return {
            View {
                attr {
                    flex(1f)
                    backgroundColor(ThemeColors.background)
                    flexDirectionColumn()
                }

                // ── ① 顶部 TopBar（§3.6） ─────────────────────────────────
                View {
                    attr {
                        height(56f + safeTop)
                        padding(top = safeTop, left = 16f, right = 16f, bottom = 0f)
                        backgroundColor(ThemeColors.surface)
                        flexDirectionRow()
                        alignItemsCenter()
                        borderBottom(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
                    }
                    Text {
                        attr {
                            fontSize(20f)
                            marginRight(12f)
                            text("‹")
                        }
                        event {
                            click {
                                ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                                    .closePage()
                            }
                        }
                    }
                    Text {
                        attr {
                            fontSize(TextStyles.TITLE_MEDIUM.size)
                            color(ThemeColors.onSurface)
                            text("设置")
                        }
                    }
                }

                // ── ② TabRow（§3.6 模板） ──────────────────────────────────
                View {
                    attr {
                        flexDirectionRow()
                        backgroundColor(ThemeColors.surface)
                        borderBottom(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
                    }
                    TabsRow(
                        tabs = listOf("通用", "Chat", "账户", "数据"),
                        selectedIndex = ctx.selectedTab,
                        onSelect = { ctx.selectedTab = it }
                    )
                }

                // ── ③ Tab Body（按 selectedTab 切换） ───────────────────────
                Scroller {
                    attr { flex(1f) }
                    ctx.renderTabBody()
                }
            }
        }
    }

    private fun renderTabBody(): ViewBuilder {
        val ctx = this
        return {
            when (ctx.selectedTab) {
                0 -> ctx.renderGeneralTab()
                1 -> ctx.renderChatTab()
                2 -> ctx.renderAccountTab()
                3 -> ctx.renderDataTab()
                else -> View {}
            }
        }
    }

    private fun renderGeneralTab(): ViewBuilder {
        val ctx = this
        return {
            SettingsGroupHeader("外观")
            SettingsCard {
                SettingsRow(
                    title = "主题模式",
                    subtitle = ctx.darkModeLabel,
                    onClick = {
                        // 简易切换：跟随系统 / 浅色 / 深色
                        ctx.darkModeLabel = when (ctx.darkModeLabel) {
                            "跟随系统" -> "浅色"
                            "浅色"     -> "深色"
                            else       -> "跟随系统"
                        }
                    }
                )
                SettingsRow(
                    title = "字体大小",
                    subtitle = ctx.fontSizeLevel,
                    onClick = {
                        ctx.fontSizeLevel = when (ctx.fontSizeLevel) {
                            "标准" -> "大"
                            "大"   -> "特大"
                            else   -> "标准"
                        }
                    }
                )
                SettingsRow(
                    title = "通知",
                    subtitle = "已开启",
                    onClick = { /* TODO: 通知设置子页（Phase 5） */ }
                )
            }

            SettingsGroupHeader("语言")
            SettingsCard {
                SettingsRow(
                    title = "界面语言",
                    subtitle = "简体中文",
                    onClick = { /* TODO: i18n（Phase 6） */ }
                )
            }
        }
    }

    private fun renderChatTab(): ViewBuilder {
        val ctx = this
        return {
            SettingsGroupHeader("默认行为")
            SettingsCard {
                SettingsRow(
                    title = "流式输出",
                    subtitle = if (ctx.streamEnabled) "已开启" else "已关闭",
                    onClick = { ctx.streamEnabled = !ctx.streamEnabled }
                )
                SettingsRow(
                    title = "Markdown 渲染",
                    subtitle = if (ctx.markdownEnabled) "已开启" else "已关闭",
                    onClick = { ctx.markdownEnabled = !ctx.markdownEnabled }
                )
                SettingsRow(
                    title = "Artifact 渲染（Mermaid/SVG/HTML）",
                    subtitle = if (ctx.artifactEnabled) "已开启" else "已关闭",
                    onClick = { ctx.artifactEnabled = !ctx.artifactEnabled }
                )
            }

            SettingsGroupHeader("默认模型")
            SettingsCard {
                SettingsRow(
                    title = "选主模型",
                    subtitle = "openai / gpt-4o",
                    onClick = {
                        // 后续：跳转到 ModelSelectorSheet
                    }
                )
            }
        }
    }

    private fun renderAccountTab(): ViewBuilder {
        return {
            SettingsGroupHeader("账号")
            SettingsCard {
                SettingsRow(
                    title = "当前账号",
                    subtitle = "demo@talkto.ai",
                    onClick = { /* TODO 账号详情 */ }
                )
                SettingsRow(
                    title = "切换账号",
                    subtitle = "添加 / 选择本地账号",
                    onClick = { /* TODO 账号列表 */ }
                )
                SettingsRow(
                    title = "登出",
                    subtitle = "清除当前账号会话",
                    onClick = { /* TODO 登出 */ }
                )
            }

            SettingsGroupHeader("危险操作")
            SettingsCard {
                SettingsRow(
                    title = "删除账号",
                    subtitle = "不可恢复，请谨慎",
                    onClick = { /* TODO 删除账号（Phase 5） */ }
                )
            }
        }
    }

    private fun renderDataTab(): ViewBuilder {
        val ctx = this
        return {
            SettingsGroupHeader("缓存")
            SettingsCard {
                SettingsRow(
                    title = "清理本地缓存",
                    subtitle = "图片 / 日志 / 偏好",
                    onClick = { /* TODO 清缓存 */ }
                )
            }

            SettingsGroupHeader("诊断")
            SettingsCard {
                SettingsRow(
                    title = "导出诊断包",
                    subtitle = "脱敏后导出，便于反馈问题",
                    onClick = {
                        ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                            .openPage("diagnostics", com.tencent.kuikly.core.nvi.serialization.json.JSONObject())
                    }
                )
            }
        }
    }
}
