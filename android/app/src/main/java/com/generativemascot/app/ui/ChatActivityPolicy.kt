package com.generativemascot.app.ui

/** Choose a transition without ever restarting a provider request. */
internal fun chatActivityTarget(
    busy: Boolean, reaction: String?, current: String?, idles: List<String>, thinking: String?,
): String? = when {
    reaction != null -> reaction
    busy -> thinking ?: idles.firstOrNull()
    current == null || current == thinking -> idles.firstOrNull()
    else -> null
}

internal fun isStaleChatReaction(pending: String?, reaction: String?, activities: Collection<String>): Boolean =
    pending != null && reaction == null && pending !in activities
