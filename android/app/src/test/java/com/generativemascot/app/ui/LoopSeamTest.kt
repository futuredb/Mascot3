package com.generativemascot.app.ui

import android.graphics.Color
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class LoopSeamTest {
    private val green = Color.rgb(0, 255, 0)
    private val character = Color.rgb(100, 80, 70)

    @Test fun matchingCharacterIgnoresSmallBackgroundVariation() {
        val first = IntArray(100) { if (it < 30) character else green }
        val last = IntArray(100) { if (it < 30) character else Color.rgb(3, 250, 2) }
        assertTrue(loopFramesMatch(first, last, green))
    }

    @Test fun differentPoseAndMissingSubjectCannotBeHardSwapped() {
        val first = IntArray(100) { if (it < 30) character else green }
        val moved = IntArray(100) { if (it in 15 until 45) character else green }
        assertFalse(loopFramesMatch(first, moved, green))
        assertFalse(loopFramesMatch(IntArray(100) { green }, IntArray(100) { green }, green))
        assertFalse(loopFramesMatch(first, IntArray(0), green))
    }

    @Test fun onlyNewApprovedClipsWithMatchingEndpointsUseDirectRepeat() {
        val dir = Files.createTempDirectory("loop-qa").toFile()
        try {
            val video = dir.resolve("idle.mp4")
            val qa = dir.resolve("idle.qa.json")
            val uri = Uri.fromFile(video)
            assertFalse(isVerifiedGeneratedLoop(uri))
            qa.writeText("""{"hard_pass":true,"endpoint_delta":0.001}""")
            assertFalse(isVerifiedGeneratedLoop(uri)) // Old QA has no exact-frame measurement.
            qa.writeText("""{"hard_pass":false,"loop_seam":{"endpoint_delta":0.001}}""")
            assertFalse(isVerifiedGeneratedLoop(uri))
            qa.writeText("""{"hard_pass":true,"loop_seam":{"endpoint_delta":0.02}}""")
            assertFalse(isVerifiedGeneratedLoop(uri))
            qa.writeText("""{"hard_pass":true,"loop_seam":{"endpoint_delta":0.003}}""")
            assertTrue(isVerifiedGeneratedLoop(uri))
            assertFalse(isVerifiedGeneratedLoop(uri, sleepSection = true))
            qa.writeText("""{"hard_pass":true,"loop_seam":{"endpoint_delta":0.003},"sleep_loop_seam":{"endpoint_delta":0.004}}""")
            assertTrue(isVerifiedGeneratedLoop(uri, sleepSection = true))
        } finally { dir.deleteRecursively() }
    }
}
