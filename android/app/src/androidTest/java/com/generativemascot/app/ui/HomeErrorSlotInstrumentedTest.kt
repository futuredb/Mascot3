package com.generativemascot.app.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeErrorSlotInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun generationErrorUsesTheQuoteSlotAndLeavesChatAvailable() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val error = mutableStateOf<String?>("Не удалось восстановить плавный стык ролика.")
        val launch = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MascotTheme {
                        KnockHeroScreen(
                            generating = false,
                            previewUrl = null,
                            error = error.value,
                            accepted = true,
                            screenActive = false,
                            onKnockComplete = {},
                            onAccept = {},
                            onPoll = {},
                            acceptEnabled = false,
                        )
                    }
                }
            }
            compose.onNodeWithTag("hero-error").assertIsDisplayed()
            compose.onNodeWithText("ЧАТ").assertIsDisplayed()
            compose.onNodeWithTag("hero-quote").assertDoesNotExist()
            compose.runOnIdle { error.value = null }
            compose.onNodeWithTag("hero-error").assertDoesNotExist()
            compose.onNodeWithTag("hero-quote").assertIsDisplayed()
        }
    }
}
