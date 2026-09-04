package com.example.talktoai.base.theme

/**
 * 字体阶梯 token（TalkToAI/Kuikly 实现蓝图 §4.3）。
 *
 * Kuikly DSL 没有 Typography 抽象，所以封一份常量表。
 * - fontSize 单位 pt（dp）。
 * - FontWeight 在 Kuikly 中通过 `fontWeightNormal / SemiBold / Bold` 等方法标识。
 *
 * 用法：
 * ```kotlin
 * Text {
 *     attr {
 *         text("...")
 *         fontSize(TextStyles.TITLE_MEDIUM.size)
 *         fontWeightSemiBold()
 *     }
 * }
 * ```
 *
 * 后续如果加 i18n / Dynamic Type 支持，可在 [TextStyles] 顶部接 `LocalDensity`/字号倍率。
 */
internal object TextStyles {

    private const val SEMI = "SEMI"
    private const val BOLD = "BOLD"
    private const val NORMAL = "NORMAL"

    const val DISPLAY_LARGE_SIZE = 30f
    const val TITLE_LARGE_SIZE   = 22f
    const val TITLE_MEDIUM_SIZE  = 17f
    const val BODY_LARGE_SIZE    = 16f
    const val BODY_MEDIUM_SIZE   = 14f
    const val BODY_SMALL_SIZE    = 12f
    const val LABEL_SMALL_SIZE   = 11f

    data class TextStyle(val size: Float, val weight: String)

    val DISPLAY_LARGE = TextStyle(DISPLAY_LARGE_SIZE, BOLD)      // 空状态插画副标题（少用）
    val TITLE_LARGE   = TextStyle(TITLE_LARGE_SIZE,   BOLD)      // 顶部标题 / Drawer 品牌区
    val TITLE_MEDIUM  = TextStyle(TITLE_MEDIUM_SIZE,  SEMI)      // 卡片标题、消息头
    val BODY_LARGE    = TextStyle(BODY_LARGE_SIZE,    NORMAL)    // 普通正文
    val BODY_MEDIUM   = TextStyle(BODY_MEDIUM_SIZE,   NORMAL)    // 列表项主文字
    val BODY_SMALL    = TextStyle(BODY_SMALL_SIZE,    NORMAL)    // 副文 / 日期 header
    val LABEL_SMALL   = TextStyle(LABEL_SMALL_SIZE,   NORMAL)    // 状态徽标 / chip
}

/**
 * 圆角阶梯（TalkToAI/Kuikly 实现蓝图 §4.4）。同字体一样，没有原生 Shape token，
 * 抽常量避免每帧分配 / 拼写错。
 */
internal object ShapeTokens {
    const val XS   = 4f
    const val SM   = 8f
    const val MD   = 12f
    const val LG   = 16f
    const val XL   = 24f
    const val PILL = 999f
}
