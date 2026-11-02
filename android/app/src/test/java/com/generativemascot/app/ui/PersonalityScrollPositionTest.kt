package com.generativemascot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalityScrollPositionTest {
    // A 255px viewport with symmetric padding around a 108px card.
    private val start = -73
    private val end = 182

    @Test fun firstAndLastUseTheSameFocusPosition() {
        val first = listOf(PersonalityItemPosition(0, 0, 108), PersonalityItemPosition(1, 108, 108))
        val last = listOf(PersonalityItemPosition(10, -108, 108), PersonalityItemPosition(11, 0, 108))
        assertEquals(0, personalityIndexNearestCenter(first, start, end))
        assertEquals(11, personalityIndexNearestCenter(last, start, end))
    }

    @Test fun selectionMovesNaturallyBetweenAdjacentCards() {
        assertEquals(4, personalityIndexNearestCenter(listOf(
            PersonalityItemPosition(4, -40, 108), PersonalityItemPosition(5, 68, 108)), start, end))
        assertEquals(5, personalityIndexNearestCenter(listOf(
            PersonalityItemPosition(4, -60, 108), PersonalityItemPosition(5, 48, 108)), start, end))
    }

    @Test fun emptyLayoutKeepsExistingSelection() {
        assertNull(personalityIndexNearestCenter(emptyList(), start, end))
    }
}
