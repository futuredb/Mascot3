package com.generativemascot.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.InsetDrawable
import com.generativemascot.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    @Test fun bothLauncherVariantsUseTheSuppliedFullResolutionMaster() {
        val context = RuntimeEnvironment.getApplication()
        for (resource in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = context.getDrawable(resource) as AdaptiveIconDrawable
            val foreground = (icon.foreground as InsetDrawable).drawable as BitmapDrawable
            assertEquals(1024, foreground.bitmap.width)
            assertEquals(1024, foreground.bitmap.height)
            assertTrue(android.graphics.Color.red(foreground.bitmap.getPixel(0, 0)) < 40)
        }
    }

    @Test fun adaptiveIconRendersWithoutTheOldPinkBackground() {
        val context = RuntimeEnvironment.getApplication()
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            icon.setBounds(0, 0, 512, 512)
            icon.draw(Canvas(bitmap))
            assertTrue(android.graphics.Color.alpha(bitmap.getPixel(256, 256)) > 0)
            assertTrue(android.graphics.Color.red(bitmap.getPixel(256, 256)) > 200)
            assertTrue(android.graphics.Color.green(bitmap.getPixel(256, 256)) > 200)
            // Offline visual-QA artifact; never touches a device or starts generation.
            val output = File(System.getProperty("java.io.tmpdir"), "mascots-launcher-icon-preview.png")
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("Launcher icon preview: ${output.absolutePath}")
        } finally { bitmap.recycle() }
    }
}
