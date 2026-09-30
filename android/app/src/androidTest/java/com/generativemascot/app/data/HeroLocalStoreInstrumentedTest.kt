package com.generativemascot.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeroLocalStoreInstrumentedTest {
    @Test
    fun opaqueChromaBaseIsCutOutBeforeQaAndPersistence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mascotId = "instrumented-base-${System.nanoTime()}"
        val mascotRoot = File(context.filesDir, "hero/$mascotId")
        try {
            val source = Bitmap.createBitmap(1024, 1536, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(source)
            canvas.drawColor(Color.rgb(0, 255, 0))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 45, 190) }
            canvas.drawRoundRect(RectF(280f, 360f, 744f, 1_176f), 180f, 180f, paint)
            val bytes = ByteArrayOutputStream().use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            source.recycle()

            val saved = HeroLocalStore(context).saveGeneratedBase(mascotId, bytes)
            val result = BitmapFactory.decodeFile(saved.absolutePath)
            try {
                assertTrue(Color.alpha(result.getPixel(0, 0)) <= 16)
                assertTrue(Color.alpha(result.getPixel(result.width / 2, result.height / 2)) >= 200)
            } finally {
                result.recycle()
            }
        } finally {
            mascotRoot.deleteRecursively()
        }
    }

    @Test
    fun existingVideoCanBecomeTransparentWidgetFramesWithoutNetwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val heroRoot = File(context.filesDir, "hero")
        val mascot = heroRoot.listFiles(File::isDirectory)
            ?.firstOrNull { File(it, "videos/idle.mp4").length() > 1_024L }
        assumeTrue("This device has no generated mascot video", mascot != null)

        val animation = HeroLocalStore(context).widgetAnimation(mascot!!.name, "content")
        val frames = animation.files

        assertTrue(frames.size in HeroLocalStore.WIDGET_MIN_FRAME_COUNT..HeroLocalStore.WIDGET_MAX_FRAME_COUNT)
        assertTrue(animation.frameIntervalMs in 100..250)
        assertTrue(frames.all { it.isFile && it.length() > 100L })
        val sample = BitmapFactory.decodeFile(frames.first().absolutePath)
        try {
            assertTrue(maxOf(sample.width, sample.height) <= HeroLocalStore.WIDGET_FRAME_MAX_SIDE)
            var transparentPixels = 0
            var opaquePixels = 0
            for (y in 0 until sample.height step 4) for (x in 0 until sample.width step 4) {
                if (Color.alpha(sample.getPixel(x, y)) <= 8) transparentPixels += 1
                if (Color.alpha(sample.getPixel(x, y)) >= 200) opaquePixels += 1
            }
            assertTrue("The chroma background must not reach the widget", transparentPixels > 0)
            assertTrue("The mascot must remain visible after chroma removal", opaquePixels > 0)
            assertTrue(Color.alpha(sample.getPixel(0, 0)) <= 8)
            assertTrue(Color.alpha(sample.getPixel(sample.width - 1, sample.height - 1)) <= 8)
        } finally {
            sample.recycle()
        }
    }

    @Test
    fun puppetSheetIsCutOutAndInstalledLocally() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mascotId = "instrumented-puppet-${System.nanoTime()}"
        val mascotRoot = File(context.filesDir, "hero/$mascotId")
        try {
            val sheet = Bitmap.createBitmap(1024, 1536, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(0, 255, 0))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            HeroLocalStore.PUPPET_ROLES.forEachIndexed { index, _ ->
                val column = index % 3
                val row = index / 3
                val left = column * sheet.width / 3f
                val top = row * sheet.height / 5f
                val cellWidth = sheet.width / 3f
                val cellHeight = sheet.height / 5f
                paint.color = Color.rgb(90 + index * 6, 35 + index * 3, 180 - index * 4)
                canvas.drawRoundRect(
                    RectF(
                        left + cellWidth * .25f,
                        top + cellHeight * .25f,
                        left + cellWidth * .75f,
                        top + cellHeight * .75f,
                    ),
                    28f,
                    28f,
                    paint,
                )
            }
            val bytes = ByteArrayOutputStream().use { output ->
                assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            sheet.recycle()

            val store = HeroLocalStore(context)
            store.savePuppetSheet(mascotId, bytes)

            assertTrue(store.puppetPackReady(mascotId))
            val parts = store.puppetPartFiles(mascotId)
            assertEquals(HeroLocalStore.PUPPET_ROLES.toSet(), parts.keys)
            assertTrue(parts.values.all { it.isFile && it.length() > 100 })
        } finally {
            mascotRoot.deleteRecursively()
        }
    }
}
