package com.generativemascot.app.ui

import android.app.Application
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdaptiveGreenMatteTest {
    private val shadedGreen = List(72) { Color.rgb(75, 126, 58) }
    private val blueSubject = List(1000) { Color.rgb(28, 104, 180) }

    @Test fun shadedGreenBorderWithBlueSubjectUsesAdaptiveKey() {
        val tuning = chooseGreenMatteTuning(shadedGreen, blueSubject)
        assertTrue(tuning.adaptive)
        assertTrue(tuning.high in .10f.. .14f)
    }

    @Test fun greenSubjectKeepsConservativeKey() {
        val subject = blueSubject.toMutableList().apply {
            repeat(10) { this[it] = Color.rgb(55, 140, 48) }
        }
        assertFalse(chooseGreenMatteTuning(shadedGreen, subject).adaptive)
    }

    @Test fun nonGreenOrSparseBorderDoesNotActivateAdaptiveKey() {
        assertFalse(chooseGreenMatteTuning(List(72) { Color.LTGRAY }, blueSubject).adaptive)
        assertFalse(chooseGreenMatteTuning(shadedGreen.take(4), blueSubject).adaptive)
    }

    @Test fun turquoiseClothingIsNotTreatedAsGreenBackdrop() {
        val turquoise = List(1000) { Color.rgb(50, 160, 143) }
        assertTrue(chooseGreenMatteTuning(shadedGreen, turquoise).adaptive)
    }
}
