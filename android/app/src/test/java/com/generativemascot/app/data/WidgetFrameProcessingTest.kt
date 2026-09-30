package com.generativemascot.app.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetFrameProcessingTest {
    private fun source(left: Float = 32f, right: Float = 96f): Bitmap =
        Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.GREEN)
            canvas.drawRect(left, 32f, right, 96f, Paint().apply { color = Color.RED })
        }

    @Test fun memoryIsBoundedWithoutReducingTheFrameCount() {
        for (count in 1..96) {
            val side = widgetCacheFrameSide(count)
            assertTrue(side in 256..320)
            assertTrue(count.toLong() * side * side * 4 <= WIDGET_CACHE_BITMAP_BUDGET_BYTES)
        }
        assertTrue(runCatching { widgetCacheFrameSide(0) }.isFailure)
        assertTrue(runCatching { widgetCacheFrameSide(97) }.isFailure)
    }

    @Test fun oneCropContainsExtremeArmPositionsAcrossTheEntireClip() {
        val frames = listOf(source(), source(4f, 96f), source(32f, 124f))
        try {
            val tracker = WidgetCropTracker()
            frames.forEach { frame ->
                val cutout = removeChromaBackground(frame)
                try { tracker.include(cutout) } finally { cutout.recycle() }
            }
            val crop = tracker.finish()
            assertEquals(0, crop.rect(128, 128).left)
            assertEquals(128, crop.rect(128, 128).right)
            frames.forEach { frame ->
                val processed = prepareWidgetFrame(frame, crop, 64)
                try {
                    assertEquals(64, processed.width)
                    assertEquals(64, processed.height)
                    assertTrue(Color.alpha(processed.getPixel(32, 32)) > 240)
                } finally { processed.recycle() }
            }
        } finally { frames.forEach(Bitmap::recycle) }
    }

    @Test fun emptyOrEntirelyKeyedFramesAreNotPublishedAsReady() {
        assertTrue(runCatching { WidgetCropTracker().finish() }.isFailure)
        val empty = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try { assertTrue(runCatching { WidgetCropTracker().include(empty) }.isFailure) }
        finally { empty.recycle() }
    }

    @Test fun characterUsesMorePixelsAndHasNoOpaqueGreenBorder() {
        val frame = source()
        try {
            val tracker = WidgetCropTracker()
            val cutout = removeChromaBackground(frame)
            try { tracker.include(cutout) } finally { cutout.recycle() }
            val crop = tracker.finish()
            assertEquals(.2f, crop.inset, .001f)
            val output = prepareWidgetFrame(frame, crop, 64)
            try {
                assertEquals(64, output.width)
                assertEquals(0, Color.alpha(output.getPixel(0, 0)))
                assertEquals(Color.RED, output.getPixel(32, 32))
                val opaque = (0 until 64).count { Color.alpha(output.getPixel(it, 32)) > 200 }
                assertTrue("More than the old 32px of the character must survive", opaque > 48)
                for (x in 0 until output.width) for (y in 0 until output.height) {
                    val pixel = output.getPixel(x, y)
                    if (Color.alpha(pixel) > 8) assertTrue(Color.green(pixel) <= Color.red(pixel) + 12)
                }
            } finally { output.recycle() }
        } finally { frame.recycle() }
    }

    @Test fun realUnaReferenceKeepsTransparencyAndAllowsAHigherResolutionFrame() {
        val context = RuntimeEnvironment.getApplication()
        val frame = context.assets.open("bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/videos/neutral.png")
            .use(BitmapFactory::decodeStream)
        try {
            val tracker = WidgetCropTracker()
            val cutout = removeChromaBackground(frame)
            try { tracker.include(cutout) } finally { cutout.recycle() }
            val output = prepareWidgetFrame(frame, tracker.finish(), widgetCacheFrameSide(72))
            try {
                assertTrue(maxOf(output.width, output.height) > 160)
                assertTrue(maxOf(output.width, output.height) <= 320)
                assertTrue(output.hasAlpha())
                assertTrue(Color.alpha(output.getPixel(0, 0)) <= 8)
            } finally { output.recycle() }
        } finally { frame.recycle() }
    }
}
