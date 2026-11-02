package com.generativemascot.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCalendarTest {
    @Test fun detectsCalendarQuestionsWithoutSendingEventsForUnrelatedMessages() {
        assertTrue(isCalendarQuestion("Что у меня в календаре завтра?"))
        assertTrue(isCalendarQuestion("Проверь расписание встреч"))
        assertTrue(isCalendarQuestion("What is on my calendar?"))
        assertFalse(isCalendarQuestion("Как приготовить салат?"))
        assertFalse(isCalendarQuestion("Расскажи о теннисе"))
    }
}
