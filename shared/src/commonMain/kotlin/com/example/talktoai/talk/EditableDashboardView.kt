package com.example.talktoai.talk

import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.Event

internal class EditableDashboardView : DeclarativeBaseView<EditableDashboardAttr, Event>() {
    override fun createAttr() = EditableDashboardAttr()

    override fun createEvent() = Event()

    override fun viewName() = "TalkDashboard"
}

internal class EditableDashboardAttr : Attr() {
    fun darkMode(value: Boolean) {
        "darkMode" with value
    }
}

internal fun ViewContainer<*, *>.EditableDashboard(init: EditableDashboardView.() -> Unit) {
    addChild(EditableDashboardView(), init)
}
