package com.generativemascot.app.ui

import com.generativemascot.app.data.MascotDto
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HeroStartupTest {
    @Test fun stalledLookupsExpireTogetherWithoutReplacingTheCachedHero() = runBlocking {
        var cancelled = 0
        val remote = loadStartupHeroes("pending", "accepted",
            getHero = { try { delay(60_000); error("Unreachable") } finally { cancelled++ } },
            activeHero = { try { delay(60_000); null } finally { cancelled++ } }, timeoutMs = 40)
        assertTrue(remote.unavailable)
        assertEquals(3, cancelled)
        val local = MascotDto(id = "accepted", status = "READY")
        assertSame(local, restoredHomeHero(remote.restored, remote.accepted, local, remote.active, null))
    }

    @Test fun aStalledActiveLookupDoesNotDiscardACompletedPendingJob() = runBlocking {
        val pending = MascotDto(id = "pending", status = "BASE_GENERATING")
        val remote = loadStartupHeroes("pending", "accepted",
            getHero = { if (it == "pending") pending else MascotDto(id = it, status = "READY") },
            activeHero = { delay(60_000); null }, timeoutMs = 40)
        assertTrue(remote.unavailable)
        assertSame(pending, restoredHomeHero(remote.restored, remote.accepted,
            MascotDto(id = "accepted", status = "READY"), remote.active, null))
    }

    @Test fun theSameSavedAndAcceptedHeroIsOnlyLookedUpOnce() = runBlocking {
        var calls = 0
        val hero = MascotDto(id = "accepted", status = "READY")
        val remote = loadStartupHeroes("accepted", "accepted", { calls++; hero }, { null })
        assertEquals(1, calls)
        assertSame(hero, remote.restored)
        assertSame(hero, remote.accepted)
        assertFalse(remote.unavailable)
    }

    @Test fun leavingTheScreenCancelsLookupsRatherThanTreatingCancellationAsOffline() = runBlocking {
        try {
            withTimeout(40) {
                loadStartupHeroes("saved", "accepted", { delay(60_000); error("Unreachable") },
                    { delay(60_000); null })
            }
            fail("Parent cancellation was swallowed")
        } catch (_: TimeoutCancellationException) { }
    }
}
