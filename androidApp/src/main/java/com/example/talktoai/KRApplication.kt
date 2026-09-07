package com.example.talktoai

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class KRApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        application = this
        AppCompatDelegate.setDefaultNightMode(ThemePreferences(this).get().delegateMode)
    }

    companion object {
        lateinit var application: Application
    }
}
