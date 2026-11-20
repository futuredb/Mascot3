package com.generativemascot.app.data

import android.app.Application
import android.content.Context
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HeroRecoveryExporterTest {
    @Test fun preservesChatAnimationsSettingsAndReferencesWithoutCredentials() {
        val context: Context = RuntimeEnvironment.getApplication()
        val id = "recovery-test-${UUID.randomUUID()}"
        val hero = File(context.filesDir, "hero/$id").apply { mkdirs() }
        fun put(relative: String, content: String) = File(hero, relative).apply { parentFile!!.mkdirs(); writeText(content) }
        put("base.png", "base"); put("name.txt", "name")
        put("chat-animations/abc.mp4", "video"); put("chat-animations/abc.json", "metadata")
        val image = File(context.filesDir, "chat-images/${UUID.randomUUID()}.jpg").apply { parentFile!!.mkdirs(); writeText("photo") }
        val reference = File(context.filesDir, "hero-references/${UUID.randomUUID()}.png").apply { parentFile!!.mkdirs(); writeText("reference") }
        val choices = context.getSharedPreferences("hero_character_choices", Context.MODE_PRIVATE)
        val histories = context.getSharedPreferences("mascot_chat_history", Context.MODE_PRIVATE)
        choices.edit().putString("$id:type", "person").putString("$id:reference", reference.path).commit()
        histories.edit().putString(id, "[{\"user\":true,\"text\":\"private\",\"imagePath\":\"${image.path}\"}]").commit()
        val output = HeroRecoveryExporter(context).export().file
        try {
            ZipFile(output).use { zip ->
                assertNotNull(zip.getEntry("recovery/hero/$id/chat-animations/abc.mp4"))
                assertNotNull(zip.getEntry("recovery/hero/$id/chat-animations/abc.json"))
                assertNotNull(zip.getEntry("recovery/chat-images/${image.name}"))
                assertNotNull(zip.getEntry("recovery/files/hero-references/${reference.name}"))
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("recovery/manifest.json")).bufferedReader().readText())
                assertTrue(manifest.getJSONObject("settings").getJSONObject(id).getString("chatHistory").contains("private"))
                assertEquals("recovery/chat-images/${image.name}", manifest.getJSONObject("imagePaths").getString(image.path))
                assertEquals("recovery/files/hero-references/${reference.name}", manifest.getJSONObject("imagePaths").getString(reference.path))
                assertFalse(zip.entries().asSequence().any { it.name.contains("openrouter-key") || it.name.contains("reactor-key") })
            }
        } finally {
            output.delete(); hero.deleteRecursively(); image.delete(); reference.delete()
            choices.edit().remove("$id:type").remove("$id:reference").commit()
            histories.edit().remove(id).commit()
        }
    }
}
