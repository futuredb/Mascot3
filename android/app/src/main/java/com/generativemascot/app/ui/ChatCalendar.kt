package com.generativemascot.app.ui

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun isCalendarQuestion(text: String): Boolean {
    val value = text.lowercase(Locale.ROOT)
    return listOf("календар", "расписан", "встреч", "событи", "calendar", "schedule", "event").any(value::contains)
}

/** Read only local Google calendars which Android has already synchronized. */
internal suspend fun readUpcomingGoogleCalendar(context: Context): String = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val end = now + 14L * 24 * 60 * 60 * 1000
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now)
        ContentUris.appendId(it, end)
    }.build()
    val calendarIds = mutableListOf<String>()
    context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.VISIBLE} = ?",
        arrayOf("com.google", "1"), null)?.use { cursor ->
        while (cursor.moveToNext()) calendarIds += cursor.getLong(0).toString()
    }
    if (calendarIds.isEmpty()) return@withContext "На телефоне нет видимых синхронизированных Google-календарей."
    val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
    val events = mutableListOf<String>()
    context.contentResolver.query(uri, projection,
        "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }})",
        calendarIds.toTypedArray(), "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
        val dateTime = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        while (cursor.moveToNext() && events.size < 30) {
            val name = (cursor.getString(0) ?: "Без названия").replace(Regex("[\\r\\n]+"), " ").take(120)
            val allDay = cursor.getInt(3) != 0
            val start = if (allDay) date.format(Date(cursor.getLong(1))) + " (весь день)" else dateTime.format(Date(cursor.getLong(1)))
            events += "$start — $name"
        }
    }
    if (events.isEmpty()) "В синхронизированных Google-календарях нет видимых событий в ближайшие 14 дней."
    else "Ближайшие события из синхронизированных Google-календарей (максимум 30; ближайшие 14 дней):\n" + events.joinToString("\n")
}
