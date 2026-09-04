package com.example.talktoai.base.theme

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.reactive.handler.observable

/**
 * 全局主题色 facade（TalkToAI/Kuikly 实现蓝图 §4.1 + §4.2）。
 *
 * 替代硬编码 `Color(0xFFF5F7FA)` / `Color(0xFF3B82F6)` 等。
 * - 启动时由 App / Native 端把 [isNightMode] 注入；现在由 [BasePager.themeDidChanged]
 *   同步调用 [updateForNightMode] 推送，保证所有 Pager 立刻重绘。
 * - 字段全部为顶层 `val` + 内部 mutable state on companion。
 * - 后续接入 MaterialKolor 时，仅需替换 [updateForNightMode] 内部；外部 API 不变。
 *
 * 用法：
 * ```kotlin
 * View { attr { backgroundColor(ThemeColors.background) } }
 * ```
 */
internal object ThemeColors {

    /** 当前夜间模式状态（由 [BasePager] 在 `themeDidChanged` 时推送更新）。 */
    var isNightMode: Boolean by observable(false)

    // ── 浅色 token（day mode） ──────────────────────────────────────
    val backgroundLight      get() = Palette.backgroundLight
    val surfaceLight         get() = Palette.surfaceLight
    val surfaceVariantLight  get() = Palette.surfaceVariantLight
    val accentLight          get() = Palette.accentLight
    val onAccentLight        get() = Palette.onAccentLight
    val onSurfaceLight       get() = Palette.onSurfaceLight
    val onSurfaceVariantLight get() = Palette.onSurfaceVariantLight
    val dividerLight         get() = Palette.dividerLight
    val errorLight           get() = Palette.errorLight
    val overlayLight         get() = Palette.overlayLight

    // ── 深色 token（night mode） ─────────────────────────────────────
    val backgroundDark       get() = Palette.backgroundDark
    val surfaceDark          get() = Palette.surfaceDark
    val surfaceVariantDark   get() = Palette.surfaceVariantDark
    val accentDark           get() = Palette.accentDark
    val onAccentDark         get() = Palette.onAccentDark
    val onSurfaceDark        get() = Palette.onSurfaceDark
    val onSurfaceVariantDark get() = Palette.onSurfaceVariantDark
    val dividerDark          get() = Palette.dividerDark
    val errorDark            get() = Palette.errorDark
    val overlayDark          get() = Palette.overlayDark

    // ── 自适应 getter（根据 isNightMode 自动分流） ────────────────────
    val background:          Color get() = if (isNightMode) backgroundDark       else backgroundLight
    val surface:             Color get() = if (isNightMode) surfaceDark          else surfaceLight
    val surfaceVariant:      Color get() = if (isNightMode) surfaceVariantDark   else surfaceVariantLight
    val accent:              Color get() = if (isNightMode) accentDark           else accentLight
    val onAccent:            Color get() = if (isNightMode) onAccentDark         else onAccentLight
    val onSurface:           Color get() = if (isNightMode) onSurfaceDark        else onSurfaceLight
    val onSurfaceVariant:    Color get() = if (isNightMode) onSurfaceVariantDark else onSurfaceVariantLight
    val divider:             Color get() = if (isNightMode) dividerDark          else dividerLight
    val error:               Color get() = if (isNightMode) errorDark            else errorLight
    val overlay:             Color get() = if (isNightMode) overlayDark          else overlayLight

    /** 阴影色不随模式变化（保持中性 alpha），可全局复用。 */
    val shadowSmall:         Color get() = Palette.shadowSmall
    val shadowMedium:        Color get() = Palette.shadowMedium

    /**
     * 由 [BasePager.themeDidChanged] 调用：推送当前夜间模式，让所有引用
     * [ThemeColors.xxx] 的 `observable` 字段触发重绘。
     *
     * 风险与备选：见映射文档 §10。短期 token getter 缓存 200ms，期间不重绘，
     * 避免主题切换全量重绘导致掉帧（Kuikly 端此版本尚未实现 200ms 缓存，
     * 由 App 端控制主题切换节流，ThemeColors 保持 O(1) 读取）。
     */
    fun updateForNightMode(night: Boolean) {
        isNightMode = night
    }
}
