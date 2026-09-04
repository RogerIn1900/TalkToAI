package com.example.talktoai.base

import com.tencent.kuikly.core.reactive.handler.observable

/**
 * TalkToAI 应用级 UI 状态（UI 架构 §4.2）。
 *
 * 持有三个独立维度：
 * - [currentTab]    : 当前 4-Tab 选中（0..3）
 * - [drawerOpen]   : Chat Tab 内 Drawer 是否展开（叠层）
 * - [activeSubRoute]: 当前打开的二级页面路由；非空时 AppShell 隐藏 TabBar
 *
 * 状态变更统一通过方法，避免外部直接 set 破坏一致性。
 */
internal class AppShellState {

    var currentTab: Int by observable(0)

    var drawerOpen: Boolean by observable(false)

    var activeSubRoute: String? by observable(null)

    // ── Tab 切换 ────────────────────────────────────────────────────────
    fun switchTab(index: Int) {
        if (index !in 0..3) return
        currentTab = index
        drawerOpen = false
        activeSubRoute = null
    }

    // ── Drawer 控制（仅 Chat Tab 触发） ────────────────────────────────
    fun openDrawer() {
        drawerOpen = true
    }

    fun closeDrawer() {
        drawerOpen = false
    }

    // ── 二级页面 ───────────────────────────────────────────────────────
    fun openSubPage(route: String) {
        activeSubRoute = route
        drawerOpen = false
    }

    fun closeSubPage() {
        activeSubRoute = null
    }
}