package com.generativemascot.app.ui

import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CalendarActionTest {
    @Test fun explicitRequestIsDifferentFromCalendarQuestion() {
        assertTrue(asksToCreateCalendarEvent("Поставь встречу в календаре"))
        assertTrue(asksToCreateCalendarEvent("Напомни мне о враче"))
        assertFalse(asksToCreateCalendarEvent("Что у меня в календаре?"))
        assertFalse(asksToCreateCalendarEvent("Расскажи о теннисе"))
        assertTrue(mentionsScheduledEvent("У меня завтра в девять вечера врач"))
        assertFalse(mentionsScheduledEvent("Завтра в девять буду смотреть фильм"))
        assertTrue(explicitlyRejectsCalendarAction("Не добавляй встречу в календарь"))
        assertFalse(mentionsScheduledEvent("Не добавляй встречу завтра"))
    }

    @Test fun eventRequiresExplicitTimezoneTitleAndOrderedTimes() {
        val start = ZonedDateTime.now().plusDays(1).toOffsetDateTime()
        val valid = JSONObject().put("title", "Врач").put("start", start.toString()).put("end", start.plusHours(1).toString())
        assertNotNull(parseCalendarEvent(valid))
        val withoutEnd = JSONObject(valid.toString()).apply { remove("end") }
        assertTrue(parseCalendarEvent(withoutEnd)!!.defaultDuration)
        assertEquals(30 * 60_000L, parseCalendarEvent(withoutEnd)!!.end - parseCalendarEvent(withoutEnd)!!.start)
        assertNull(parseCalendarEvent(JSONObject(valid.toString()).put("title", " ")))
        assertNull(parseCalendarEvent(JSONObject(valid.toString()).put("end", start.minusHours(1).toString())))
        assertNull(parseCalendarEvent(JSONObject(valid.toString()).put("start", "2027-01-01T10:00:00")))
    }

    @Test fun onlyTheMostActiveModeCanCreateAnEvent() {
        assertTrue(calendarActionAllowed("proactive_solutions", true))
        assertFalse(calendarActionAllowed("proactive_solutions", false))
        listOf("request_solutions", "request_guidance", "proactive_guidance").forEach {
            assertFalse(calendarActionAllowed(it, true))
        }
    }

    @Test fun eventAndOneHourReminderAreWrittenTogether() = runBlocking {
        val provider = RecordingCalendarProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val start = ZonedDateTime.now().plusDays(1).toInstant().toEpochMilli()
        val reply = createGoogleCalendarEvent(RuntimeEnvironment.getApplication(),
            CalendarEventRequest("Врач", start, start + 30 * 60_000L, true))
        assertTrue(reply.contains("напоминание за час"))
        assertEquals("Врач", provider.event?.getAsString(CalendarContract.Events.TITLE))
        assertEquals(60, provider.reminder?.getAsInteger(CalendarContract.Reminders.MINUTES))
        assertEquals(42L, provider.reminder?.getAsLong(CalendarContract.Reminders.EVENT_ID))
    }

    @Test fun multipleCalendarsRequireAnExplicitChoice() = runBlocking {
        val provider = RecordingCalendarProvider(twoCalendars = true)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val start = ZonedDateTime.now().plusDays(1).toInstant().toEpochMilli()
        val event = CalendarEventRequest("Врач", start, start + 30 * 60_000L, true)
        assertTrue(createGoogleCalendarEvent(RuntimeEnvironment.getApplication(), event).contains("однозначно"))
        assertNull(provider.event)
        assertTrue(createGoogleCalendarEvent(RuntimeEnvironment.getApplication(), event, 2L).contains("напоминание за час"))
        assertEquals(2L, provider.event?.getAsLong(CalendarContract.Events.CALENDAR_ID))
    }

    private class RecordingCalendarProvider(private val twoCalendars: Boolean = false) : ContentProvider() {
        var event: ContentValues? = null
        var reminder: ContentValues? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME)).apply {
                addRow(arrayOf(1L, 1, "Тестовый", "test@example.invalid"))
                if (twoCalendars) addRow(arrayOf(2L, 1, "Второй", "other@example.invalid"))
            }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            if (uri == CalendarContract.Events.CONTENT_URI) { event = values; return Uri.withAppendedPath(uri, "42") }
            if (uri == CalendarContract.Reminders.CONTENT_URI) { reminder = values; return Uri.withAppendedPath(uri, "7") }
            error("Unexpected calendar URI")
        }
        override fun getType(uri: Uri): String? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
