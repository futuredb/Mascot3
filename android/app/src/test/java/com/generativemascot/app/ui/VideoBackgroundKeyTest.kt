package com.generativemascot.app.ui

import android.graphics.Bitmap
import android.app.Application
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class VideoBackgroundKeyTest {
    @Test fun compressedAndShadedGreenDisappear() {
        for(color in listOf(Color.GREEN,Color.rgb(0,140,0),Color.rgb(20,110,25))) {
            assertEquals(Color.TRANSPARENT,keyedPixel(color,Color.GREEN))
        }
    }
    @Test fun mixedEdgeRetainsPartialAlphaAndLosesGreenSpill() {
        val edge=keyedPixel(Color.rgb(120,150,120),Color.GREEN)
        assertTrue(Color.alpha(edge) in 1..254)
        assertTrue(Color.green(edge)<150)
        assertEquals(120,Color.red(edge));assertEquals(120,Color.blue(edge))
    }
    @Test fun foregroundColorsAndExistingTransparencySurvive() {
        for(color in listOf(Color.rgb(190,30,70),Color.WHITE,Color.argb(120,60,70,150))) {
            assertEquals(color,keyedPixel(color,Color.GREEN))
        }
        assertEquals(Color.TRANSPARENT,keyedPixel(Color.argb(0,255,0,0),Color.GREEN))
    }
    @Test fun neutralFrameOutputIsTransparentWithoutChangingTheSource() {
        val original=Bitmap.createBitmap(intArrayOf(Color.GREEN,Color.RED),2,1,Bitmap.Config.ARGB_8888)
        val keyed=keyBitmap(original,Color.GREEN)
        assertEquals(Color.GREEN,original.getPixel(0,0))
        assertEquals(Color.TRANSPARENT,keyed.getPixel(0,0))
        assertEquals(Color.RED,keyed.getPixel(1,0))
        val gray=Color.rgb(232,244,252)
        assertEquals(Color.TRANSPARENT,keyedPixel(gray,gray))
        assertEquals(Color.RED,keyedPixel(Color.RED,gray))
        original.recycle();keyed.recycle()
    }
}
