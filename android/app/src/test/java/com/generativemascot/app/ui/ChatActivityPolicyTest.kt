package com.generativemascot.app.ui

import org.junit.Assert.*
import org.junit.Test

class ChatActivityPolicyTest {
    private val idles = listOf("idle-a", "idle-b")

    @Test fun responseHasPriorityEvenIfTheRequestIsStillBusy() {
        assertEquals("reply", chatActivityTarget(true, "reply", "idle-a", idles, "think"))
    }
    @Test fun waitingUsesThinkingAndFallsBackSafely() {
        assertEquals("think", chatActivityTarget(true, null, "idle-a", idles, "think"))
        assertEquals("idle-a", chatActivityTarget(true, null, "idle-a", idles, null))
        assertNull(chatActivityTarget(true, null, null, emptyList(), null))
    }
    @Test fun finishingOrFailingReturnsFromThinkingToIdle() {
        assertEquals("idle-a", chatActivityTarget(false, null, "think", idles, "think"))
        assertNull(chatActivityTarget(false, null, "idle-b", idles, "think"))
    }
    @Test fun timedOutResponseCannotAppearLater() {
        assertTrue(isStaleChatReaction("reply", null, listOf("idle-a", "think")))
        assertFalse(isStaleChatReaction("reply", "reply", listOf("idle-a", "think")))
        assertFalse(isStaleChatReaction("think", null, listOf("idle-a", "think")))
        assertFalse(isStaleChatReaction(null, null, emptyList()))
    }
}
