package com.generativemascot.app.data

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimationReferenceInstrumentedTest {
    @Test fun importedHeroKeepsItsObservedIdentity() {
        val appearance = "A lavender figure in an oversized coat holding a small red book."
        val seed = "9f226b89-df35-4d3a-9b36-96c28ea1bb62".hashCode()
        val hero = referenceAnimationCharacter(seed, null, appearance)
        val prompt = buildCharacterAnimationPrompt("resting", seed, character = hero)
        validateResolvedVideoPrompt(prompt, "#00FF00")
        assertTrue(prompt.contains(appearance))
        assertTrue(prompt.contains("Preserve its existing outfit and held objects throughout"))
        assertFalse(prompt.contains(mobileCharacterContract(seed).body.subject))
    }

    @Test fun onlyRepairableSeamsCanBeTrimmed() {
        val seam = VideoLoopSeam(.009, .006, .002, .004, .081, .007)
        fun quality(candidate: VideoLoopSeam) = evaluateVideoQa(
            action = "resting", expectedDurationSeconds = 6, durationMs = 6592,
            width = 768, height = 768, sampleCount = 5, minimumBorderKeyRatio = 1.0,
            minimumFrameMargin = .21, endpointDelta = .01, matteHex = "#00FF00",
            enforceLoop = true, loopSeam = candidate,
        )
        assertTrue(VideoLoopRepair.canRepair(quality(seam)))
        assertFalse(VideoLoopRepair.canRepair(quality(seam.copy(peakEdgeStepDelta = .212))))
        assertFalse(VideoLoopRepair.canRepair(quality(seam).let { it.copy(blockingIssues = it.blockingIssues + "Другой дефект") }))
    }
    @Test fun shortEdgeJumpIsRepairedAndCheckedAgainOnDevice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val source = File.createTempFile("loop-single-jump-", ".mp4", app.cacheDir)
        try {
            instrumentation.context.assets.open("loop-single-jump.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val before = CharacterAppearance.imageHash(source)
            val qa = VideoQualityGate.inspect(source, "resting", 6, Color.GREEN, enforceLoop = true)
            assertTrue(qa.blockingIssues.toString(), VideoLoopRepair.canRepair(qa))
            val repaired = VideoLoopRepair.repair(app, source, qa, 6)
            checkNotNull(repaired) { "No passing trim was exported" }
            assertTrue(repaired.qa.hardPass)
            assertTrue(repaired.qa.durationMs < qa.durationMs)
            assertTrue(before == CharacterAppearance.imageHash(source))
        } finally {
            source.delete()
        }
        Unit
    }

}
