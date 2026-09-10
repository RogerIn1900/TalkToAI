package com.example.talktoai

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.example.talktoai.chat.AiModels

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

    fun getBubbleStyle(): String = preferences.getString(KEY_BUBBLE_STYLE, BUBBLE_STYLE_SOFT)
        ?.takeIf(ALLOWED_BUBBLE_STYLES::contains) ?: BUBBLE_STYLE_SOFT

    fun getAvatarStyle(): String = preferences.getString(KEY_AVATAR_STYLE, AVATAR_STYLE_TEXT)
        ?.takeIf(ALLOWED_AVATAR_STYLES::contains) ?: AVATAR_STYLE_TEXT

    fun getAiModel(): String = preferences.getString(KEY_AI_MODEL, AiModels.DEFAULT)
        ?.takeIf(AiModels.allowed::contains) ?: AiModels.DEFAULT

    fun set(mode: ThemeMode, resumeDestination: String? = null) {
        if (mode == get()) return
        val editor = preferences.edit().putString(KEY_MODE, mode.wireName)
        if (resumeDestination == RESUME_APPEARANCE) {
            editor.putString(KEY_RESUME_DESTINATION, RESUME_APPEARANCE)
        }
        check(editor.commit()) {
            "Failed to persist theme mode"
        }
        AppCompatDelegate.setDefaultNightMode(mode.delegateMode)
    }

    fun setAppearance(bubbleStyle: String?, avatarStyle: String?) {
        val editor = preferences.edit()
        bubbleStyle?.takeIf(ALLOWED_BUBBLE_STYLES::contains)?.let {
            editor.putString(KEY_BUBBLE_STYLE, it)
        }
        avatarStyle?.takeIf(ALLOWED_AVATAR_STYLES::contains)?.let {
            editor.putString(KEY_AVATAR_STYLE, it)
        }
        editor.apply()
    }

    fun setAiModel(model: String): Boolean {
        if (model !in AiModels.allowed) return false
        preferences.edit().putString(KEY_AI_MODEL, model).apply()
        return true
    }

    fun consumeResumeDestination(): String {
        val destination = preferences.getString(KEY_RESUME_DESTINATION, "").orEmpty()
        if (destination.isNotEmpty()) preferences.edit().remove(KEY_RESUME_DESTINATION).apply()
        return destination
    }

    companion object {
        private const val PREFERENCES_NAME = "talktoai_appearance_v1"
        private const val KEY_MODE = "theme_mode"
        private const val KEY_BUBBLE_STYLE = "bubble_style"
        private const val KEY_AVATAR_STYLE = "avatar_style"
        private const val KEY_RESUME_DESTINATION = "resume_destination"
        private const val KEY_AI_MODEL = "ai_model"
        private const val BUBBLE_STYLE_SOFT = "soft"
        private const val AVATAR_STYLE_TEXT = "text"
        const val RESUME_APPEARANCE = "appearance"
        private val ALLOWED_BUBBLE_STYLES = setOf("soft", "outline", "compact")
        private val ALLOWED_AVATAR_STYLES = setOf("text", "round", "minimal")
    }
}
