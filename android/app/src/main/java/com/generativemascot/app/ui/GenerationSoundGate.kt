package com.generativemascot.app.ui

/** One-shot UI feedback only. Resuming the app must never replay a pending old sound. */
internal class GenerationSoundGate(
    private val play: () -> Unit,
    private val stop: () -> Unit,
) {
    private var loaded = false
    private var active = false
    private var pending = false
    private var closed = false

    fun resume() { if (!closed) active = true }

    fun request() {
        if (closed || !active) return
        if (loaded) play() else pending = true
    }

    fun onLoaded(success: Boolean) {
        if (closed) return
        loaded = success
        val shouldPlay = success && pending && active
        pending = false
        if (shouldPlay) play()
    }

    fun pause() {
        if (closed) return
        active = false
        pending = false
        stop()
    }

    fun close() {
        if (closed) return
        pause()
        closed = true
    }
}
