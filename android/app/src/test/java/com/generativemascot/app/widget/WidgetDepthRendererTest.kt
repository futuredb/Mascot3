package com.generativemascot.app.widget

import android.app.Application
import android.graphics.Bitmap
import com.generativemascot.app.physics.InstalledPhysics
import com.generativemascot.app.physics.PhoneSample
import com.generativemascot.app.physics.PhysicsPack
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetDepthRendererTest {
    @Test fun approvedHeroProducesTransparentAndTiltResponsiveWidgetFrames() {
        val context = RuntimeEnvironment.getApplication()
        val folder = "bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/physics"
        val revision = context.assets.open("$folder/active").bufferedReader().use { it.readText().trim() }
        val manifest = context.assets.open("$folder/$revision/manifest.json").bufferedReader().use { it.readText() }
        val pack = Json { ignoreUnknownKeys = true }.decodeFromString<PhysicsPack>(manifest)
        val root = File(context.cacheDir, "widget-depth-preview").apply { mkdirs() }
        pack.assets.values.forEach { asset ->
            context.assets.open("$folder/$revision/${asset.file}").use { input -> File(root, asset.file).outputStream().use(input::copyTo) }
        }
        WidgetDepthRenderer(InstalledPhysics(pack, root)).use { renderer ->
            val neutral = renderer.render(256, .016, PhoneSample())
            repeat(60) { renderer.render(256, .016, PhoneSample(tiltX = .18f, tiltY = .12f)).recycle() }
            val tilted = renderer.render(256, .016, PhoneSample(tiltX = .18f, tiltY = .12f))
            try {
                val a = IntArray(256 * 256); val b = IntArray(a.size)
                neutral.getPixels(a, 0, 256, 0, 0, 256, 256); tilted.getPixels(b, 0, 256, 0, 0, 256, 256)
                assertTrue("character must be visible", a.count { it ushr 24 > 8 } > 1000)
                assertTrue("background must be transparent", a.count { it ushr 24 == 0 } > 1000)
                assertTrue("tilt must move the depth mesh", a.indices.count { a[it] != b[it] } > 500)
                System.getenv("MASCOT3_DEPTH_WIDGET_OUTPUT")?.let { output ->
                    File(output).mkdirs()
                    File(output, "widget-neutral.png").outputStream().use { neutral.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(output, "widget-tilted.png").outputStream().use { tilted.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { neutral.recycle(); tilted.recycle() }
        }
    }
}
