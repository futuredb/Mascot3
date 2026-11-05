package com.generativemascot.app.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GreetingRecoveryTest {
    @Test fun rejectedPaidVideoKeepsItsOriginalReasonAndSubmissionMarker() = runBlocking {
        val store = HeroLocalStore(RuntimeEnvironment.getApplication())
        val id = "recovery-fixture"
        val token = "recovery-token"
        val qa = evaluateVideoQa(action = "greeting", expectedDurationSeconds = 6, durationMs = 6_000,
            width = 960, height = 960, sampleCount = 5, minimumBorderKeyRatio = 1.0,
            minimumFrameMargin = 0.1, endpointDelta = 0.12, matteHex = "#00FF00",
            enforceLoop = true, loopSeam = VideoLoopSeam(0.12, 0.01, 0.01, 0.01))
        assertFalse(qa.hardPass)
        assertFalse(store.videoSubmissionStarted(token, "greeting"))
        store.claimVideoSubmission(token, "greeting")
        store.saveVideoQa(id, "greeting", qa)
        assertNull(store.rejectedVideoQa(id, "greeting")) // QA alone does not prove the video was saved.
        store.quarantineActionVideo(id, "greeting", "provider-job", ByteArray(2048) { 1 }, null)
        assertTrue(store.videoSubmissionStarted(token, "greeting"))
        assertEquals(qa.blockingIssues, store.rejectedVideoQa(id, "greeting")?.blockingIssues)
        assertNull(store.actionVideoFile(id, "greeting"))
    }
}
