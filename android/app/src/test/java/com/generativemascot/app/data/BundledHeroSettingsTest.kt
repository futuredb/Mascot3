package com.generativemascot.app.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BundledHeroSettingsTest {
    @Test fun importsSelectedStyleAndModeWithoutPrivateReferenceOrOverwritingUserChanges() {
        val context: Context = RuntimeEnvironment.getApplication()
        val heroId = "settings-test-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, heroId).apply { mkdirs() }
        val choices = context.getSharedPreferences("hero_character_choices", Context.MODE_PRIVATE)
        val interactions = context.getSharedPreferences("mascot_chat_interaction", Context.MODE_PRIVATE)
        try {
            File(directory, "team-settings.json").writeText("""{"choice":{"type":"animal","style":"friendly","x":0.2,"y":0.8,"reference":"private-photo.jpg"},"interaction":"proactive_guidance"}""")
            BundledHeroSettings.importIfAbsent(context, heroId, directory)
            val saved = requireNotNull(HeroLocalStore(context).characterChoice(heroId))
            assertEquals("animal", saved.type); assertEquals("friendly", saved.style)
            assertEquals(.2f, saved.positionX!!, .001f); assertEquals(.8f, saved.positionY!!, .001f)
            assertNull(saved.referencePath)
            assertEquals("proactive_guidance", interactions.getString(heroId, null))
            val updated = CharacterCreationChoice(type = "person", style = "expert", personalityPrompt = "Спокойный")
            HeroLocalStore(context).saveCharacterChoice(heroId, updated)
            interactions.edit().putString(heroId, "request_guidance").commit()
            BundledHeroSettings.importIfAbsent(context, heroId, directory)
            assertEquals(updated, HeroLocalStore(context).characterChoice(heroId))
            assertEquals("request_guidance", interactions.getString(heroId, null))
        } finally {
            listOf("type", "style", "reference", "x", "y", "personalityPrompt").forEach { choices.edit().remove("$heroId:$it").commit() }
            interactions.edit().remove(heroId).commit()
            directory.deleteRecursively()
        }
    }
}
