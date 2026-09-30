package com.generativemascot.app.ui

/** Activity-local foreground events survive configuration changes in the ViewModel. */
internal class ForegroundWelcomeGate {
    private var foreground = false
    private var serial = 0L
    var pending: Long? = null
        private set

    fun enter(): Long? {
        if (!foreground) {
            foreground = true
            pending = ++serial
        }
        return pending
    }

    fun leave() {
        foreground = false
        pending = null
    }

    /** A newly accepted hero can greet even if the previous hero already consumed app entry. */
    fun requestGreeting(): Long? {
        if (foreground) pending = ++serial
        return pending
    }

    fun consume(requestId: Long): Boolean {
        if (!foreground || pending != requestId) return false
        pending = null
        return true
    }
}

/** Playback only; never request generation to fill a missing app-entry asset. */
internal fun entryWelcomeAction(available: Set<String>): String? = when {
    "greeting" in available -> "greeting"
    else -> null
}

internal fun greetingIsOnlyVideo(available: Set<String>): Boolean =
    available.map(com.generativemascot.app.data.HeroLocalStore::normalizeVideoAction).toSet() == setOf("greeting")

/** Baseline for a partially generated pack; never require an absent idle asset. */
internal fun defaultHomeVideoAction(available: Set<String>): String {
    val saved = available.map(com.generativemascot.app.data.HeroLocalStore::normalizeVideoAction).toSet()
    return when {
        "idle" in saved -> "idle"
        "greeting" in saved -> "greeting"
        else -> saved.firstOrNull() ?: "idle"
    }
}

/** Never claim a sleeping/joyful state when its video has not been generated yet. */
internal fun resolveAvailableHomeAction(requested: String, available: Set<String>): String {
    val action = com.generativemascot.app.data.HeroLocalStore.normalizeVideoAction(requested)
    val saved = available.map(com.generativemascot.app.data.HeroLocalStore::normalizeVideoAction).toSet()
    if (saved.isEmpty() || action in saved) return action // Legacy PNG/rigged heroes retain their controls.
    return when {
        "greeting" in saved -> "greeting"
        "idle" in saved -> "idle"
        else -> saved.first()
    }
}

internal fun shouldInterruptHomeClip(current: String, next: String, idle: String): Boolean =
    next != current && (next == "greeting" || current == "greeting" ||
        current == idle || current == "sleeping" || current == "sleep_loop")
