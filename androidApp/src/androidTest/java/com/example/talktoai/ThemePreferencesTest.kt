package com.example.talktoai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.talktoai.chat.AiModels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemePreferencesTest {
    private lateinit var context: Context
    private lateinit var preferences: ThemePreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(PREFERENCES_NAME)
        preferences = ThemePreferences(context)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(PREFERENCES_NAME)
    }

    @Test
    fun selectedAiModelPersistsAndInvalidModelDoesNotReplaceIt() {
        assertEquals(AiModels.HY3, preferences.getAiModel())
        assertTrue(preferences.setAiModel(AiModels.DEEPSEEK_V4_FLASH))
        assertEquals(AiModels.DEEPSEEK_V4_FLASH, ThemePreferences(context).getAiModel())

        assertFalse(preferences.setAiModel("unknown-model"))
        assertEquals(AiModels.DEEPSEEK_V4_FLASH, preferences.getAiModel())
    }

    private companion object {
        const val PREFERENCES_NAME = "talktoai_appearance_v1"
    }
}
