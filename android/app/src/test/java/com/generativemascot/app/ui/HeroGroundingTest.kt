package com.generativemascot.app.ui

import android.app.Application
import android.graphics.*
import com.generativemascot.app.physics.PhysicsFraming
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeroGroundingTest {
    private fun subject(background: Int, bottom: Float = 360f): Bitmap =
        Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(background)
            Canvas(it).drawRect(180f, 100f, 330f, bottom, Paint().apply { color = Color.RED })
        }

    @Test fun transparentPaddingDoesNotLiftFeet() {
        val bitmap = subject(Color.TRANSPARENT)
        val anchor = requireNotNull(detectHeroGrounding(bitmap))
        assertEquals(360, anchor.bottom)
        // Rasterize at two actual layout sizes; the last opaque row meets the floor.
        for ((w, h) in listOf(342 to 396, 290 to 250)) {
            val rendered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val scale = minOf(w / 512f, h / 512f)
            val dy = anchor.translationY(w.toFloat(), h.toFloat())
            Canvas(rendered).drawBitmap(bitmap, null, RectF((w - 512 * scale) / 2,
                (h - 512 * scale) / 2 + dy, (w + 512 * scale) / 2, (h + 512 * scale) / 2 + dy), null)
            val bottom = (0 until h).last { y -> (0 until w).any { x -> Color.alpha(rendered.getPixel(x, y)) >= 160 } } + 1
            assertEquals(h / 2f + minOf(w, h) * PhysicsFraming.GROUND_OFFSET, bottom.toFloat(), 1.1f)
        }
    }

    @Test fun opaqueNeutralAndTransparentCutoutUseTheSameFeet() {
        for (background in listOf(Color.TRANSPARENT, Color.GREEN, Color.rgb(232, 244, 252))) {
            assertEquals(360, requireNotNull(detectHeroGrounding(subject(background))).bottom)
        }
    }

    @Test fun portraitArtworkAndCroppedVideoHaveMatchingVisibleSizeAndFloor() {
        fun image(width: Int, height: Int, background: Int, rect: RectF): Bitmap =
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(background)
                Canvas(it).drawRect(rect, Paint().apply { color = Color.RED })
            }
        val art = requireNotNull(detectHeroGrounding(image(1024, 1536, Color.TRANSPARENT, RectF(273f, 121f, 778f, 1440f)))).bounds()
        val video = requireNotNull(detectHeroGrounding(image(720, 720, Color.GREEN, RectF(286f, 167f, 434f, 554f)))).bounds()
        val width = 342f; val height = 396f
        fun displayedHeight(bounds: com.generativemascot.app.physics.SubjectBounds): Float =
            (bounds.bottom - bounds.top) * minOf(width / bounds.width, height / bounds.height) * bounds.fit(width, height).scale
        assertEquals(displayedHeight(art), displayedHeight(video), .1f)
        fun floor(bounds: com.generativemascot.app.physics.SubjectBounds): Float =
            height / 2 + (bounds.bottom - bounds.height / 2f) * minOf(width / bounds.width, height / bounds.height) * bounds.fit(width, height).scale + bounds.fit(width, height).translationY
        assertEquals(floor(art), floor(video), .1f)
    }

    @Test fun faintShadowAndIsolatedNoiseDoNotMoveTheFloor() {
        val bitmap = subject(Color.TRANSPARENT)
        Canvas(bitmap).drawRect(100f, 390f, 400f, 420f, Paint().apply { color = 0x33000000 })
        bitmap.setPixel(256, 490, Color.BLACK)
        assertEquals(360, requireNotNull(detectHeroGrounding(bitmap)).bottom)
    }

    @Test fun blankFrameHasNoFalseAnchor() {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        assertNull(detectHeroGrounding(bitmap))
        bitmap.eraseColor(Color.GREEN)
        assertNull(detectHeroGrounding(bitmap))
    }

    @Test fun rectangularSourceKeepsFeetOnTheSameFloorAfterResize() {
        for ((sw, sh, foot) in listOf(Triple(720, 1280, 900), Triple(1280, 720, 500))) {
            val anchor = HeroGrounding(sw, sh, foot)
            for ((w, h) in listOf(342f to 396f, 600f to 300f)) {
                val scale = minOf(w / sw, h / sh)
                val actual = (h - sh * scale) / 2 + foot * scale + anchor.translationY(w, h)
                assertEquals(h / 2 + minOf(w, h) * PhysicsFraming.GROUND_OFFSET, actual, .01f)
            }
        }
    }

    @Test fun neutralAnchorPreservesAuthoredJump() {
        val anchor = requireNotNull(detectHeroGrounding(subject(Color.GREEN)))
        val grounded = anchor.bottom + anchor.translationY(512f, 512f)
        val jumping = 320 + anchor.translationY(512f, 512f)
        assertEquals(40f, grounded - jumping, .01f)
    }

    @Test fun previewArtworkAndVideoReferenceAtTheSameSize() {
        val output = System.getenv("MASCOT3_FRAMING_OUTPUT")?.let(::File) ?: return
        val fixtures = listOf(File("output/grounding-0.1.95"), File("../output/grounding-0.1.95"), File("../../output/grounding-0.1.95"))
            .first { it.isDirectory }
        output.mkdirs()
        val width = 342f; val height = 396f
        val montage = Bitmap.createBitmap(720, 860, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(montage)
        canvas.drawColor(Color.rgb(225, 234, 244))
        for (row in 0..1) {
            for ((column, kind) in listOf("base", "neutral").withIndex()) {
                val source = requireNotNull(BitmapFactory.decodeFile(File(fixtures, "new-${row + 1}-$kind.png").path))
                val bounds = requireNotNull(detectHeroGrounding(source)).bounds()
                val image = if (kind == "neutral") keyBitmap(source, source.getPixel(2, 2)) else source
                val fit = minOf(width / source.width, height / source.height)
                val framing = bounds.fit(width, height)
                val left = column * 360f + 9f
                val top = row * 430f + 30f
                val centerX = left + width / 2 + framing.translationX
                val centerY = top + height / 2 + framing.translationY
                val scaledWidth = source.width * fit * framing.scale
                val scaledHeight = source.height * fit * framing.scale
                val floor = top + height / 2 + minOf(width, height) * PhysicsFraming.GROUND_OFFSET
                canvas.drawLine(left, floor, left + width, floor, Paint().apply { color = Color.BLUE; strokeWidth = 2f })
                canvas.drawBitmap(image, null, RectF(centerX - scaledWidth / 2, centerY - scaledHeight / 2,
                    centerX + scaledWidth / 2, centerY + scaledHeight / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.drawText(if (column == 0) "Depth artwork" else "Video reference", left, top - 8,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 16f })
            }
        }
        File(output, "comparison.png").outputStream().use { montage.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun previewExistingArtworkAndExtraPadding() {
        val output = System.getenv("MASCOT3_GROUNDING_OUTPUT")?.let(::File) ?: return
        val assets = File("src/main/assets").let { if (it.isDirectory) it else File("app/src/main/assets") }
        val paths = listOf(
            "Уна" to File(assets, "bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/videos/neutral.png"),
            "Аким" to File(assets, "bundled-heroes/9f226b89-df35-4d3a-9b36-96c28ea1bb62/base.png"),
            "Марс" to File(assets, "bundled-heroes/a83e9d30-1270-40e6-902c-b44774e84c17/base.png"),
            File(output, "new-1-name.txt").readText().trim() to File(output, "new-1-neutral.png"),
            File(output, "new-2-name.txt").readText().trim() to File(output, "new-2-neutral.png"),
        )
        val montage = Bitmap.createBitmap(1200, paths.size * 430, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(montage)
        canvas.drawColor(Color.rgb(231, 229, 243))
        paths.forEachIndexed { index, (name, file) ->
            val original = requireNotNull(BitmapFactory.decodeFile(file.path))
            val border = original.getPixel(2, 2)
            val source = if (Color.alpha(border) < 16) original else keyBitmap(original, border)
            val padded = Bitmap.createBitmap(source.width * 2, source.height * 2, Bitmap.Config.ARGB_8888)
            Canvas(padded).drawBitmap(source, source.width * .5f, source.height * .5f, null)
            for ((column, bitmap) in listOf(source, source, padded).withIndex()) {
                val anchor = requireNotNull(detectHeroGrounding(bitmap))
                val scale = minOf(342f / bitmap.width, 340f / bitmap.height)
                val dy = if (column == 0) 0f else anchor.translationY(342f, 340f)
                val left = column * 400f + 29f
                val top = index * 430f + 48f
                val floor = top + 170f + 340f * PhysicsFraming.GROUND_OFFSET
                canvas.drawLine(left, floor, left + 342, floor, Paint().apply { color = Color.rgb(110, 100, 150); strokeWidth = 1f })
                val bw = bitmap.width * scale; val bh = bitmap.height * scale
                canvas.drawBitmap(bitmap, null, RectF(left + (342 - bw) / 2, top + (340 - bh) / 2 + dy,
                    left + (342 + bw) / 2, top + (340 + bh) / 2 + dy), Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.drawText("$name · ${listOf("исходный кадр", "по линии пола", "большие поля")[column]}", left, top - 15,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 18f })
            }
        }
        File(output, "comparison.png").outputStream().use { montage.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
