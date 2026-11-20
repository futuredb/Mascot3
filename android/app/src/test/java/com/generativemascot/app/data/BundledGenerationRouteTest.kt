package com.generativemascot.app.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class BundledGenerationRouteTest {
    private val key = "sk-or-v1-test-bundled-key-1234567890"
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearTestSettings() {
        context.getSharedPreferences("openrouter_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun freshTeamInstallUsesTheBundledKeyWithoutManualSetup() {
        val store = OpenRouterSettingsStore(context, key)
        assertEquals(GenerationRoute.OPENROUTER_DIRECT, store.route())
        assertTrue(store.isDirectModeEnabled())
        assertTrue(store.enableBundledRouteForCharacterCreation())
    }

    @Test fun oldServerSettingIsMigratedOnUpdateAndCreationCanUseBundledKey() {
        context.getSharedPreferences("openrouter_settings", Context.MODE_PRIVATE).edit()
            .putString("generation_route", GenerationRoute.TEAM_SERVER.name).commit()
        val store = OpenRouterSettingsStore(context, key)
        assertTrue(store.isDirectModeEnabled())
        store.setRoute(GenerationRoute.TEAM_SERVER)
        assertEquals(GenerationRoute.TEAM_SERVER, store.route())
        assertTrue(store.enableBundledRouteForCharacterCreation())
        assertEquals(GenerationRoute.OPENROUTER_DIRECT, store.route())
    }

    @Test fun buildWithoutAnyKeyDoesNotPretendItCanCreateCharacters() {
        val store = OpenRouterSettingsStore(context, "")
        assertFalse(store.enableBundledRouteForCharacterCreation())
        assertEquals(GenerationRoute.TEAM_SERVER, store.route())
    }
}
