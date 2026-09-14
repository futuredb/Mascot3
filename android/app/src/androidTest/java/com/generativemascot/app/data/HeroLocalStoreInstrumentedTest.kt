package com.generativemascot.app.data

import android.content.Context
import android.graphics.Bitmap
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeroLocalStoreInstrumentedTest {
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
