package com.generativemascot.app.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AcceptanceGreetingStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun store() = AcceptanceGreetingStore(context)

    @Test fun existingHeroesAndDraftsDoNotAuthorizePaidGreeting() {
        assertNull(store().pending())
        store().saveDraft("new")
        assertEquals("new", store().draftId())
        assertEquals(setOf("new"), store().unacceptedIds())
        assertNull(store().pending())
        assertNull(store().request("old-hero"))
    }

    @Test fun repeatedAcceptanceAndProcessRecreationUseExactlyTheSameRequest() {
        val first = store().begin("hero", GenerationRoute.OPENROUTER_DIRECT)
        repeat(5) {
            assertEquals(first, store().begin("hero", GenerationRoute.TEAM_SERVER))
            assertEquals(first, store().pending())
        }
    }

    @Test fun cannotAcceptAnotherHeroWhileFirstGreetingIsPending() {
        store().begin("first", GenerationRoute.OPENROUTER_DIRECT)
        assertTrue(runCatching { store().begin("second", GenerationRoute.OPENROUTER_DIRECT) }.isFailure)
        assertNull(store().request("second"))
    }

    @Test fun resumeRetainsProviderTokenAndRouteSoAmbiguousSubmissionCannotBePaidTwice() {
        val first = store().begin("hero", GenerationRoute.OPENROUTER_DIRECT)
        val resumed = store().resume("hero")
        assertEquals(first.token, resumed.token)
        assertEquals(first.route, resumed.route)
        assertNotEquals(first.workId, resumed.workId)
        val markers = File(context.filesDir, "test-greeting-markers")
        claimVideoSubmission(markers, first.token, "greeting")
        assertTrue(runCatching { claimVideoSubmission(markers, resumed.token, "greeting") }.isFailure)
    }

    @Test fun completedGreetingCannotSilentlyAuthorizeAnotherPaidClip() {
        store().saveDraft("hero")
        val first = store().begin("hero", GenerationRoute.TEAM_SERVER)
        store().complete("hero")
        assertNull(store().pending())
        assertNull(store().draftId())
        assertFalse("hero" in store().unacceptedIds())
        assertEquals(first.copy(completed = true), store().begin("hero", GenerationRoute.OPENROUTER_DIRECT))
        assertTrue(runCatching { store().resume("hero") }.isFailure)
    }

    @Test fun rejectedDraftsStayOutOfHeroLibraryWithoutDeletingTheirFiles() {
        store().saveDraft("rejected")
        store().saveDraft("kept")
        assertEquals(setOf("rejected", "kept"), store().unacceptedIds())
        store().clearDraft("kept")
        assertEquals(setOf("rejected"), store().unacceptedIds())
    }

    @Test fun greetingIsOneOfSeventeenAndFollowingBatchNeverRegeneratesIt() {
        assertEquals(listOf("greeting"), validateAnimationBatch(listOf("greeting")))
        val next = planAnimationBatch(setOf("greeting"), emptySet(), 4)
        assertEquals(listOf("idle", "joyful", "sleeping", "dancing"), next)
        assertEquals(16, planAnimationBatch(setOf("greeting"), emptySet(), 17).size)
    }

    @Test fun deferDoesNotRestartGenerationOrLoseOriginalAuthorization() {
        val first = store().begin("hero", GenerationRoute.OPENROUTER_DIRECT)
        store().defer("hero")
        assertNull(store().pending())
        assertEquals(first, store().request("hero"))
        assertEquals(first, store().begin("hero", GenerationRoute.TEAM_SERVER))
        assertEquals(first, store().pending())
        assertEquals(first.token, store().resume("hero").token)
    }
}
