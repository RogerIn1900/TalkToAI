package com.example.talktoai.chat

import android.content.Context
import java.util.UUID

class InstallationIdentity(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun get(): String {
        preferences.getString(KEY_ID, null)?.let { return it }
        val value = "android_${UUID.randomUUID()}"
        check(preferences.edit().putString(KEY_ID, value).commit()) { "Failed to persist installation identity" }
        return value
    }

    companion object {
        private const val PREFERENCES_NAME = "talktoai_identity_v1"
        private const val KEY_ID = "installation_id"
    }
}
