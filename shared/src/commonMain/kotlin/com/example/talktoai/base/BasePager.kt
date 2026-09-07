package com.example.talktoai.base

import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.*

/**
 * 所有 Pager 的基类（TalkToAI/Kuikly 实现蓝图 §1 + §4）。
 *
 * 关键职责：
 * 1. 注入 [BridgeModule]（§2.1 跨端一致性）。
 * 2. 监听 `themeDidChanged`，把夜间模式状态同步推给 [ThemeColors]（§4.2），
 *    所有引用 [ThemeColors.xxx] 的节点会随之重绘。
 * 3. 将统一主题 token 暴露给所有 TalkToAI 页面。
 */
internal abstract class BasePager : Pager() {

    private var nightModel: Boolean? by observable(null)

    override fun createExternalModules(): Map<String, Module>? {
        val externalModules = hashMapOf<String, Module>()
        externalModules[BridgeModule.MODULE_NAME] = BridgeModule()
        return externalModules
    }

    override fun created() {
        super.created()
        // 首次进入页时把夜间模式 push 到全局 ThemeColors，让首个 draw 用对色。
        ThemeColors.updateForNightMode(isNightMode())
    }

    /**
     * 主题变化钩子（Kuikly 原生 hook；§4.2）。
     *
     * 收到平台层（Native / 平板 / Activity）派发的主题变更后：
     * - 推进 [nightModel]；
     * - 推送 [ThemeColors]，让全 App 引用 token 的 `observable` 字段触发重绘。
     */
    override fun themeDidChanged(data: JSONObject) {
        super.themeDidChanged(data)
        nightModel = data.optBoolean(IS_NIGHT_MODE_KEY)
        ThemeColors.updateForNightMode(nightModel!!)
    }

    // 是否为夜间模式
    override fun isNightMode(): Boolean {
        if (nightModel == null) {
            nightModel = pageData.params.optBoolean(IS_NIGHT_MODE_KEY)
        }
        return nightModel!!
    }

    // 不开启调试UI模式
    override fun debugUIInspector(): Boolean {
        return false
    }

    companion object {
        const val IS_NIGHT_MODE_KEY = "isNightMode"
    }
}
