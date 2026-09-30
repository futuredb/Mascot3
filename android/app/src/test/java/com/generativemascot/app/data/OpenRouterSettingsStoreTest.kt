package com.generativemascot.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterSettingsStoreTest {
    @Test
    fun keyIsTrimmedButNeverOtherwiseChanged() {
        val key = "sk-or-v1-1234567890abcdefghijklmnop"
        assertEquals(key, normalizeOpenRouterApiKey("  $key\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAKeyFromAnotherProvider() {
        normalizeOpenRouterApiKey("sk-not-openrouter-12345678901234567890")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWhitespaceInsideAKey() {
        normalizeOpenRouterApiKey("sk-or-v1-1234567890 abcdefghijklmnop")
    }
}
