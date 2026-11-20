package com.generativemascot.app.widget

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.generativemascot.app.physics.InstalledPhysics
import com.generativemascot.app.physics.PhoneSample
import com.generativemascot.app.physics.PhysicsPack
import com.generativemascot.app.data.WidgetAnimationFrames
import com.generativemascot.app.ui.LoadedVideoLighting
import com.generativemascot.app.ui.VideoLightingManifest
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
    @Test fun videoReactionUsesFrameNormalsAndPreservesAlpha() {
        val page = Bitmap.createBitmap(1024, 128, Bitmap.Config.ARGB_8888)
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            page.eraseColor(Color.rgb(230, 128, 204))
            source.eraseColor(Color.argb(180, 120, 100, 80))
            val manifest = VideoLightingManifest("mascot-video-lighting", 1, "a".repeat(64), 1,
                128, 128, 128, 8, 64, listOf(0L, 250_000L), listOf("normals-000.png"), listOf("b".repeat(64)))
            val file = File("frame_01.png")
            val metadata = WidgetAnimationFrames(listOf(file), 250, true, cropInset = .1f,
                sourceDurationUs = 1_000_000L, sourceFrameCount = 4)
            val before = source.getPixel(16, 16)
            shadeReaction(source, file, metadata, LoadedVideoLighting(manifest, listOf(page)), .15f, 0f)
            val after = source.getPixel(16, 16)
            assertTrue("video normals must change reaction lighting", Color.red(after) < Color.red(before))
            assertTrue("video lighting must preserve opacity", Color.alpha(after) == Color.alpha(before))
        } finally { page.recycle(); source.recycle() }
    }
    @Test fun directionalLightingUsesSurfaceNormalAndPreservesNeutralArtwork() {
        val neutral = WidgetReliefLight(0f, 0f, 0f, 0f, 0f)
        assertTrue(neutral.color(.8f, 0f, .6f) == Color.WHITE)
        val left = WidgetReliefLight(0f, 0f, 0f, .15f, 0f)
        val right = WidgetReliefLight(0f, 0f, 0f, -.15f, 0f)
        val leftFacing = left.color(.8f, 0f, .6f)
        val rightFacing = right.color(.8f, 0f, .6f)
        assertTrue("opposite tilts must shade the surface differently", leftFacing != rightFacing)
        assertTrue("vertex modulation must not change alpha", Color.alpha(leftFacing) == 255)
        assertTrue("a flat surface keeps its original color", left.color(0f, 0f, 1f) == Color.WHITE)
    }
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
            WidgetDepthRenderer(InstalledPhysics(pack, root)).use { reference ->
                val tilt = PhoneSample(tiltX = .18f, tiltY = .12f)
                repeat(35) {
                    renderer.render(256, .016, tilt).recycle()
                    reference.render(256, .016, tilt, lightingEnabled = false).recycle()
                }
                val lit = renderer.render(256, .016, tilt)
                val unlit = reference.render(256, .016, tilt, lightingEnabled = false)
                try {
                    val a = IntArray(256 * 256); val b = IntArray(a.size)
                    lit.getPixels(a, 0, 256, 0, 0, 256, 256); unlit.getPixels(b, 0, 256, 0, 0, 256, 256)
                    assertTrue("light must change pixels even at identical geometry", a.indices.count { a[it] != b[it] } > 100)
                } finally { lit.recycle(); unlit.recycle() }
            }
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
