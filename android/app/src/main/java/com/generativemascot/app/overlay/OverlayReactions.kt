package com.generativemascot.app.overlay

import kotlin.random.Random

/** A shuffled bag avoids both immediate repeats and starving one of the saved reactions. */
internal class OverlayReactions(clips: List<OverlayVideo>, private val random: Random = Random.Default) {
    private val available = clips.distinctBy { it.file }.toMutableList()
    private val bag = mutableListOf<OverlayVideo>()
    private var previous: OverlayVideo? = null
    var active: OverlayVideo? = null
        private set

    fun take(): OverlayVideo? {
        if (active != null || available.isEmpty()) return null
        if (bag.isEmpty()) {
            bag.addAll(available.shuffled(random))
            if (bag.size > 1 && bag.first() == previous) {
                val swap = bag[0]; bag[0] = bag[1]; bag[1] = swap
            }
        }
        return bag.removeAt(0).also { active = it; previous = it }
    }

    fun finish() { active = null }
    fun discardActive() {
        active?.let { bad -> available.remove(bad); bag.remove(bad) }
        finish()
    }
}
