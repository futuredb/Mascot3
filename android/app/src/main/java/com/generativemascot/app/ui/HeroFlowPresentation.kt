package com.generativemascot.app.ui

/** Approval enters home immediately; greeting generation never reopens the knock screen. */
val AppUiState.showsHeroHome: Boolean
    get() = mascot?.status == "READY" || greetingPending

fun heroFlowContentKey(state: AppUiState): String = when (state.route) {
    "knock", "onboarding", "create", "progress", "meet", "hero" ->
        if (state.showsHeroHome) "home" else "generation"
    else -> state.route
}

/** Synchronous tap feedback, before disk/network work. This does not enqueue any job. */
internal fun AppUiState.withGreetingStarted(): AppUiState = copy(
    route = "knock", mascot = mascot?.copy(status = "READY"), context = null,
    busy = true, creating = true, greetingPending = true, error = null,
    generationLabel = "Готовим приветствие — одну анимацию…",
    animationBatchActions = listOf("greeting"),
)
