package com.generativemascot.app.data

import android.content.Context
import android.app.Application
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HeroArchiveExporterTest {
    @Test fun excludesChatAndReferencesButIncludesHeroAndActiveMap() {
        val context: Context = RuntimeEnvironment.getApplication()
        val id = "export-test-${UUID.randomUUID()}"
        val hero = File(context.filesDir, "hero/$id").apply { mkdirs() }
        val revision = "a".repeat(64)
        fun put(relative: String, content: String) = File(hero, relative).apply {
            parentFile!!.mkdirs(); writeText(content)
        }
        put("base.png", "base"); put("name.txt", "hero")
        put("videos/idle.mp4", "video")
        put("physics/active", revision); put("physics/$revision/manifest.json", "manifest")
        put("physics/$revision/body_depth.png", "depth")
        put("chat-animations/secret.json", "private conversation")
        put("reference.jpg", "private source photo")
        val output = HeroArchiveExporter(context).export().file
        try {
            ZipFile(output).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.filter { id in it }.toSet()
                assertTrue(names.contains("bundled-heroes/$id/base.png"))
                assertTrue(names.contains("bundled-heroes/$id/videos/idle.mp4"))
                assertTrue(names.contains("bundled-heroes/$id/physics/$revision/body_depth.png"))
                assertFalse(names.any { "chat-animations" in it || "reference" in it })
            }
        } finally { output.delete(); hero.deleteRecursively() }
    }
}
