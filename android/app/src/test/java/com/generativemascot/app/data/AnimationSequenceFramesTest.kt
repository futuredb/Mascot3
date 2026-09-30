package com.generativemascot.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationSequenceFramesTest {
    private fun filtered(frames: List<String>) = animationFramesWithoutZero(frames) { it }

    @Test fun zeroIsExcludedWithAnyPaddingAndNotJustByPosition() {
        for (zero in listOf("frame_0.png", "frame_00.png", "frame_000.png", "frame_0000.png")) {
            assertEquals(listOf("frame_001.png", "frame_002.png"),
                filtered(listOf("frame_001.png", zero, "frame_002.png")))
        }
    }

    @Test fun filteringTwiceNeverDropsTheFirstMotionFrame() {
        val frames = listOf("frame_000.png", "frame_001.png", "frame_002.png")
        assertEquals(frames.drop(1), filtered(filtered(frames)))
    }

    @Test fun localAndRemotePathsUseTheActualFrameIndex() {
        val one = "file:///hero/sequences/idle/frame_001.png"
        assertEquals(listOf(one), filtered(listOf("file:///hero/sequences/idle/frame_000.png", one)))
        assertEquals(listOf("https://host/frame_01.png?token=abc"),
            filtered(listOf("https://host/frame_00.png?token=abc#preview", "https://host/frame_01.png?token=abc")))
    }

    @Test fun staticPreviewEmptyAndAlreadyTrimmedSequenceRemainUnchanged() {
        for (frames in listOf(emptyList(), listOf("frame_000.png"),
            listOf("base.png"), listOf("frame_001.png", "frame_002.png"))) {
            assertEquals(frames, filtered(frames))
        }
    }
}
