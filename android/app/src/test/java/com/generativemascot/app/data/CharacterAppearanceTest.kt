package com.generativemascot.app.data

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CharacterAppearanceTest {
    @Test fun descriptionIsBoundToTheActualImage() {
        val dir = Files.createTempDirectory("mascot-appearance").toFile()
        try {
            val image = dir.resolve("base.png").apply { writeText("original image") }
            assertNull(CharacterAppearance.read(image))
            CharacterAppearance.save(image, "Purple character with a round head")
            assertEquals("Purple character with a round head", CharacterAppearance.read(image))
            image.writeText("a different image")
            assertNull(CharacterAppearance.read(image))
        } finally { dir.deleteRecursively() }
    }
}
