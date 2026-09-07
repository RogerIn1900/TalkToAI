package com.example.talktoai

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class ThemeMode(val wireName: String, val delegateMode: Int) {
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    ;

    fun next(): ThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }
}

class ThemePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun get(): ThemeMode = ThemeMode.entries.firstOrNull {
        it.wireName == preferences.getString(KEY_MODE, ThemeMode.SYSTEM.wireName)
    } ?: ThemeMode.SYSTEM

    fun set(mode: ThemeMode) {
        check(preferences.edit().putString(KEY_MODE, mode.wireName).commit()) {
            "Failed to persist theme mode"
        }
        AppCompatDelegate.setDefaultNightMode(mode.delegateMode)
    }

    companion object {
        private const val PREFERENCES_NAME = "talktoai_appearance_v1"
        private const val KEY_MODE = "theme_mode"
    }
}
