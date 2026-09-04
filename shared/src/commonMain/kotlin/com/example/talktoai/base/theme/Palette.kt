package com.example.talktoai.base.theme

import com.tencent.kuikly.core.base.Color

/**
 * 颜色 token 集合（TalkToAI/Kuikly 实现蓝图 §4.1）。
 *
 * - 仅放静态 token；运行期切换在 [ThemeColors] 上完成。
 * - 与 Material 3 命名对齐：background / surface / accent / onSurface / onSurfaceVariant / divider / error …
 * - 浅色为默认，深色为 nightMode 下的对应色。
 *
 * 后续可接入 MaterialKolor 等价（在 androidMain 跑一遍 seed → 13 色板再回填），
 * 现阶段先用静态对齐方案落地，Pager 全部走 [ThemeColors.xxx] 读色。
 */
internal object Palette {

    // ── 浅色（light） ────────────────────────────────────────────────
    val backgroundLight      = Color(0xFFF5F7FA)   // 整体页面底色
    val surfaceLight         = Color(0xFFFFFFFF)   // 卡片/输入框/气泡底
    val surfaceVariantLight  = Color(0xFFEEF2FF)   // chip 选中态等次级表面
    val accentLight          = Color(0xFF3B82F6)   // 主色（按钮/强调）
    val onAccentLight        = Color(0xFFFFFFFF)   // 主色之上的前景（按钮文字）
    val onSurfaceLight       = Color(0xFF1A1A1A)   // 正文
    val onSurfaceVariantLight = Color(0xFF666666)  // 副文/时间戳
    val dividerLight         = Color(0xFFE5E5E5)   // 分隔线
    val errorLight           = Color(0xFFB00020)   // 错误/警示
    val overlayLight         = Color(0x66000000)   // 抽屉/弹窗半透明遮罩

    // ── 深色（dark） ─────────────────────────────────────────────────
    val backgroundDark       = Color(0xFF121212)
    val surfaceDark          = Color(0xFF1E1E1E)
    val surfaceVariantDark   = Color(0xFF1F2937)
    val accentDark           = Color(0xFF60A5FA)
    val onAccentDark         = Color(0xFF0B1220)
    val onSurfaceDark        = Color(0xFFE5E5E5)
    val onSurfaceVariantDark = Color(0xFFB0B0B0)
    val dividerDark          = Color(0xFF2A2A2A)
    val errorDark            = Color(0xFFCF6679)
    val overlayDark          = Color(0x80000000)

    // ── 阴影 / BoxShadow alpha（不会随模式切换） ────────────────────
    val shadowSmall          = Color(0x1A000000)
    val shadowMedium         = Color(0x1F000000)
}
