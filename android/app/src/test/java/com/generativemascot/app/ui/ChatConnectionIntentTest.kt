package com.generativemascot.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConnectionIntentTest {
    @Test fun recognizesExplicitConnectionRequests() {
        assertTrue(isCalendarConnectionRequest("Хочу подключить календарь"))
        assertTrue(isCalendarConnectionRequest("Connect my Google Calendar"))
    }
    @Test fun ordinaryCalendarQuestionsDoNotOpenSettings() {
        assertFalse(isCalendarConnectionRequest("Что у меня сегодня в календаре?"))
        assertFalse(isCalendarConnectionRequest("Подключи наушники"))
        assertFalse(isCalendarConnectionRequest("Не хочу подключать календарь"))
    }
}
