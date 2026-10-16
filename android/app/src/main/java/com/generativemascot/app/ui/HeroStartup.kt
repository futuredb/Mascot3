package com.generativemascot.app.ui

import com.generativemascot.app.data.MascotDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException

internal const val STARTUP_REMOTE_TIMEOUT_MS = 8_000L

internal data class StartupHeroes(
    val restored: MascotDto?,
    val accepted: MascotDto?,
    val active: MascotDto?,
    val unavailable: Boolean,
)

private data class StartupRead(val hero: MascotDto?, val unavailable: Boolean = false)

/** These are reads only. A slow lookup must not hide cached heroes or retry paid work. */
internal suspend fun loadStartupHeroes(
    savedId: String?,
    acceptedId: String?,
    getHero: suspend (String) -> MascotDto,
    activeHero: suspend () -> MascotDto?,
    timeoutMs: Long = STARTUP_REMOTE_TIMEOUT_MS,
): StartupHeroes = coroutineScope {
    suspend fun read(block: suspend () -> MascotDto?): StartupRead = try {
        withTimeoutOrNull(timeoutMs) { StartupRead(block()) } ?: StartupRead(null, true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (missing: HttpException) {
        StartupRead(null, missing.code() != 404)
    } catch (_: Exception) {
        StartupRead(null, true)
    }
    val restored = async { read { savedId?.let { getHero(it) } } }
    val accepted = if (acceptedId == savedId) restored else async { read { acceptedId?.let { getHero(it) } } }
    val active = async { read(activeHero) }
    val r = restored.await(); val a = accepted.await(); val current = active.await()
    StartupHeroes(r.hero, a.hero, current.hero, r.unavailable || a.unavailable || current.unavailable)
}
