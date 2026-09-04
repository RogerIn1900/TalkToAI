package com.example.talktoai

import android.app.Application

class KRApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        application = this
        // 设计 §6：在 Application.onCreate 阶段尽早 attach context，
        // 让 DshClientHolder 在第一次 ensureConnected() 时能拿到 Context。
        DshClientHolder.attachApplicationContext(this)
    }

    companion object {
        lateinit var application: Application
    }
}